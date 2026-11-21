package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record WorldId(UUID value) implements Comparable<WorldId> {
 public WorldId { Objects.requireNonNull(value); }
 public static WorldId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new WorldId(id); }
 public int compareTo(WorldId other) { return value.toString().compareTo(other.value.toString()); }
}
