package fr.proutlost.director.persistence;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** All callers run on the exclusive writer, including startup and shutdown. */
final class DurableFiles {
    private DurableFiles() {}
    static String hash(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    static String hash(Path path) throws IOException {
        var d = digest(); try (InputStream in = Files.newInputStream(path)) {
            byte[] b = new byte[65536]; for (int n; (n = in.read(b)) >= 0;) d.update(b, 0, n);
        } return HexFormat.of().formatHex(d.digest());
    }
    static void directory(Path dir) throws IOException {
        // Fail closed if this platform cannot sync directories. No unsupported durability claim.
        try (FileChannel c = FileChannel.open(dir, READ)) { c.force(true); }
    }
    static void replace(Path target, byte[] bytes) throws IOException {
        replace(target,bytes,point -> {},"FILE");
    }
    static void replace(Path target, byte[] bytes, JournalStore.IoProbe probe, String kind) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel c = FileChannel.open(temp, CREATE_NEW, WRITE)) { WalFrame.writeFully(c, bytes); probe.at(kind+"_WRITTEN"); c.force(true); probe.at(kind+"_FILE_FORCED"); }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            probe.at(kind+"_RENAMED"); directory(target.getParent());
        } finally { Files.deleteIfExists(temp); }
    }
}
