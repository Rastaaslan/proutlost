package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record OwnerToken(UUID value) implements Comparable<OwnerToken> {
 public OwnerToken { Objects.requireNonNull(value); }
 public static OwnerToken parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new OwnerToken(id); }
 public int compareTo(OwnerToken other) { return value.toString().compareTo(other.value.toString()); }
}
