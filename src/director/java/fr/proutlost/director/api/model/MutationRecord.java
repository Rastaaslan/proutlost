package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record MutationRecord(AggregateHeader header, MutationId mutationId, EventInstanceId instanceId, SliceId sliceId, MutationState state, PrimitiveId primitiveId, int primitiveVersion, Footprint footprint, BlobRef preStateRef, BlobRef postStateRef, OwnerToken ownerToken, Epoch ownerEpoch, ReadSet readSetVersions, Set<NoninvertibleEffect> noninvertibleEffects, List<RecoveryEvidence> reconciliationEvidence, Optional<MutationId> compensationRef, Sequence durableSeq) {
 public MutationRecord { Objects.requireNonNull(header); Objects.requireNonNull(mutationId); Objects.requireNonNull(instanceId); Objects.requireNonNull(sliceId); Objects.requireNonNull(state); Objects.requireNonNull(primitiveId); nonnegative(primitiveVersion); Objects.requireNonNull(footprint); Objects.requireNonNull(preStateRef); Objects.requireNonNull(postStateRef); Objects.requireNonNull(ownerToken); Objects.requireNonNull(ownerEpoch); Objects.requireNonNull(readSetVersions); noninvertibleEffects=set(noninvertibleEffects); reconciliationEvidence=list(reconciliationEvidence); Objects.requireNonNull(compensationRef); Objects.requireNonNull(durableSeq);  }
}
