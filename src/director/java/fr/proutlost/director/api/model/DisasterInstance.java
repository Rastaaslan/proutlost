package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DisasterInstance(AggregateHeader header, DisasterId disasterId, EventInstanceId eventInstanceId, DefinitionId recipeId, DisasterStage stage, Map<ResourceId,UnitValue> riskFactors, UnitValue riskScore, Footprint envelope, WarningEvidence warningEvidence, Set<PlayerId> affectedAudience, List<RouteSegment> evacuationRoutes, BlobRef propagationMaskRef, Set<MutationId> mutationRefs, String stabilizationState, Set<FactId> durableConsequenceRefs) {
 public DisasterInstance { Objects.requireNonNull(header); Objects.requireNonNull(disasterId); Objects.requireNonNull(eventInstanceId); Objects.requireNonNull(recipeId); Objects.requireNonNull(stage); riskFactors=map(riskFactors); Objects.requireNonNull(riskScore); Objects.requireNonNull(envelope); Objects.requireNonNull(warningEvidence); affectedAudience=set(affectedAudience); evacuationRoutes=list(evacuationRoutes); Objects.requireNonNull(propagationMaskRef); mutationRefs=set(mutationRefs); text(stabilizationState); durableConsequenceRefs=set(durableConsequenceRefs);  }
}
