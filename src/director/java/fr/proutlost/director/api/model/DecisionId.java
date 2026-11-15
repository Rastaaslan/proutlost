package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record DecisionId(UUID value) implements Comparable<DecisionId> {
 public DecisionId { Objects.requireNonNull(value); }
 public static DecisionId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new DecisionId(id); }
 public int compareTo(DecisionId other) { return value.toString().compareTo(other.value.toString()); }
}
