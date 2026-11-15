package fr.proutlost.contract.director;

/** Canonical immutable wire identity. */
public record Id(String value) implements Comparable<Id> {
    public Id {
        Checks.text(value);
        Checks.require(value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"), "Invalid Id");
    }
    @Override public int compareTo(Id other) { return value.compareTo(other.value); }
    @Override public String toString() { return value; }
}
