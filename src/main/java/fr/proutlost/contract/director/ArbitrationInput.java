package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record ArbitrationInput(
    Id candidate_id,
    Id need_id,
    Revision definition_revision,
    AudienceScope audience,
    LocationRef location,
    long attention_debt,
    long first_eligible_round,
    int site_preference_score,
    Digest tie_break_key) {
    public ArbitrationInput {
        Objects.requireNonNull(candidate_id, "candidate_id");
        Objects.requireNonNull(need_id, "need_id");
        Objects.requireNonNull(definition_revision, "definition_revision");
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(location, "location");
        Checks.nonnegative(attention_debt, "attention_debt");
        Checks.require(attention_debt <= Profiles.MAX_DEBT, "Attention debt cap");
        Checks.nonnegative(first_eligible_round, "first_eligible_round");
        Objects.requireNonNull(tie_break_key, "tie_break_key");
    }
}
