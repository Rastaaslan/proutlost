package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record SliceId(UUID value) implements Comparable<SliceId> {
 public SliceId { Objects.requireNonNull(value); }
 public static SliceId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new SliceId(id); }
 public int compareTo(SliceId other) { return value.toString().compareTo(other.value.toString()); }
}
