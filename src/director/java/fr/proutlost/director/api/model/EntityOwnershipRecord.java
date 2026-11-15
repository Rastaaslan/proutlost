package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record EntityOwnershipRecord(AggregateHeader header, OwnerToken ownerToken, UUID entityUuid, EventInstanceId instanceId, CohortId cohortId, String slotId, EntityPhase phase, OwnershipDisposition disposition, ChunkRef lastKnownChunk, Tick lastSeenTick, ArrivalPolicy persistMode, CleanupReason cleanupPolicy, Optional<PlayerId> adoptedBy, Epoch generation) {
 public EntityOwnershipRecord { Objects.requireNonNull(header); Objects.requireNonNull(ownerToken); Objects.requireNonNull(entityUuid); Objects.requireNonNull(instanceId); Objects.requireNonNull(cohortId); text(slotId); Objects.requireNonNull(phase); Objects.requireNonNull(disposition); Objects.requireNonNull(lastKnownChunk); Objects.requireNonNull(lastSeenTick); Objects.requireNonNull(persistMode); Objects.requireNonNull(cleanupPolicy); Objects.requireNonNull(adoptedBy); Objects.requireNonNull(generation);  }
}
