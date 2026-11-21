package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record FactId(UUID value) implements Comparable<FactId> {
 public FactId { Objects.requireNonNull(value); }
 public static FactId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new FactId(id); }
 public int compareTo(FactId other) { return value.toString().compareTo(other.value.toString()); }
}
