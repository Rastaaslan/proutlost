package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record RegionId(UUID value) implements Comparable<RegionId> {
 public RegionId { Objects.requireNonNull(value); }
 public static RegionId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new RegionId(id); }
 public int compareTo(RegionId other) { return value.toString().compareTo(other.value.toString()); }
}
