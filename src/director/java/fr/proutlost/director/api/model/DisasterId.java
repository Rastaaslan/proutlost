package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record DisasterId(UUID value) implements Comparable<DisasterId> {
 public DisasterId { Objects.requireNonNull(value); }
 public static DisasterId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new DisasterId(id); }
 public int compareTo(DisasterId other) { return value.toString().compareTo(other.value.toString()); }
}
