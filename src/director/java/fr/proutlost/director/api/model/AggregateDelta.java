package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AggregateDelta(ScopeRef scope, Revision beforeRevision, Revision afterRevision, Map<MemoryComponent,DecayValue> changes) {
 public AggregateDelta { Objects.requireNonNull(scope); Objects.requireNonNull(beforeRevision); Objects.requireNonNull(afterRevision); changes=map(changes);  }
}
