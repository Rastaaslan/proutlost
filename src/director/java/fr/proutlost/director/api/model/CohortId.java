package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record CohortId(UUID value) implements Comparable<CohortId> {
 public CohortId { Objects.requireNonNull(value); }
 public static CohortId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new CohortId(id); }
 public int compareTo(CohortId other) { return value.toString().compareTo(other.value.toString()); }
}
