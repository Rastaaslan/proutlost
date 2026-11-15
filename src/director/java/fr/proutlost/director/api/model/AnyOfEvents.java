package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AnyOfEvents(Set<DefinitionId> eventIds) implements Selector {
 public AnyOfEvents { eventIds=set(eventIds); require(!eventIds.isEmpty(),"selector"); }
}
