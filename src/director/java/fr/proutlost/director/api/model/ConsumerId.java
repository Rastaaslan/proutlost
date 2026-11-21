package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record ConsumerId(UUID value) implements Comparable<ConsumerId> {
 public ConsumerId { Objects.requireNonNull(value); }
 public static ConsumerId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new ConsumerId(id); }
 public int compareTo(ConsumerId other) { return value.toString().compareTo(other.value.toString()); }
}
