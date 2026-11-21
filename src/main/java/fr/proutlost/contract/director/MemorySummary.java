package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record MemorySummary(
    Id world_id,
    SchemaVersion schema_version,
    Id memory_id,
    MemoryKind kind,
    Id subject_id,
    @Nullable Long membership_epoch,
    @Nullable SemanticRegionId semantic_region_id,
    @Nullable MemoryCellKey cell_key,
    Instant window_start,
    Instant updated_at,
    Instant expires_at,
    Map<Id,Long> counts,
    int attention_debt,
    Map<Id,Instant> cooldown_until,
    Map<Id,Instant> last_exposure,
    @Nullable Instant last_actual_play_at,
    List<EvidenceRef> routine_refs,
    List<Id> fact_refs,
    Revision revision,
    Map<Id,StateValue> states) {
    public MemorySummary {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(memory_id, "memory_id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject_id, "subject_id");
        if (membership_epoch != null) Checks.nonnegative(membership_epoch, "membership_epoch");
        Objects.requireNonNull(window_start, "window_start");
        Objects.requireNonNull(updated_at, "updated_at");
        Objects.requireNonNull(expires_at, "expires_at");
        counts = Checks.copy(counts);
        counts.values().forEach(v -> Checks.nonnegative(v, "counts"));
        cooldown_until = Checks.copy(cooldown_until);
        last_exposure = Checks.copy(last_exposure);
        routine_refs = Checks.copy(routine_refs);
        fact_refs = Checks.copy(fact_refs);
        Objects.requireNonNull(revision, "revision");
        states = Checks.copy(states);
        Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "Wrong record schema version");
        Checks.require((kind == MemoryKind.CONTEXT_GROUP) == (membership_epoch != null), "Memory epoch mismatch"); Checks.require((kind == MemoryKind.CELL) == (cell_key != null), "Cell identity mismatch"); Checks.require((kind == MemoryKind.SEMANTIC_REGION) == (semantic_region_id != null), "Region identity mismatch"); Checks.require(attention_debt >= 0 && attention_debt <= Profiles.MAX_DEBT, "Debt cap"); Checks.require(!updated_at.isBefore(window_start) && !expires_at.isBefore(updated_at), "Memory time order");
    }
}
