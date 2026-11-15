package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record RoundInput(
    Id world_id,
    Id round_id,
    long logical_time_ms,
    Revision policy_revision,
    Revision tuning_revision,
    Map<Id,Revision> snapshot_revisions,
    Digest seed,
    long draw_index,
    @Nullable Id candidate_cursor) {
    public RoundInput {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(round_id, "round_id");
        Checks.nonnegative(logical_time_ms, "logical_time_ms");
        Objects.requireNonNull(policy_revision, "policy_revision");
        Objects.requireNonNull(tuning_revision, "tuning_revision");
        snapshot_revisions = Checks.copy(snapshot_revisions);
        Objects.requireNonNull(seed, "seed");
        Checks.nonnegative(draw_index, "draw_index");
    }
}
