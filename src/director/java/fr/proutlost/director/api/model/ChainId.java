package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record ChainId(UUID value) implements Comparable<ChainId> {
 public ChainId { Objects.requireNonNull(value); }
 public static ChainId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new ChainId(id); }
 public int compareTo(ChainId other) { return value.toString().compareTo(other.value.toString()); }
}
