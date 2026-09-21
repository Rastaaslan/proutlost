package fr.proutlost.worldprep.pack;

public record RegistryFingerprint(String registryId, String canonicalSha256) implements Comparable<RegistryFingerprint> {
    public RegistryFingerprint {
        if (registryId == null || registryId.isBlank() || canonicalSha256 == null || canonicalSha256.isBlank())
            throw new IllegalArgumentException("Registry id and fingerprint are required");
    }
    @Override public int compareTo(RegistryFingerprint other) { return registryId.compareTo(other.registryId); }
}
