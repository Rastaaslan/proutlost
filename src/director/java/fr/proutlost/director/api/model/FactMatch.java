package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record FactMatch(FactType factType, ScopeRef scopeMatch, EvidenceStatus requiredEvidence, Map<String,JsonValue> payloadEquals) implements FactPredicate {
 public FactMatch { Objects.requireNonNull(factType); Objects.requireNonNull(scopeMatch); Objects.requireNonNull(requiredEvidence); payloadEquals=map(payloadEquals); require(payloadEquals.values().stream().noneMatch(v->v instanceof JsonValue.Array || v instanceof JsonValue.ObjectValue),"scalar fact predicate"); require(requiredEvidence==EvidenceStatus.CONFIRMED,"fulfillment requires confirmed fact"); }
}
