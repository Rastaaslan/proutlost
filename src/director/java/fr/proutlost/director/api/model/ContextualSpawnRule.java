package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ContextualSpawnRule(ScopeSet scopes, ResourceId rule, DurationTicks ttl, Revision revision) implements PrimitiveArgs {
 public ContextualSpawnRule { Objects.requireNonNull(scopes); Objects.requireNonNull(rule); Objects.requireNonNull(ttl); Objects.requireNonNull(revision);  }
}
