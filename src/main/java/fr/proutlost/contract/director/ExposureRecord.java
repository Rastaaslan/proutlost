package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record ExposureRecord(
    Id world_id,
    SchemaVersion schema_version,
    Id id,
    long revision,
    Id event_instance_id,
    UUID player_uuid,
    Id effect_key,
    List<LocationRef> location_refs,
    long observed_logical_ms,
    EvidenceRef evidence_ref,
    Id accounting_key) {
    public ExposureRecord {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(id, "id");
        Checks.nonnegative(revision, "revision");
        Objects.requireNonNull(event_instance_id, "event_instance_id");
        Objects.requireNonNull(player_uuid, "player_uuid");
        Objects.requireNonNull(effect_key, "effect_key");
        location_refs = Checks.copy(location_refs);
        Checks.nonnegative(observed_logical_ms, "observed_logical_ms");
        Objects.requireNonNull(evidence_ref, "evidence_ref");
        Objects.requireNonNull(accounting_key, "accounting_key");
        Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "Wrong record schema version");
    }
}
