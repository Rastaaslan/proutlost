package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record TransactionId(UUID value) implements Comparable<TransactionId> {
 public TransactionId { Objects.requireNonNull(value); }
 public static TransactionId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new TransactionId(id); }
 public int compareTo(TransactionId other) { return value.toString().compareTo(other.value.toString()); }
}
