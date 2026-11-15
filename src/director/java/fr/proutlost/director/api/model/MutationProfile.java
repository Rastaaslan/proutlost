package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record MutationProfile(ResourceId recipeId, int version, Set<PrimitiveId> allowedPrimitives, Set<ResourceId> affectedCodecs, ResourceId footprintResolver, ReadSet preconditions, ResourceId propagationMaskStrategy, BudgetVector maximumWork, int neighborRadius, List<RouteSegment> safeExits, PlayerEditPolicy ownershipPolicy, Optional<ConstructionImpactContract> constructionImpact, PrimitiveId inversePlan, Set<NoninvertibleEffect> noninvertibleEffects, RecoveryMode recovery, Set<String> requiredGameTests) {
 public MutationProfile { Objects.requireNonNull(recipeId); nonnegative(version); allowedPrimitives=set(allowedPrimitives); affectedCodecs=set(affectedCodecs); Objects.requireNonNull(footprintResolver); Objects.requireNonNull(preconditions); Objects.requireNonNull(propagationMaskStrategy); Objects.requireNonNull(maximumWork); nonnegative(neighborRadius); safeExits=list(safeExits); Objects.requireNonNull(ownershipPolicy); Objects.requireNonNull(constructionImpact); Objects.requireNonNull(inversePlan); noninvertibleEffects=set(noninvertibleEffects); Objects.requireNonNull(recovery); requiredGameTests=set(requiredGameTests);  }
}
