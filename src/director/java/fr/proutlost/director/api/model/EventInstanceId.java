package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record EventInstanceId(UUID value) implements Comparable<EventInstanceId> {
 public EventInstanceId { Objects.requireNonNull(value); }
 public static EventInstanceId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new EventInstanceId(id); }
 public int compareTo(EventInstanceId other) { return value.toString().compareTo(other.value.toString()); }
}
