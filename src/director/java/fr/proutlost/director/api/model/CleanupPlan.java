package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record CleanupPlan(List<EffectIntent> operations, Set<ResourceKey> yieldedResources, List<SystemError> errors) {
 public CleanupPlan { operations=list(operations); yieldedResources=set(yieldedResources); errors=list(errors);  }
}
