package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ResourceClaim(ResourceKey resource, ClaimPolicy policy, BudgetVector cost, Optional<ResourceId> mergeFunction, Epoch expectedEpoch) {
 public ResourceClaim { Objects.requireNonNull(resource); Objects.requireNonNull(policy); Objects.requireNonNull(cost); Objects.requireNonNull(mergeFunction); Objects.requireNonNull(expectedEpoch); require(policy!=ClaimPolicy.MERGE || mergeFunction.isPresent(),"merge function required"); }
}
