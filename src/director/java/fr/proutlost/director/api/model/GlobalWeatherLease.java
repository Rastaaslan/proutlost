package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record GlobalWeatherLease(DimensionId dimension, ResourceId weather, DurationTicks duration, Revision externalRevision) implements PrimitiveArgs {
 public GlobalWeatherLease { Objects.requireNonNull(dimension); Objects.requireNonNull(weather); Objects.requireNonNull(duration); Objects.requireNonNull(externalRevision);  }
}
