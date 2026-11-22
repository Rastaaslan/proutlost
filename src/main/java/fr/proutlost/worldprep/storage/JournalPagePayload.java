package fr.proutlost.worldprep.storage;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Versioned, bounded chunk sections inside one atomic ownership-journal page. */
final class JournalPagePayload {
    static final int MAGIC = 0xD74A5043;
    static final int VERSION = 1;
    private static final int MAX = DurablePageStore.MAX_PAYLOAD_BYTES;

    static <T> byte[] encode(List<T> entries, PagedPlanStore.EntryCodec<T> codec) throws IOException {
        if (entries.isEmpty()) throw new IOException("Empty journal payload");
        int groups = 0;
        for (int i = 0; i < entries.size();) {
            int x = chunkX(codec, entries.get(i)), z = chunkZ(codec, entries.get(i));
            groups++;
            do { i++; } while (i < entries.size() && chunkX(codec, entries.get(i)) == x && chunkZ(codec, entries.get(i)) == z);
        }
        var bytes = new LimitedBytes(MAX);
        var out = new DataOutputStream(bytes);
        out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(entries.size()); out.writeInt(groups);
        int totalRaw = 4;
        for (int start = 0; start < entries.size();) {
            int x = chunkX(codec, entries.get(start)), z = chunkZ(codec, entries.get(start)), end = start + 1;
            while (end < entries.size() && chunkX(codec, entries.get(end)) == x && chunkZ(codec, entries.get(end)) == z) end++;
            var raw = new LimitedBytes(MAX - totalRaw);
            var values = new DataOutputStream(raw);
            for (int i = start; i < end; i++) codec.write(values, entries.get(i));
            values.flush();
            byte[] plain = raw.bytes();
            totalRaw += plain.length;
            byte[] compressed = deflate(plain);
            out.writeInt(x); out.writeInt(z); out.writeInt(end - start);
            out.writeInt(plain.length); out.writeInt(compressed.length); out.write(compressed);
            start = end;
        }
        out.flush();
        return bytes.bytes();
    }

    static boolean isLegacy(byte[] payload) {
        return payload.length >= 4 && java.nio.ByteBuffer.wrap(payload).getInt() >= 0;
    }

    static <T> byte[] legacy(List<T> entries, PagedPlanStore.EntryCodec<T> codec) throws IOException {
        var bytes = new LimitedBytes(MAX);
        var out = new DataOutputStream(bytes);
        out.writeInt(entries.size());
        for (T entry : entries) codec.write(out, entry);
        out.flush();
        return bytes.bytes();
    }

    static void validate(byte[] payload, int count) throws IOException {
        read(payload, count, null);
    }

    static <T> ArrayList<T> decode(byte[] payload, int count, PagedPlanStore.EntryCodec<T> codec) throws IOException {
        return read(payload, count, Objects.requireNonNull(codec));
    }

    private static <T> ArrayList<T> read(byte[] payload, int expected, PagedPlanStore.EntryCodec<T> codec) throws IOException {
        if (payload.length > MAX || expected < 1) throw new IOException("Invalid journal payload bounds");
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            var result = new ArrayList<T>();
            int first = in.readInt();
            if (first >= 0) {
                if (first != expected) throw new IOException("Legacy journal entry count mismatch");
                if (codec == null) return result;
                for (int i = 0; i < expected; i++) result.add(codec.read(in));
            } else {
                if (first != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported journal payload version");
                int count = in.readInt(), groups = in.readInt(), observed = 0, totalRaw = 4;
                if (count != expected || groups < 1 || groups > count) throw new IOException("Invalid journal chunk counts");
                for (int group = 0; group < groups; group++) {
                    int x = in.readInt(), z = in.readInt(), entries = in.readInt();
                    int rawSize = in.readInt(), storedSize = in.readInt();
                    if (entries < 1 || entries > count - observed || rawSize < 0 || rawSize > MAX - totalRaw
                            || storedSize < 1 || storedSize > in.available()) throw new IOException("Invalid journal chunk bounds");
                    totalRaw += rawSize;
                    observed += entries;
                    if (codec == null) { in.skipNBytes(storedSize); continue; }
                    byte[] raw = inflate(in.readNBytes(storedSize), rawSize);
                    try (var values = new DataInputStream(new ByteArrayInputStream(raw))) {
                        for (int i = 0; i < entries; i++) {
                            T value = codec.read(values);
                            if (codec instanceof PagedJournalStore.ChunkCodec<?>
                                    && (chunkX(codec, value) != x || chunkZ(codec, value) != z))
                                throw new IOException("Journal entry outside declared chunk");
                            result.add(value);
                        }
                        if (values.read() != -1) throw new IOException("Trailing decompressed journal chunk");
                    }
                }
                if (observed != count) throw new IOException("Journal chunk entry count mismatch");
            }
            if (in.read() != -1) throw new IOException("Trailing journal page payload");
            return result;
        } catch (EOFException e) { throw new IOException("Truncated journal page payload", e); }
    }

    private static byte[] deflate(byte[] raw) throws IOException {
        var deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(raw); deflater.finish();
            var result = new LimitedBytes(MAX);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count == 0 && !deflater.finished()) throw new IOException("Journal compression stalled");
                result.write(buffer, 0, count);
            }
            return result.bytes();
        } finally { deflater.end(); }
    }

    private static byte[] inflate(byte[] stored, int length) throws IOException {
        var inflater = new Inflater();
        try {
            inflater.setInput(stored);
            byte[] result = new byte[length], extra = new byte[1];
            int offset = 0;
            while (!inflater.finished()) {
                int count = offset < length ? inflater.inflate(result, offset, length - offset) : inflater.inflate(extra);
                if (offset == length && count != 0) throw new IOException("Journal decompression exceeds declared size");
                offset += count;
                if (count == 0 && !inflater.finished()) throw new IOException("Incomplete or invalid journal compression");
            }
            if (offset != length || inflater.getRemaining() != 0) throw new IOException("Journal compressed length mismatch");
            return result;
        } catch (DataFormatException e) { throw new IOException("Invalid journal compression", e); }
        finally { inflater.end(); }
    }

    @SuppressWarnings("unchecked")
    private static <T> int chunkX(PagedPlanStore.EntryCodec<T> codec, T value) {
        return codec instanceof PagedJournalStore.ChunkCodec<?> c ? ((PagedJournalStore.ChunkCodec<T>) c).chunkX(value) : 0;
    }
    @SuppressWarnings("unchecked")
    private static <T> int chunkZ(PagedPlanStore.EntryCodec<T> codec, T value) {
        return codec instanceof PagedJournalStore.ChunkCodec<?> c ? ((PagedJournalStore.ChunkCodec<T>) c).chunkZ(value) : 0;
    }

    private static final class LimitedBytes extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        LimitedBytes(int limit) { this.limit = limit; }
        public void write(int value) throws IOException { require(1); bytes.write(value); }
        public void write(byte[] value, int offset, int count) throws IOException { Objects.checkFromIndexSize(offset, count, value.length); require(count); bytes.write(value, offset, count); }
        private void require(int count) throws IOException { if (count > limit - bytes.size()) throw new IOException("Journal page exceeds existing payload limit"); }
        byte[] bytes() { return bytes.toByteArray(); }
    }

    private JournalPagePayload() {}
}
