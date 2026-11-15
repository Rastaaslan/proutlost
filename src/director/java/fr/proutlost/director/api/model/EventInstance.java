package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record EventInstance(AggregateHeader header, EventInstanceId instanceId, DefinitionRef definition, Epoch catalogGeneration, EventState state, String phase, RoleAssignments roleAssignments, Set<RegionId> regionIds, BlobRef contextRef, Map<String,StepState> stepStates, Set<ReservationId> reservationRefs, Set<EffectId> effectRefs, Set<MutationId> mutationRefs, Optional<ChainId> chainId, Set<RequestId> requirementIds, Optional<Tick> startedTick, DurationTicks elapsedActiveTicks, DurationTicks maxDurationTicks, RecoveryMode recoveryPolicy, Optional<Outcome> outcome) {
 public EventInstance { Objects.requireNonNull(header); Objects.requireNonNull(instanceId); Objects.requireNonNull(definition); Objects.requireNonNull(catalogGeneration); Objects.requireNonNull(state); text(phase); Objects.requireNonNull(roleAssignments); regionIds=set(regionIds); Objects.requireNonNull(contextRef); stepStates=map(stepStates); reservationRefs=set(reservationRefs); effectRefs=set(effectRefs); mutationRefs=set(mutationRefs); Objects.requireNonNull(chainId); requirementIds=set(requirementIds); Objects.requireNonNull(startedTick); Objects.requireNonNull(elapsedActiveTicks); Objects.requireNonNull(maxDurationTicks); Objects.requireNonNull(recoveryPolicy); Objects.requireNonNull(outcome);  }
}
