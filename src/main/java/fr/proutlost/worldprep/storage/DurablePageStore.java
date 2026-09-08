package fr.proutlost.worldprep.storage;

import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;
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
            payload = payload.clone();
            if (dimension.isBlank() || sequence < 0 || entryCount < 0 || payload.length > MAX_PAYLOAD_BYTES)
                throw new IllegalArgumentException("Invalid page metadata");
        }
        @Override public byte[] payload() { return payload.clone(); }
    }
    public static Page page(StorageKind kind, UUID owner, PassId pass, String dimension,
                            int chunkX, int chunkZ, long sequence, int entryCount, byte[] payload) {
        return new Page(kind, owner, pass, dimension, chunkX, chunkZ, sequence, entryCount, payload,
                PlanFingerprint.ofBytes(payload).value());
    }
    public static void publish(Path target, Page page) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 OutputStream raw = java.nio.channels.Channels.newOutputStream(channel);
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw))) {
                write(out, page); out.flush(); channel.force(true);
            }
            Page reread = read(temporary);
            if (reread.storageKind()!=page.storageKind() || !reread.ownerId().equals(page.ownerId())
                    || reread.passId()!=page.passId() || !reread.dimension().equals(page.dimension())
                    || reread.chunkX()!=page.chunkX() || reread.chunkZ()!=page.chunkZ()
                    || reread.sequence()!=page.sequence() || reread.entryCount()!=page.entryCount()
                    || !reread.checksum().equals(page.checksum()) || !Arrays.equals(reread.payload(), page.payload()))
                throw new IOException("Temporary page validation failed");
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException exception) { Files.move(temporary, target); }
            forceDirectory(target.toAbsolutePath().getParent());
        } finally { Files.deleteIfExists(temporary); }
    }
    public static Page read(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != MAGIC) throw new IOException("Not a WorldPrep page");
            int format = in.readInt(); if (format != FORMAT_VERSION) throw new IOException("Unsupported page format " + format);
            StorageKind kind = enumValue(StorageKind.class, in.readUTF(), "storage kind");
            UUID owner = new UUID(in.readLong(), in.readLong());
            PassId pass = enumValue(PassId.class, in.readUTF(), "pass");
            String dimension = in.readUTF(); int chunkX = in.readInt(), chunkZ = in.readInt();
            long sequence = in.readLong(); int count = in.readInt(), length = in.readInt();
            if (count < 0 || length < 0 || length > MAX_PAYLOAD_BYTES) throw new IOException("Invalid page sizes");
            byte[] payload = in.readNBytes(length); if (payload.length != length) throw new EOFException();
            String expected = in.readUTF(); if (in.read() != -1) throw new IOException("Trailing page data");
            if (!PlanFingerprint.ofBytes(payload).value().equals(expected)) throw new IOException("Page checksum mismatch");
            return new Page(kind, owner, pass, dimension, chunkX, chunkZ, sequence, count, payload, expected);
        } catch (EOFException exception) { throw new IOException("Truncated WorldPrep page", exception); }
    }
    private static void write(DataOutputStream out, Page p) throws IOException {
        out.writeInt(MAGIC); out.writeInt(FORMAT_VERSION); out.writeUTF(p.storageKind().name());
        out.writeLong(p.ownerId().getMostSignificantBits()); out.writeLong(p.ownerId().getLeastSignificantBits());
        out.writeUTF(p.passId().name()); out.writeUTF(p.dimension()); out.writeInt(p.chunkX()); out.writeInt(p.chunkZ());
        out.writeLong(p.sequence()); out.writeInt(p.entryCount()); out.writeInt(p.payload().length); out.write(p.payload()); out.writeUTF(p.checksum());
    }
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String label) throws IOException {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException exception) { throw new IOException("Unknown " + label + " " + value, exception); }
    }
    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        catch (IOException | UnsupportedOperationException ignored) { }
    }
    private DurablePageStore() {}
}
