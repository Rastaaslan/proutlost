package fr.proutlost.worldprep.transaction;
import java.util.Objects;
/** Exact reversible mutation. Before/after include any owned block-entity NBT. */
public record Mutation<K,V>(K key, V before, V after) {
 public Mutation { Objects.requireNonNull(key,"key"); Objects.requireNonNull(before,"before"); Objects.requireNonNull(after,"after"); if (before.equals(after)) throw new IllegalArgumentException("Mutation must change state"); }
}
