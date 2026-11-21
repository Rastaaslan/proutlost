package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record HistoryEntry(
    Id world_id,
    SchemaVersion schema_version,
    Id id,
    long revision,
    Id event_instance_id,
    @Nullable Id result_key,
    @Nullable Id exposure_id,
    List<StateAddress> subjects,
    Id outcome,
    long logical_time_ms,
    Instant timestamp,
    List<EvidenceRef> evidence_refs,
    Set<Id> fact_ids,
    Set<Id> accounting_keys) {
    public HistoryEntry {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(id, "id");
        Checks.nonnegative(revision, "revision");
        Objects.requireNonNull(event_instance_id, "event_instance_id");
        subjects = Checks.copy(subjects);
        Objects.requireNonNull(outcome, "outcome");
        Checks.nonnegative(logical_time_ms, "logical_time_ms");
        Objects.requireNonNull(timestamp, "timestamp");
        evidence_refs = Checks.copy(evidence_refs);
        fact_ids = Checks.copy(fact_ids);
        accounting_keys = Checks.copy(accounting_keys);
        Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "Wrong record schema version");
    }
}
