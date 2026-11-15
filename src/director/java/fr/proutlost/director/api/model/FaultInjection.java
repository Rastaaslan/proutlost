package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record FaultInjection(FaultPoint point, long occurrence, OperationToken operationToken, Optional<ResourceKey> resource, boolean failClosed) {
 public FaultInjection { Objects.requireNonNull(point); nonnegative(occurrence); Objects.requireNonNull(operationToken); Objects.requireNonNull(resource);  }
}
