package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Completion(FactType factType, Optional<TagId> durableTrace, UnitValue persistenceProbability) {
 public Completion { Objects.requireNonNull(factType); Objects.requireNonNull(durableTrace); Objects.requireNonNull(persistenceProbability);  }
}
