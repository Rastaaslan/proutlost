package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record PermitId(UUID value) implements Comparable<PermitId> {
 public PermitId { Objects.requireNonNull(value); }
 public static PermitId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new PermitId(id); }
 public int compareTo(PermitId other) { return value.toString().compareTo(other.value.toString()); }
}
