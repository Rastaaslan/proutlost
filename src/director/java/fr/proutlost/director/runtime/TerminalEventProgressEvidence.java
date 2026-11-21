package fr.proutlost.director.runtime;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Exact immutable memo key for an already-terminal EVENT's pure metadata
 * reconciliation. This grants no admission, receipt, write or replay authority.
 * New durable evidence must fail matches(), even at the same collection size. */
public record TerminalEventProgressEvidence(
        EventInstance head,EventDefinition definition,ExecutionPlan plan,
        Map<TransactionId,PrepareRecord> prepares,Map<OperationToken,ResultRecord> results,
        Set<MutationId> mutations,Set<RequestId> requirements,DisasterInstance disaster,
        Set<ReservationId> uniqueReservations) {
    public TerminalEventProgressEvidence {
        Objects.requireNonNull(head);Objects.requireNonNull(definition);Objects.requireNonNull(plan);
        if(!Set.of(EventState.SUCCEEDED,EventState.PARTIAL,EventState.ABORTED_SAFE,EventState.FAILED_SAFE).contains(head.state()))
            throw new IllegalArgumentException("terminal EVENT evidence only");
        prepares=Map.copyOf(prepares);results=Map.copyOf(results);mutations=Set.copyOf(mutations);
        requirements=Set.copyOf(requirements);uniqueReservations=Set.copyOf(uniqueReservations);
    }
    public boolean matches(EventInstance current,EventDefinition currentDefinition,ExecutionPlan currentPlan,
            Map<TransactionId,PrepareRecord> currentPrepares,Map<OperationToken,ResultRecord> currentResults,
            Set<MutationId> currentMutations,Set<RequestId> currentRequirements,DisasterInstance currentDisaster,
            Set<ReservationId> currentReservations) {
        return head.equals(current)&&definition.equals(currentDefinition)&&plan.equals(currentPlan)
            &&prepares.equals(currentPrepares)&&results.equals(currentResults)&&mutations.equals(currentMutations)
            &&requirements.equals(currentRequirements)&&Objects.equals(disaster,currentDisaster)
            &&uniqueReservations.equals(currentReservations);
    }
}
