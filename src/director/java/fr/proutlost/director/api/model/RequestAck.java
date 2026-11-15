package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RequestAck(RequestId requestId, Revision revision, RequestStatus state, List<fr.proutlost.director.api.error.ReasonCode> reasons, Set<FactId> factRefs) {
 public RequestAck { Objects.requireNonNull(requestId); Objects.requireNonNull(revision); Objects.requireNonNull(state); reasons=list(reasons); factRefs=set(factRefs);  }
}
