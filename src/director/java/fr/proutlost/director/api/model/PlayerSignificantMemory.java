package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PlayerSignificantMemory(AggregateHeader header, MemoryId memoryId, PlayerId playerId, EventInstanceId eventId, DefinitionId eventDefinitionId, String role, PerceptionStatus perceptionStatus, BlobRef outcomeRef, Optional<RegionId> regionId, Optional<LocationId> locationId, Set<FactId> consequenceRefs, Set<ObservableReaction> observableReactionCodes, Tick recordedTick) {
 public PlayerSignificantMemory { Objects.requireNonNull(header); Objects.requireNonNull(memoryId); Objects.requireNonNull(playerId); Objects.requireNonNull(eventId); Objects.requireNonNull(eventDefinitionId); text(role); Objects.requireNonNull(perceptionStatus); Objects.requireNonNull(outcomeRef); Objects.requireNonNull(regionId); Objects.requireNonNull(locationId); consequenceRefs=set(consequenceRefs); observableReactionCodes=set(observableReactionCodes); Objects.requireNonNull(recordedTick);  }
}
