package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RecoveryEvidence(ResourceKey resource, EvidenceStatus status, Optional<Fingerprint> observedHash, Optional<OwnerToken> ownerToken, Epoch ownerEpoch, Epoch playerOverrideEpoch, boolean localAbsenceProven) {
 public RecoveryEvidence { Objects.requireNonNull(resource); Objects.requireNonNull(status); Objects.requireNonNull(observedHash); Objects.requireNonNull(ownerToken); Objects.requireNonNull(ownerEpoch); Objects.requireNonNull(playerOverrideEpoch);  }
}
