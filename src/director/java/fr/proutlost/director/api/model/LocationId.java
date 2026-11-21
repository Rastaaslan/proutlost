package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record LocationId(UUID value) implements Comparable<LocationId> {
 public LocationId { Objects.requireNonNull(value); }
 public static LocationId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new LocationId(id); }
 public int compareTo(LocationId other) { return value.toString().compareTo(other.value.toString()); }
}
