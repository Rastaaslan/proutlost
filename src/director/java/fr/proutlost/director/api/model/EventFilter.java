package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record EventFilter(Optional<EventInstanceId> instanceId, Set<EventState> states) implements AdminFilter {
 public EventFilter { Objects.requireNonNull(instanceId); states=set(states);  }
}
