package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AnchorRecord(AggregateHeader header, AnchorId anchorId, Optional<LocationId> locationId, Set<RegionId> regionIds, DimensionId dimension, BlockPosition position, String radiusProfile, Set<TagId> tags, AnchorOrigin origin, EvidenceStatus evidence, boolean active) {
 public AnchorRecord { Objects.requireNonNull(header); Objects.requireNonNull(anchorId); Objects.requireNonNull(locationId); regionIds=set(regionIds); Objects.requireNonNull(dimension); Objects.requireNonNull(position); text(radiusProfile); tags=set(tags); Objects.requireNonNull(origin); Objects.requireNonNull(evidence);  }
}
