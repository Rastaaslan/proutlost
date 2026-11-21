package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record OperationToken(UUID value) implements Comparable<OperationToken> {
 public OperationToken { Objects.requireNonNull(value); }
 public static OperationToken parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new OperationToken(id); }
 public int compareTo(OperationToken other) { return value.toString().compareTo(other.value.toString()); }
}
