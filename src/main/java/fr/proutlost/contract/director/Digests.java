package fr.proutlost.contract.director;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

public final class Digests {
    private Digests() {}
    public static Digest sha256(String canonical) {
        try {
            return new Digest(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
