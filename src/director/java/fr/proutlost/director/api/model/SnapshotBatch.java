package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record SnapshotBatch(RequestId requestId, ScopeSet scopes, Tick capturedTick, Map<ScopeRef,Revision> scopeRevisions, ObservationContext context, Set<SensorKind> missingData, Set<ChunkRef> loadedChunks, HealthMode serverHealth) {
 public SnapshotBatch { Objects.requireNonNull(requestId); Objects.requireNonNull(scopes); Objects.requireNonNull(capturedTick); scopeRevisions=map(scopeRevisions); Objects.requireNonNull(context); missingData=set(missingData); loadedChunks=set(loadedChunks); Objects.requireNonNull(serverHealth);  }
}
