package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record MutationId(UUID value) implements Comparable<MutationId> {
 public MutationId { Objects.requireNonNull(value); }
 public static MutationId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new MutationId(id); }
 public int compareTo(MutationId other) { return value.toString().compareTo(other.value.toString()); }
}
