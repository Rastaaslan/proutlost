package fr.proutlost.director.persistence;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.*;
import java.util.zip.CRC32C;
import fr.proutlost.director.api.codec.CanonicalJsonV1;

/** Exact WDW1 wire format. A damaged complete frame is never treated as an incomplete tail. */
public final class WalFrame {
    public static final int OVERHEAD = 44;
    public enum Type { BEGIN, RECORD, COMMIT }
    public record Frame(Type type, long sequence, UUID transaction, byte[] payload, byte[] encoded) {
        public Frame { payload = payload.clone(); encoded = encoded.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
        @Override public byte[] encoded() { return encoded.clone(); }
    }
    public static final class IncompleteTail extends IOException {
        public IncompleteTail() { super("incomplete WAL frame"); }
    }
    private WalFrame() {}
    public static byte[] encode(Type type, long sequence, UUID transaction, byte[] payload, int maximum) {
        if (sequence <= 0 || payload.length > maximum - OVERHEAD) throw new IllegalArgumentException("frame bounds");
        // Only the frozen canonical UTF-8 representation is accepted.
        if (!Arrays.equals(payload, CanonicalJsonV1.encode(CanonicalJsonV1.decode(payload))))
            throw new IllegalArgumentException("noncanonical payload");
        ByteBuffer b = ByteBuffer.allocate(Math.addExact(OVERHEAD, payload.length)).order(ByteOrder.BIG_ENDIAN);
        b.putInt(0x57445731).putShort((short) 1).put((byte) (type.ordinal() + 1)).put((byte) 0);
        b.putInt(b.capacity()).putLong(sequence).putLong(transaction.getMostSignificantBits())
            .putLong(transaction.getLeastSignificantBits()).putInt(payload.length).put(payload);
        CRC32C crc = new CRC32C(); crc.update(b.array(), 0, b.position()); b.putInt((int) crc.getValue());
        return b.array();
    }
    public static Frame read(SeekableByteChannel channel, int maximum) throws IOException {
        long remaining = channel.size() - channel.position();
        if (remaining == 0) return null;
        if (remaining < 40) throw new IncompleteTail();
        ByteBuffer h = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN); readFully(channel, h); h.flip();
        int magic = h.getInt(), version = Short.toUnsignedInt(h.getShort());
        int type = Byte.toUnsignedInt(h.get()), flags = Byte.toUnsignedInt(h.get()), length = h.getInt();
        long seq = h.getLong(); UUID tx = new UUID(h.getLong(), h.getLong()); int payloadLength = h.getInt();
        if (magic != 0x57445731 || version != 1 || type < 1 || type > 3 || flags != 0 || seq <= 0
                || length < OVERHEAD || length > maximum || payloadLength != length - OVERHEAD)
            throw new IOException("invalid WAL header/version/length");
        if (remaining < length) throw new IncompleteTail();
        byte[] bytes = new byte[length]; System.arraycopy(h.array(), 0, bytes, 0, 40);
        readFully(channel, ByteBuffer.wrap(bytes, 40, length - 40));
        CRC32C crc = new CRC32C(); crc.update(bytes, 0, length - 4);
        if ((int) crc.getValue() != ByteBuffer.wrap(bytes, length - 4, 4).getInt()) throw new IOException("WAL CRC32C mismatch");
        byte[] payload = Arrays.copyOfRange(bytes, 40, length - 4);
        try {
            if (!Arrays.equals(payload, CanonicalJsonV1.encode(CanonicalJsonV1.decode(payload)))) throw new IOException("noncanonical WAL payload");
        } catch (IllegalArgumentException e) { throw new IOException("invalid WAL JSON", e); }
        return new Frame(Type.values()[type - 1], seq, tx, payload, bytes);
    }
    static void readFully(ReadableByteChannel channel, ByteBuffer target) throws IOException {
        while (target.hasRemaining()) if (channel.read(target) < 0) throw new EOFException();
    }
    static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(bytes); while (b.hasRemaining()) channel.write(b);
    }
}
