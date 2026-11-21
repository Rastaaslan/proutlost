package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record PlayerId(UUID value) implements Comparable<PlayerId> {
 public PlayerId { Objects.requireNonNull(value); }
 public static PlayerId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new PlayerId(id); }
 public int compareTo(PlayerId other) { return value.toString().compareTo(other.value.toString()); }
}
