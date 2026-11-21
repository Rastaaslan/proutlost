package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DecisionAdminData(DecisionId decisionId, Tick capturedTick, List<Candidate> candidates, Optional<CandidateId> selected, Optional<AiProposalResult> advisor, List<ValidationResult> validations, Optional<Outcome> outcome) implements AdminData {
 public DecisionAdminData { Objects.requireNonNull(decisionId); Objects.requireNonNull(capturedTick); candidates=list(candidates); Objects.requireNonNull(selected); Objects.requireNonNull(advisor); validations=list(validations); Objects.requireNonNull(outcome);  }
}
