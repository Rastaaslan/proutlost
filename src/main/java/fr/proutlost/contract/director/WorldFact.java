package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record WorldFact(
    Id world_id,
    SchemaVersion schema_version,
    Id fact_id,
    Id fact_type,
    Id source_system,
    FactPlayerScope player_scope,
    FactGroupScope group_scope,
    @Nullable SemanticRegionId region_id,
    @Nullable Id location_id,
    Instant timestamp,
    FactPayload payload,
    Persistence persistence,
    Visibility narrative_visibility) {
    public WorldFact {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(fact_id, "fact_id");
        Objects.requireNonNull(fact_type, "fact_type");
        Objects.requireNonNull(source_system, "source_system");
        Objects.requireNonNull(player_scope, "player_scope");
        Objects.requireNonNull(group_scope, "group_scope");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(narrative_visibility, "narrative_visibility");
        Checks.require(schema_version.equals(SchemaVersion.BASE), "Wrong record schema version");
    }
}
