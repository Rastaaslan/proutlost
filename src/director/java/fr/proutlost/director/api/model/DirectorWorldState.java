package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DirectorWorldState(AggregateHeader header, WorldId worldId, Epoch storeGeneration, Tick simTick, RunControl runControl, HealthMode healthMode, PacingState pacingState, GlobalTension tension, ResourceId intensityProfileId, Fingerprint catalogHash, Fingerprint configHash, Epoch bootEpoch, Sequence lastCheckpointSeq, EngineControlState engineControl) {
 public DirectorWorldState { Objects.requireNonNull(engineControl); Objects.requireNonNull(header); Objects.requireNonNull(worldId); Objects.requireNonNull(storeGeneration); Objects.requireNonNull(simTick); Objects.requireNonNull(runControl); Objects.requireNonNull(healthMode); Objects.requireNonNull(pacingState); Objects.requireNonNull(tension); Objects.requireNonNull(intensityProfileId); Objects.requireNonNull(catalogHash); Objects.requireNonNull(configHash); Objects.requireNonNull(bootEpoch); Objects.requireNonNull(lastCheckpointSeq);  }
}
