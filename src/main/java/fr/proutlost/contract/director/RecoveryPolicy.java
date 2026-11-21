package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record RecoveryPolicy(
    List<RecoveryStrategy> strategies,
    List<LocationRef> fallback_refs,
    boolean preserve_fact_ids,
    Id user_cue,
    Id retry_profile) {
    public RecoveryPolicy {
        strategies = Checks.copy(strategies);
        fallback_refs = Checks.copy(fallback_refs);
        Objects.requireNonNull(user_cue, "user_cue");
        Objects.requireNonNull(retry_profile, "retry_profile");
        Checks.require(preserve_fact_ids && !strategies.isEmpty(), "Recovery must preserve facts");
    }
}
