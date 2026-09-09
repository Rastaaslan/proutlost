package fr.proutlost.worldprep.storage;

import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.plan.CanonicalDigest;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Immutable, checksummed plan/journal page codec with validated safe publication. */
public final class DurablePageStore {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private static final int MAGIC = 0x57505047;

    public enum StorageKind { PLAN, JOURNAL }

    public record Page(StorageKind storageKind, UUID ownerId, PassId passId, String dimension,
                       int chunkX, int chunkZ, long sequence, int entryCount, byte[] payload, String checksum) {
        public Page {
            Objects.requireNonNull(storageKind, "storageKind");
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(passId, "passId");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(checksum, "checksum");
            payload = payload.clone();
            if (dimension.isBlank() || sequence < 0 || entryCount < 0 || payload.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Invalid page metadata");
            }
            new PlanFingerprint(checksum);
            String computed = fingerprint(storageKind, ownerId, passId, dimension, chunkX, chunkZ,
                    sequence, entryCount, payload);
            if (!computed.equals(checksum)) {
                throw new IllegalArgumentException("WorldPrep page identity checksum mismatch");
            }
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    public static Page page(StorageKind kind, UUID owner, PassId pass, String dimension,
                            int chunkX, int chunkZ, long sequence, int entryCount, byte[] payload) {
        byte[] copy = Objects.requireNonNull(payload, "payload").clone();
        return new Page(kind, owner, pass, dimension, chunkX, chunkZ, sequence, entryCount, copy,
                fingerprint(kind, owner, pass, dimension, chunkX, chunkZ, sequence, entryCount, copy));
    }

    public static String fingerprint(StorageKind kind, UUID owner, PassId pass, String dimension,
                                     int chunkX, int chunkZ, long sequence, int entryCount, byte[] payload) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(pass, "pass");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(payload, "payload");
        return CanonicalDigest.sha256()
                .putString("worldprep-page")
                .putInt(FORMAT_VERSION)
                .putString(kind.name())
                .putUuid(owner)
                .putString(pass.name())
                .putString(dimension)
                .putInt(chunkX)
                .putInt(chunkZ)
                .putLong(sequence)
                .putInt(entryCount)
                .putBytes(payload)
                .finish();
    }

    public static void publish(Path target, Page page) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 OutputStream raw = java.nio.channels.Channels.newOutputStream(channel);
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw))) {
                write(out, page);
                out.flush();
                channel.force(true);
            }
            Page reread = read(temporary);
            if (reread.storageKind() != page.storageKind() || !reread.ownerId().equals(page.ownerId())
                    || reread.passId() != page.passId() || !reread.dimension().equals(page.dimension())
                    || reread.chunkX() != page.chunkX() || reread.chunkZ() != page.chunkZ()
                    || reread.sequence() != page.sequence() || reread.entryCount() != page.entryCount()
                    || !reread.checksum().equals(page.checksum()) || !Arrays.equals(reread.payload(), page.payload())) {
                throw new IOException("Temporary page validation failed");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
            forceDirectory(target.toAbsolutePath().getParent());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static Page read(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("Not a WorldPrep page");
            }
            int format = in.readInt();
            if (format != FORMAT_VERSION) {
                throw new IOException("Unsupported page format " + format);
            }
            StorageKind kind = enumValue(StorageKind.class, in.readUTF(), "storage kind");
            UUID owner = new UUID(in.readLong(), in.readLong());
            PassId pass = enumValue(PassId.class, in.readUTF(), "pass");
            String dimension = in.readUTF();
            int chunkX = in.readInt();
            int chunkZ = in.readInt();
            long sequence = in.readLong();
            int count = in.readInt();
            int length = in.readInt();
            if (count < 0 || length < 0 || length > MAX_PAYLOAD_BYTES) {
                throw new IOException("Invalid page sizes");
            }
            byte[] payload = in.readNBytes(length);
            if (payload.length != length) {
                throw new EOFException();
            }
            String expected = in.readUTF();
            if (in.read() != -1) {
                throw new IOException("Trailing page data");
            }
            String computed = fingerprint(kind, owner, pass, dimension, chunkX, chunkZ, sequence, count, payload);
            if (!computed.equals(expected)) {
                throw new IOException("Page identity checksum mismatch");
            }
            try {
                return new Page(kind, owner, pass, dimension, chunkX, chunkZ, sequence, count, payload, expected);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid WorldPrep page", exception);
            }
        } catch (EOFException exception) {
            throw new IOException("Truncated WorldPrep page", exception);
        }
    }

    private static void write(DataOutputStream out, Page page) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(FORMAT_VERSION);
        out.writeUTF(page.storageKind().name());
        out.writeLong(page.ownerId().getMostSignificantBits());
        out.writeLong(page.ownerId().getLeastSignificantBits());
        out.writeUTF(page.passId().name());
        out.writeUTF(page.dimension());
        out.writeInt(page.chunkX());
        out.writeInt(page.chunkZ());
        out.writeLong(page.sequence());
        out.writeInt(page.entryCount());
        byte[] payload = page.payload();
        out.writeInt(payload.length);
        out.write(payload);
        out.writeUTF(page.checksum());
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String label) throws IOException {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Unknown " + label + " " + value, exception);
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is best-effort on filesystems that do not expose it.
        }
    }

    private DurablePageStore() {}
}
