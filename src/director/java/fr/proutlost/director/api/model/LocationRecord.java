package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record LocationRecord(AggregateHeader header, LocationId locationId, ResourceId sourceNamespace, String externalId, DimensionId dimension, Aabb bounds, Set<TagId> tags, Set<AnchorId> anchorIds, Revision importRevision, boolean active) {
 public LocationRecord { Objects.requireNonNull(header); Objects.requireNonNull(locationId); Objects.requireNonNull(sourceNamespace); text(externalId); Objects.requireNonNull(dimension); Objects.requireNonNull(bounds); tags=set(tags); anchorIds=set(anchorIds); Objects.requireNonNull(importRevision);  }
}
