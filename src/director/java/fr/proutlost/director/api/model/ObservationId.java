package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record ObservationId(UUID value) implements Comparable<ObservationId> {
 public ObservationId { Objects.requireNonNull(value); }
 public static ObservationId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new ObservationId(id); }
 public int compareTo(ObservationId other) { return value.toString().compareTo(other.value.toString()); }
}
