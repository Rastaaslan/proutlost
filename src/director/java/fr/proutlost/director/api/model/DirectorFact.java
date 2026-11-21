package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DirectorFact(FactId factId, FactType factType, List<ScopeRef> subjectRefs, Optional<EventInstanceId> eventId, EvidenceStatus evidenceStatus, Map<String,JsonValue> payload, boolean durable, Optional<FactId> supersedesFactId) {
 public DirectorFact { Objects.requireNonNull(factId); Objects.requireNonNull(factType); subjectRefs=list(subjectRefs); Objects.requireNonNull(eventId); Objects.requireNonNull(evidenceStatus); payload=map(payload); Objects.requireNonNull(supersedesFactId); require(!subjectRefs.isEmpty() && evidenceStatus!=EvidenceStatus.UNKNOWN,"fact evidence"); }
}
