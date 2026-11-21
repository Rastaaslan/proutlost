package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record SessionId(UUID value) implements Comparable<SessionId> {
 public SessionId { Objects.requireNonNull(value); }
 public static SessionId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new SessionId(id); }
 public int compareTo(SessionId other) { return value.toString().compareTo(other.value.toString()); }
}
