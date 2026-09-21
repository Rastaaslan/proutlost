package fr.proutlost.worldprep.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;

/** Resolves identifiers below configured roots; callers never supply unrestricted paths. */
public final class BuildWorkspaceManager {
    private final Path sourceRoot;
    private final Path buildRoot;

    public BuildWorkspaceManager(Path sourceRoot, Path buildRoot) throws IOException {
        this.sourceRoot = sourceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        this.buildRoot = buildRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        rejectOverlap(this.sourceRoot, this.buildRoot);
    }

    public WorldPrepBuild validate(String sourceId, String buildId, BuildIdentity identity) throws IOException {
        Path source = resolveIdentifier(sourceRoot, sourceId);
        Path candidate = resolveIdentifier(buildRoot, buildId);
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source is not an existing directory");
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Candidate already exists; explicit recovery required");
        rejectOverlap(source, candidate);
        return new WorldPrepBuild(identity, source, candidate, BuildState.SOURCE_VERIFIED);
    }


    /** Copies through a marked staging directory and publishes only a verified complete candidate. */
    public WorldPrepBuild createCandidate(String sourceId, String buildId, BuildIdentity identity) throws IOException {
        WorldPrepBuild validated = validate(sourceId, buildId, identity);
        SourceWorldFingerprint before = SourceWorldFingerprint.capture(validated.source());
        if (!before.value().equals(identity.sourceFingerprint())) throw new IOException("Source fingerprint mismatch");
        Path staging = buildRoot.resolve("." + buildId + ".partial");
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Partial candidate requires explicit recovery");
        Files.createDirectory(staging);
        Files.writeString(staging.resolve(".worldprep-state"), BuildState.SOURCE_VERIFIED.name(), StandardCharsets.UTF_8);
        try {
            copyTree(validated.source(), staging);
            SourceWorldFingerprint copied = SourceWorldFingerprint.capture(staging);
            // The lifecycle marker is not source content, so verify again after temporarily excluding it.
            Files.delete(staging.resolve(".worldprep-state"));
            copied = SourceWorldFingerprint.capture(staging);
            if (!copied.equals(before)) throw new IOException("Candidate copy verification failed");
            if (!SourceWorldFingerprint.capture(validated.source()).equals(before)) throw new IOException("Immutable source changed during copy");
            Files.writeString(staging.resolve(".worldprep-state"), BuildState.PLANNING.name(), StandardCharsets.UTF_8);
            try { Files.move(staging, validated.candidate(), StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException exception) { Files.move(staging, validated.candidate()); }
            return new WorldPrepBuild(identity, validated.source(), validated.candidate(), BuildState.PLANNING);
        } catch (IOException | RuntimeException failure) {
            // Keep the explicitly marked partial tree for forensic recovery; never reuse it implicitly.
            throw failure;
        }
    }

    public BuildState readState(Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(buildRoot)) throw new IllegalArgumentException("Candidate escapes build root");
        return BuildState.valueOf(Files.readString(normalized.resolve(".worldprep-state"), StandardCharsets.UTF_8).trim());
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var children = Files.list(source)) {
            for (Path path : children.sorted(java.util.Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Symbolic links are not allowed in immutable source");
                Path destination = target.resolve(path.getFileName().toString());
                if (Files.isDirectory(path)) { Files.createDirectory(destination); copyTree(path, destination); }
                else Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    static Path resolveIdentifier(Path root, String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw new IllegalArgumentException("Invalid workspace identifier");
        Path result = root.resolve(id).normalize();
        if (!result.startsWith(root)) throw new IllegalArgumentException("Workspace escapes configured root");
        return result;
    }

    static void rejectOverlap(Path source, Path target) {
        Path a = source.toAbsolutePath().normalize(), b = target.toAbsolutePath().normalize();
        if (a.equals(b) || a.startsWith(b) || b.startsWith(a)) throw new IllegalArgumentException("Source and candidate paths overlap");
    }
}
