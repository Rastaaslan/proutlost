package fr.proutlost.worldprep.pack;
public record AdapterFingerprint(String adapterId, String version, String sha256) implements Comparable<AdapterFingerprint> {
    public AdapterFingerprint {
        if (adapterId == null || adapterId.isBlank() || version == null || version.isBlank() || sha256 == null || sha256.isBlank())
            throw new IllegalArgumentException("Complete adapter identity required");
    }
    @Override public int compareTo(AdapterFingerprint other) { return adapterId.compareTo(other.adapterId); }
}
