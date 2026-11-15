package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PlayerOperationalMemory(AggregateHeader header, PlayerId playerId, DurationTicks eligibleOnlineTicks, Optional<RegionId> lastRegionId, Optional<Tick> lastTargetTick, List<Exposure> eventExposureRing, List<Exposure> roleExposureRing, Set<ResourceId> cooldownRefs, UnitValue attentionDebt, Map<MemoryComponent,DecayValue> recentActivity, Optional<Tick> recentDeathUntil, Epoch transitionEpoch) {
 public PlayerOperationalMemory { Objects.requireNonNull(header); Objects.requireNonNull(playerId); Objects.requireNonNull(eligibleOnlineTicks); Objects.requireNonNull(lastRegionId); Objects.requireNonNull(lastTargetTick); eventExposureRing=list(eventExposureRing); roleExposureRing=list(roleExposureRing); cooldownRefs=set(cooldownRefs); Objects.requireNonNull(attentionDebt); recentActivity=map(recentActivity); Objects.requireNonNull(recentDeathUntil); Objects.requireNonNull(transitionEpoch);  }
}
