package fr.proutlost.worldprep.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

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
