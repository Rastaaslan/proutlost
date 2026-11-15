package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record ReservationId(UUID value) implements Comparable<ReservationId> {
 public ReservationId { Objects.requireNonNull(value); }
 public static ReservationId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new ReservationId(id); }
 public int compareTo(ReservationId other) { return value.toString().compareTo(other.value.toString()); }
}
