package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Conflicts(Set<String> claims, Set<TagId> compatibilityTags, Set<DefinitionId> allowMergeWith, Priority priority, Preemption preemption) {
 public Conflicts { claims=set(claims); compatibilityTags=set(compatibilityTags); allowMergeWith=set(allowMergeWith); Objects.requireNonNull(priority); Objects.requireNonNull(preemption);  }
}
