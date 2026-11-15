package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record Observation(
    Id world_id,
    SchemaVersion schema_version,
    Id observation_id,
    Id source_system,
    Id observation_type,
    Id validator_id,
    Set<UUID> player_ids,
    @Nullable AudienceScope audience,
    @Nullable SemanticRegionId semantic_region_id,
    @Nullable LocationRef location,
    Instant observed_at,
    long sequence,
    TypedValue payload,
    List<EvidenceRef> evidence) {
    public Observation {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(observation_id, "observation_id");
        Objects.requireNonNull(source_system, "source_system");
        Objects.requireNonNull(observation_type, "observation_type");
        Objects.requireNonNull(validator_id, "validator_id");
        player_ids = Checks.copy(player_ids);
        Objects.requireNonNull(observed_at, "observed_at");
        Checks.nonnegative(sequence, "sequence");
        Objects.requireNonNull(payload, "payload");
        evidence = Checks.copy(evidence);
        Checks.require(schema_version.equals(SchemaVersion.BASE), "Wrong record schema version");
    }
}
