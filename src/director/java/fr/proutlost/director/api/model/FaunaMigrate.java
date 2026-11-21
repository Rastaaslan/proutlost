package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record FaunaMigrate(ResourceId species, int cohortSize, TagId corridorTag, ArrivalPolicy arrivalPolicy) implements PrimitiveArgs {
 public FaunaMigrate { Objects.requireNonNull(species); nonnegative(cohortSize); Objects.requireNonNull(corridorTag); Objects.requireNonNull(arrivalPolicy);  }
}
