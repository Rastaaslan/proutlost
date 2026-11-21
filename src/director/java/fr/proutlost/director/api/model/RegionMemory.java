package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RegionMemory(AggregateHeader header, RegionId regionId, Map<MemoryComponent,DecayValue> components, Tick lastVisitTick, Tick lastEventTick, Set<ResourceId> persistentStateRefs, Set<FactId> durableFactRefs, RegionActivityState activity) {
 public RegionMemory { Objects.requireNonNull(header); Objects.requireNonNull(regionId); components=map(components); Objects.requireNonNull(lastVisitTick); Objects.requireNonNull(lastEventTick); persistentStateRefs=set(persistentStateRefs); durableFactRefs=set(durableFactRefs); Objects.requireNonNull(activity);  }
}
