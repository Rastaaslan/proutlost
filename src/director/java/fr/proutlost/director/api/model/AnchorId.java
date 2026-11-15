package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record AnchorId(UUID value) implements Comparable<AnchorId> {
 public AnchorId { Objects.requireNonNull(value); }
 public static AnchorId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new AnchorId(id); }
 public int compareTo(AnchorId other) { return value.toString().compareTo(other.value.toString()); }
}
