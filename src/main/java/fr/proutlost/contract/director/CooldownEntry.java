package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record CooldownEntry(
    Id world_id,
    SchemaVersion schema_version,
    Id id,
    long revision,
    Id subject_kind,
    Id subject_id,
    @Nullable Long membership_epoch,
    Id category_id,
    long until_logical_ms,
    Id last_exposure_id,
    Set<Id> application_keys) {
    public CooldownEntry {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(id, "id");
        Checks.nonnegative(revision, "revision");
        Objects.requireNonNull(subject_kind, "subject_kind");
        Objects.requireNonNull(subject_id, "subject_id");
        if (membership_epoch != null) Checks.nonnegative(membership_epoch, "membership_epoch");
        Objects.requireNonNull(category_id, "category_id");
        Checks.nonnegative(until_logical_ms, "until_logical_ms");
        Objects.requireNonNull(last_exposure_id, "last_exposure_id");
        application_keys = Checks.copy(application_keys);
        Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "Wrong record schema version");
    }
}
