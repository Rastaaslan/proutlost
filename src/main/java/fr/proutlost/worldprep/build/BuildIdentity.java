package fr.proutlost.worldprep.build;
public record BuildIdentity(String buildId, String sourceFingerprint, String semanticPackFingerprint) {
    public BuildIdentity { if (buildId == null || buildId.isBlank() || sourceFingerprint == null || sourceFingerprint.isBlank() || semanticPackFingerprint == null || semanticPackFingerprint.isBlank()) throw new IllegalArgumentException("Complete build identity required"); }
}
