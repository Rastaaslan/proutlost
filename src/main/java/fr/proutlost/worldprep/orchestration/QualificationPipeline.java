package fr.proutlost.worldprep.orchestration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Durable phase boundary ledger, independent of engines and existing SavedData formats.
 * An interrupted intent MUST be reconciled by the phase engine, never blindly executed again.
 * Evidence digests identify externally validated evidence; this ledger does not validate world truth.
 * One owner holds the OS lock for its entire lifetime, including all engine calls.
 */
public final class QualificationPipeline implements AutoCloseable {
    public enum Phase {
        ECOLOGY_RECEIPTS, INTEGRITY_AND_BACKUP, STRUCTURES, STRUCTURE_DISK_AUDIT,
        FAUNA, POPULATION_DISK_AUDIT, CATALOG, DIRECTOR_IMPORT, FINAL_AUDIT
    }
    public record Pending(Phase phase, UUID operation, boolean reconciliationRequired) {}
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;
    private final UUID batch;
    private final String config;
    private boolean closed;

    public QualificationPipeline(Path directory, UUID batch, String configSha256) throws IOException {
        requireDigest(configSha256);
        this.batch = java.util.Objects.requireNonNull(batch);
        this.config = configSha256;
        this.directory = directory.toAbsolutePath().normalize();
        // No symlink ancestors: the ledger must not be redirected outside its isolated root.
        for (Path p = this.directory; p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("Symlink ledger refused");
        Files.createDirectories(this.directory);
        Path lockPath = this.directory.resolve("owner.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("Symlink lock refused");
        channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Pipeline already owned");
        } catch (IOException | OverlappingFileLockException e) {
            channel.close(); throw new IOException("Pipeline already owned or lock unavailable", e);
        }
        lock = acquired;
        try {
            publish("identity", "worldprep-qualification-v1\n" + batch + "\n" + config + "\n");
            cursor();
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }

    public synchronized int cursor() throws IOException {
        owned();
        String previous = hash(read("identity"));
        int count = 0;
        boolean gap = false;
        for (Phase phase : Phase.values()) {
            String intent = intent(phase, previous);
            boolean hasIntent = exists(name(phase, "intent"));
            boolean hasReceipt = exists(name(phase, "receipt"));
            if (hasIntent && !read(name(phase, "intent")).equals(intent)) throw new IOException("Intent identity mismatch");
            if (hasReceipt) {
                if (gap || !hasIntent) throw new IOException("Receipt without contiguous intent chain");
                String receipt = read(name(phase, "receipt"));
                String prefix = intent + "evidence=";
                if (!receipt.startsWith(prefix) || receipt.length() != prefix.length() + 65
                        || !receipt.endsWith("\n")) throw new IOException("Malformed receipt");
                try { requireDigest(receipt.substring(prefix.length(), receipt.length() - 1)); }
                catch (IllegalArgumentException e) { throw new IOException("Invalid evidence digest", e); }
                previous = hash(receipt); count++;
            } else {
                if (gap && hasIntent) throw new IOException("Intent beyond incomplete phase");
                gap = true;
            }
        }
        return count;
    }

    /** Call only after the external resource guard and preceding evidence validators succeed. */
    public synchronized Pending begin() throws IOException {
        int index = cursor();
        if (index == Phase.values().length) throw new IOException("Qualification complete; publication remains external");
        Phase phase = Phase.values()[index];
        String previous = previous(index);
        boolean interrupted = exists(name(phase, "intent"));
        publish(name(phase, "intent"), intent(phase, previous));
        return new Pending(phase, operation(phase), interrupted);
    }

    /** Engine must prove its durable postconditions, including reconciliation of a prior intent. */
    public synchronized void complete(Pending pending, String verifiedEvidenceSha256) throws IOException {
        requireDigest(verifiedEvidenceSha256);
        int index = cursor();
        if (index == Phase.values().length || Phase.values()[index] != pending.phase()
                || !operation(pending.phase()).equals(pending.operation())) throw new IOException("Stale phase completion");
        String expected = intent(pending.phase(), previous(index));
        if (!read(name(pending.phase(), "intent")).equals(expected)) throw new IOException("Missing phase intent");
        publish(name(pending.phase(), "receipt"), expected + "evidence=" + verifiedEvidenceSha256 + "\n");
    }

    /** Preserve the actual frozen configuration, not only its digest. */
    public synchronized void archiveConfiguration(String canonicalConfiguration) throws IOException {
        owned();
        if(canonicalConfiguration.getBytes(StandardCharsets.UTF_8).length>4096
                ||!hash(canonicalConfiguration).equals(config))throw new IOException("Configuration size/digest mismatch");
        publish("configuration",canonicalConfiguration);
    }
    /** A phase's small verified receipt is retained before its cursor can advance. */
    public synchronized String archiveEvidence(Pending pending,String evidence) throws IOException {
        owned();
        if(evidence.isBlank()||evidence.getBytes(StandardCharsets.UTF_8).length>4096)throw new IOException("Evidence receipt size");
        publish(name(pending.phase(),"evidence"),evidence);
        return hash(evidence);
    }

    private String previous(int index) throws IOException {
        return hash(read(index == 0 ? "identity" : name(Phase.values()[index - 1], "receipt")));
    }
    private UUID operation(Phase phase) {
        return UUID.nameUUIDFromBytes((batch + "/" + config + "/" + phase).getBytes(StandardCharsets.UTF_8));
    }
    private String intent(Phase phase, String previous) {
        return batch + "\n" + config + "\n" + phase + "\n" + operation(phase) + "\n" + previous + "\n";
    }
    private static String name(Phase phase, String suffix) { return phase.ordinal() + "-" + phase + "." + suffix; }
    private boolean exists(String name) { return Files.exists(directory.resolve(name), LinkOption.NOFOLLOW_LINKS); }
    private String read(String name) throws IOException {
        Path file = directory.resolve(name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4096) throw new IOException("Missing/oversized ledger record");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
    private void publish(String name, String value) throws IOException {
        owned();
        if (exists(name)) {
            if (!read(name).equals(value)) throw new IOException("Immutable ledger conflict");
            return;
        }
        // Abandoned staging files are retained as crash evidence, never mistaken for receipts.
        Path temp = Files.createTempFile(directory, ".pending-", ".partial");
        try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(value);
            while (bytes.hasRemaining()) out.write(bytes);
            out.force(true);
        }
        Files.move(temp, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
    }
    private void owned() throws IOException {
        if (closed || !lock.isValid()) throw new IOException("Pipeline ownership lost");
    }
    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("SHA256 required");
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    @Override public synchronized void close() throws IOException {
        if (!closed) { closed = true; try { lock.release(); } finally { channel.close(); } }
    }
}
