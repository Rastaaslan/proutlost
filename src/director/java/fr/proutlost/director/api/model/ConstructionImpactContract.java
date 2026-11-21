package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ConstructionImpactContract(ResourceId id, Set<TagId> affectedConstructionClasses, Set<ResourceId> excludedCodecs, Footprint extent, ResourceId warningProfile, ReadSet preconditions, PrimitiveId compensation, PlayerEditPolicy playerEditPolicy) {
 public ConstructionImpactContract { Objects.requireNonNull(id); affectedConstructionClasses=set(affectedConstructionClasses); excludedCodecs=set(excludedCodecs); Objects.requireNonNull(extent); Objects.requireNonNull(warningProfile); Objects.requireNonNull(preconditions); Objects.requireNonNull(compensation); Objects.requireNonNull(playerEditPolicy);  }
}
