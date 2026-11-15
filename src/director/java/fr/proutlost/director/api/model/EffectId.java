package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record EffectId(UUID value) implements Comparable<EffectId> {
 public EffectId { Objects.requireNonNull(value); }
 public static EffectId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new EffectId(id); }
 public int compareTo(EffectId other) { return value.toString().compareTo(other.value.toString()); }
}
