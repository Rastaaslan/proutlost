package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record LocationMemory(
    Id world_id,
    SchemaVersion schema_version,
    Id id,
    long revision,
    LocationRef location,
    Map<Id,StateValue> states,
    @Nullable Revision physical_revision,
    @Nullable Digest physical_digest,
    Availability availability,
    @Nullable Id observation_ref,
    Id summary_ref,
    Set<Id> mutation_receipt_ids,
    Set<Id> arc_binding_ids,
    Set<Id> constraint_ids) {
    public LocationMemory {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(id, "id");
        Checks.nonnegative(revision, "revision");
        Objects.requireNonNull(location, "location");
        states = Checks.copy(states);
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(summary_ref, "summary_ref");
        mutation_receipt_ids = Checks.copy(mutation_receipt_ids);
        arc_binding_ids = Checks.copy(arc_binding_ids);
        constraint_ids = Checks.copy(constraint_ids);
        Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "Wrong record schema version");
        Checks.require((physical_revision == null) == (physical_digest == null), "Incomplete physical identity");
    }
}
