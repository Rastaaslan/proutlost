package fr.proutlost.contract.director;

/** Canonical immutable wire identity. */
public record Digest(String value) implements Comparable<Digest> {
    public Digest {
        Checks.text(value);
        Checks.require(value.matches("[0-9a-f]{64}"), "Invalid Digest");
    }
    @Override public int compareTo(Digest other) { return value.compareTo(other.value); }
    @Override public String toString() { return value; }
}
