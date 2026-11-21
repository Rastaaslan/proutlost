package fr.proutlost.contract.director;

/** Canonical immutable wire identity. */
public record Revision(String value) implements Comparable<Revision> {
    public Revision {
        Checks.text(value);
        Checks.require(value.matches("[^\\s]+"), "Invalid Revision");
    }
    @Override public int compareTo(Revision other) { return value.compareTo(other.value); }
    @Override public String toString() { return value; }
}
