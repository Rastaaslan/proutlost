package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record RequestId(UUID value) implements Comparable<RequestId> {
 public RequestId { Objects.requireNonNull(value); }
 public static RequestId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new RequestId(id); }
 public int compareTo(RequestId other) { return value.toString().compareTo(other.value.toString()); }
}
