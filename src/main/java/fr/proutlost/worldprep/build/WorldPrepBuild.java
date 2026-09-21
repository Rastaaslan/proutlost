package fr.proutlost.worldprep.build;
import java.nio.file.Path;
public record WorldPrepBuild(BuildIdentity identity, Path source, Path candidate, BuildState state) {}
