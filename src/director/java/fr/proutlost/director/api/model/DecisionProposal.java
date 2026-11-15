package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DecisionProposal(DecisionId proposalId, List<Candidate> candidates, Optional<ExecutionPlan> selectedPlan, AuditDecision audit, List<AggregateChange> stateChanges) {
 public DecisionProposal { stateChanges=list(stateChanges); require(stateChanges.stream().map(AggregateChange::key).distinct().count()==stateChanges.size(),"duplicate decision state change"); Objects.requireNonNull(proposalId); candidates=list(candidates); Objects.requireNonNull(selectedPlan); Objects.requireNonNull(audit);  }
}
