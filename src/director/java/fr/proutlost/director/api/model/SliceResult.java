package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record SliceResult(Outcome outcome, Set<OperationToken> appliedOperationIds, Map<ResourceKey,Fingerprint> observedHashes, long remainingWork, Set<ResourceKey> releasedClaims, Set<BlockPosition> conflictCells, List<FactEvidence> factEvidence) {
 public SliceResult { Objects.requireNonNull(outcome); appliedOperationIds=set(appliedOperationIds); observedHashes=map(observedHashes); nonnegative(remainingWork); releasedClaims=set(releasedClaims); conflictCells=set(conflictCells); factEvidence=list(factEvidence);  }
}
