package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record CorrelationId(UUID value) implements Comparable<CorrelationId> {
 public CorrelationId { Objects.requireNonNull(value); }
 public static CorrelationId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new CorrelationId(id); }
 public int compareTo(CorrelationId other) { return value.toString().compareTo(other.value.toString()); }
}
