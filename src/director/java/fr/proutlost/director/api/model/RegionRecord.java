package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RegionRecord(AggregateHeader header, RegionId regionId, DimensionId dimension, RegionStatus status, SupportShape supportShape, Set<AnchorId> anchorIds, UnitValue importance, Set<RegionId> parentIds, Set<RegionId> aliases, Revision membershipRevision, RegionPersonality personality, Set<MemoryId> memoryRefs) {
 public RegionRecord { Objects.requireNonNull(header); Objects.requireNonNull(regionId); Objects.requireNonNull(dimension); Objects.requireNonNull(status); Objects.requireNonNull(supportShape); anchorIds=set(anchorIds); Objects.requireNonNull(importance); parentIds=set(parentIds); aliases=set(aliases); Objects.requireNonNull(membershipRevision); Objects.requireNonNull(personality); memoryRefs=set(memoryRefs);  }
}
