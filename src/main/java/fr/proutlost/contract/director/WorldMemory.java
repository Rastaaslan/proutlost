package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record WorldMemory(
    Id world_id,
    SchemaVersion schema_version,
    Id id,
    long revision,
    Id build_id,
    Revision catalog_revision,
    Digest catalog_digest,
    long store_sequence,
    Id server_epoch,
    Id backup_generation,
    long scheduler_round,
    long next_draw_index,
    long logical_time_ms,
    @Nullable Id candidate_cursor,
    Admission admission,
    Map<Id,Long> budget_ledger,
    Map<Id,StateValue> states,
    Set<Id> retired_schedule_ids) {
    public WorldMemory {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(id, "id");
        Checks.nonnegative(revision, "revision");
        Objects.requireNonNull(build_id, "build_id");
        Objects.requireNonNull(catalog_revision, "catalog_revision");
        Objects.requireNonNull(catalog_digest, "catalog_digest");
        Checks.nonnegative(store_sequence, "store_sequence");
        Objects.requireNonNull(server_epoch, "server_epoch");
        Objects.requireNonNull(backup_generation, "backup_generation");
        Checks.nonnegative(scheduler_round, "scheduler_round");
        Checks.nonnegative(next_draw_index, "next_draw_index");
        Checks.nonnegative(logical_time_ms, "logical_time_ms");
        Objects.requireNonNull(admission, "admission");
        budget_ledger = Checks.copy(budget_ledger);
        budget_ledger.values().forEach(v -> Checks.nonnegative(v, "budget_ledger"));
        states = Checks.copy(states);
        retired_schedule_ids = Checks.copy(retired_schedule_ids);
        Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "Wrong record schema version");
    }
}
