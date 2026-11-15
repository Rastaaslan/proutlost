package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record MemoryId(UUID value) implements Comparable<MemoryId> {
 public MemoryId { Objects.requireNonNull(value); }
 public static MemoryId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new MemoryId(id); }
 public int compareTo(MemoryId other) { return value.toString().compareTo(other.value.toString()); }
}
