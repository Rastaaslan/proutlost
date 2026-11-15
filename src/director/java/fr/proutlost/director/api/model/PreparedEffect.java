package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PreparedEffect(EffectIntent intent, SnapshotBatch snapshot, List<ResourceClaim> claims, ReadSet preconditions, Map<ResourceKey,Fingerprint> expectedPost, BudgetVector budgetEstimate, List<EffectIntent> inversePlan, Set<CapabilityId> capabilities, EffectBlobs durableBlobs) {
 public PreparedEffect { Objects.requireNonNull(intent); Objects.requireNonNull(snapshot); claims=list(claims); Objects.requireNonNull(preconditions); expectedPost=map(expectedPost); Objects.requireNonNull(budgetEstimate); inversePlan=list(inversePlan); capabilities=set(capabilities); Objects.requireNonNull(durableBlobs);  }
}
