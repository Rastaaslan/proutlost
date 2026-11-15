package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record CandidateId(UUID value) implements Comparable<CandidateId> {
 public CandidateId { Objects.requireNonNull(value); }
 public static CandidateId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new CandidateId(id); }
 public int compareTo(CandidateId other) { return value.toString().compareTo(other.value.toString()); }
}
