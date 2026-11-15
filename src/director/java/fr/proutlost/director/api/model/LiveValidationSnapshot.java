package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record LiveValidationSnapshot(ReadSet readSet, Set<ChunkRef> loadedChunks, HealthMode health, Tick now, Epoch reservationGeneration) {
 public LiveValidationSnapshot { Objects.requireNonNull(readSet); loadedChunks=set(loadedChunks); Objects.requireNonNull(health); Objects.requireNonNull(now); Objects.requireNonNull(reservationGeneration);  }
}
