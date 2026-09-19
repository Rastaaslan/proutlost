package fr.proutlost.worldprep.pack;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record ModArtifact(String modId, String version, String jarSha256,
                          ContentClassification classification, Set<ContentCapability> capabilities) {
    public ModArtifact {
        modId = require(modId, "modId");
        version = require(version, "version");
        jarSha256 = require(jarSha256, "jarSha256");
        Objects.requireNonNull(classification, "classification");
        capabilities = Set.copyOf(new TreeSet<>(Objects.requireNonNull(capabilities, "capabilities")));
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
