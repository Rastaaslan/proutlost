package fr.proutlost.worldprep.build;

import fr.proutlost.worldprep.plan.CanonicalDigest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/** Streaming, path-sensitive fingerprint; memory is bounded by one directory's entries. */
public record SourceWorldFingerprint(String value) {
    public static SourceWorldFingerprint capture(Path root) throws IOException {
        var digest = CanonicalDigest.sha256();
        visit(root, root, digest);
        return new SourceWorldFingerprint(digest.finish());
    }

    private static void visit(Path root, Path directory, CanonicalDigest digest) throws IOException {
        try (var children = Files.list(directory)) {
            for (Path path : children.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Symbolic links are not allowed in immutable source");
                if (Files.isDirectory(path)) visit(root, path, digest);
                else if (Files.isRegularFile(path)) {
                    digest.putString(root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/"));
                    digest.putLong(Files.size(path));
                    digest.putString(fileDigest(path));
                }
            }
        }
    }

    private static String fileDigest(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
