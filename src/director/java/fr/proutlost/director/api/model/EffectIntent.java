package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record EffectIntent(OperationToken operationToken, PrimitiveId primitive, int version, PrimitiveArgs typedArgs, Footprint expectedFootprint, EventInstanceId instanceId) {
 public EffectIntent { Objects.requireNonNull(operationToken); Objects.requireNonNull(primitive); nonnegative(version); Objects.requireNonNull(typedArgs); Objects.requireNonNull(expectedFootprint); Objects.requireNonNull(instanceId);  }
}
