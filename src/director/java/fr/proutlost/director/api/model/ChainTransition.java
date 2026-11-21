package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ChainTransition(ResourceId id, String from, String to, FactType onFact, Optional<DefinitionId> eventId, Condition condition, boolean consumeFactOnce, Optional<TagId> settleStateTag) {
 public ChainTransition { Objects.requireNonNull(id); text(from); text(to); Objects.requireNonNull(onFact); Objects.requireNonNull(eventId); Objects.requireNonNull(condition); Objects.requireNonNull(settleStateTag); require(consumeFactOnce,"consume fact once"); }
}
