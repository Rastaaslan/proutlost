package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record SelectionDecision(
    Revision policy_revision,
    Digest seed,
    long draw_index,
    SelectionOutcome outcome,
    Set<Id> reason_codes,
    List<CandidateScore> candidate_scores,
    @Nullable Digest tie_break_key,
    Instant evaluated_at) {
    public SelectionDecision {
        Objects.requireNonNull(policy_revision, "policy_revision");
        Objects.requireNonNull(seed, "seed");
        Checks.nonnegative(draw_index, "draw_index");
        Objects.requireNonNull(outcome, "outcome");
        reason_codes = Checks.copy(reason_codes);
        candidate_scores = Checks.copy(candidate_scores);
        Objects.requireNonNull(evaluated_at, "evaluated_at");
    }
}
