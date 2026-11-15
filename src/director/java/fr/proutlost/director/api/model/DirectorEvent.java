package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DirectorEvent(EventInstanceId instanceId, EventState transition, Optional<EventOutcome> outcome, Set<ScopeRef> scopes) {
 public DirectorEvent { Objects.requireNonNull(instanceId); Objects.requireNonNull(transition); Objects.requireNonNull(outcome); scopes=set(scopes); require(!scopes.isEmpty(),"event scopes"); }
}
