package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record OwnershipRecord(AggregateHeader header, ResourceKey resourceKey, Set<OwnerToken> ownerTokens, Epoch epoch, OwnershipDisposition disposition, MutationId creationMutationId, Fingerprint expectedPostHash, Epoch playerOverrideEpoch, Optional<ResourceId> exceptionContractId) {
 public OwnershipRecord { Objects.requireNonNull(header); Objects.requireNonNull(resourceKey); ownerTokens=set(ownerTokens); Objects.requireNonNull(epoch); Objects.requireNonNull(disposition); Objects.requireNonNull(creationMutationId); Objects.requireNonNull(expectedPostHash); Objects.requireNonNull(playerOverrideEpoch); Objects.requireNonNull(exceptionContractId);  }
}
