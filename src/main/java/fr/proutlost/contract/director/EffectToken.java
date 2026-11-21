package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record EffectToken(
    Id ownership_id,
    Id world_id,
    Id backup_generation,
    Id server_epoch,
    Id event_instance_id,
    Id effect_key,
    @Nullable UUID entity_uuid,
    long last_sequence) {
    public EffectToken {
        Objects.requireNonNull(ownership_id, "ownership_id");
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(backup_generation, "backup_generation");
        Objects.requireNonNull(server_epoch, "server_epoch");
        Objects.requireNonNull(event_instance_id, "event_instance_id");
        Objects.requireNonNull(effect_key, "effect_key");
        Checks.nonnegative(last_sequence, "last_sequence");
    }
}
