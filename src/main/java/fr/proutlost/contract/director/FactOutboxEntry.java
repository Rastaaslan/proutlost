package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record FactOutboxEntry(
    Id world_id,
    SchemaVersion schema_version,
    WorldFact fact,
    long outbox_sequence,
    Id generation_id,
    Map<Id,ConsumerState> consumer_states) {
    public FactOutboxEntry {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(fact, "fact");
        Checks.nonnegative(outbox_sequence, "outbox_sequence");
        Objects.requireNonNull(generation_id, "generation_id");
        consumer_states = Checks.copy(consumer_states);
        Checks.require(schema_version.equals(SchemaVersion.BASE), "Wrong record schema version");
        Checks.require(world_id.equals(fact.world_id()), "Cross-world fact");
    }
}
