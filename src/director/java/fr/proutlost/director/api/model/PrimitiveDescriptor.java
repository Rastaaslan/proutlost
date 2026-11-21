package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PrimitiveDescriptor(PrimitiveId id, int version, ResourceId argumentCodec, Set<CapabilityId> capabilities, BudgetVector minimumCost, ResourceId footprintResolver, Set<ResourceId> capturableCodecs, Set<OwnershipDomain> ownershipDomains, Set<NoninvertibleEffect> noninvertibleEffects, DurationTicks maximumDuration, PausePolicy pausePolicy, RecoveryMode recovery, ResourceId cleanup, Optional<PrimitiveId> inverse, Optional<ResourceId> merge, ProviderMode mode) {
 public PrimitiveDescriptor { Objects.requireNonNull(id); nonnegative(version); Objects.requireNonNull(argumentCodec); capabilities=set(capabilities); Objects.requireNonNull(minimumCost); Objects.requireNonNull(footprintResolver); capturableCodecs=set(capturableCodecs); ownershipDomains=set(ownershipDomains); noninvertibleEffects=set(noninvertibleEffects); Objects.requireNonNull(maximumDuration); Objects.requireNonNull(pausePolicy); Objects.requireNonNull(recovery); Objects.requireNonNull(cleanup); Objects.requireNonNull(inverse); Objects.requireNonNull(merge); Objects.requireNonNull(mode); require(version>0,"primitive version"); }
}
