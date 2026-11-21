package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RetryCondition(fr.proutlost.director.api.error.ReasonCode reason, Optional<Tick> notBefore, Set<ScopeRef> invalidatedScopes) {
 public RetryCondition { Objects.requireNonNull(reason); Objects.requireNonNull(notBefore); invalidatedScopes=set(invalidatedScopes);  }
}
