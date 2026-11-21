package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record SystemError(fr.proutlost.director.api.error.ReasonCode code, Severity severity, boolean retriable, Optional<RetryCondition> retryCondition, Set<ScopeRef> scopes, CorrelationId correlationId, SafeState safeState) {
 public SystemError { Objects.requireNonNull(code); Objects.requireNonNull(severity); Objects.requireNonNull(retryCondition); scopes=set(scopes); Objects.requireNonNull(correlationId); Objects.requireNonNull(safeState);  }
}
