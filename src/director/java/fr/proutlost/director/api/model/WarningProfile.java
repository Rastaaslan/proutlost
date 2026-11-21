package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record WarningProfile(ResourceId id, Set<ResourceId> signs, ResourceId audienceResolver, Set<ResourceId> visibilityConstraints, DurationTicks leadTime, Set<ResourceId> perimeterMarkers, ResourceId newArrivalHandling, ResourceId cancellationSignal, ResourceId proofCodec) {
 public WarningProfile { Objects.requireNonNull(id); signs=set(signs); Objects.requireNonNull(audienceResolver); visibilityConstraints=set(visibilityConstraints); Objects.requireNonNull(leadTime); perimeterMarkers=set(perimeterMarkers); Objects.requireNonNull(newArrivalHandling); Objects.requireNonNull(cancellationSignal); Objects.requireNonNull(proofCodec);  }
}
