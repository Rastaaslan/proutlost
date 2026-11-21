package fr.proutlost.worldprep.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Canonical length-prefixed SHA-256 encoder for persisted WorldPrep identities. */
public final class CanonicalDigest {
    private final MessageDigest digest;

    private CanonicalDigest() {
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    public static CanonicalDigest sha256() {
        return new CanonicalDigest();
    }

    public CanonicalDigest putInt(int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
        return this;
    }

    public CanonicalDigest putLong(long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            digest.update((byte) (value >>> shift));
        }
        return this;
    }

    public CanonicalDigest putUuid(UUID value) {
        Objects.requireNonNull(value, "value");
        return putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
    }

    public CanonicalDigest putString(String value) {
        Objects.requireNonNull(value, "value");
        return putBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    public CanonicalDigest putBytes(byte[] value) {
        Objects.requireNonNull(value, "value");
        putInt(value.length);
        digest.update(value);
        return this;
    }

    public String finish() {
        return HexFormat.of().formatHex(digest.digest());
    }
}
