package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record BackupGeneration(
    Id world_id,
    SchemaVersion schema_version,
    Id generation_id,
    @Nullable Id parent_generation_id,
    Id build_id,
    Digest catalog_digest,
    Instant created_at,
    long checkpoint_sequence,
    Digest journal_digest,
    Map<Id,Digest> component_digests,
    Id restore_epoch,
    Map<Id,Long> consumer_cursors,
    BackupState state) {
    public BackupGeneration {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(generation_id, "generation_id");
        Objects.requireNonNull(build_id, "build_id");
        Objects.requireNonNull(catalog_digest, "catalog_digest");
        Objects.requireNonNull(created_at, "created_at");
        Checks.nonnegative(checkpoint_sequence, "checkpoint_sequence");
        Objects.requireNonNull(journal_digest, "journal_digest");
        component_digests = Checks.copy(component_digests);
        Objects.requireNonNull(restore_epoch, "restore_epoch");
        consumer_cursors = Checks.copy(consumer_cursors);
        consumer_cursors.values().forEach(v -> Checks.nonnegative(v, "consumer_cursors"));
        Objects.requireNonNull(state, "state");
        Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "Wrong record schema version");
    }
}
