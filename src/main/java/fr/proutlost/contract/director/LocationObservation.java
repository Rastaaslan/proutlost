package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record LocationObservation(
    Id world_id,
    SchemaVersion schema_version,
    LocationRef location,
    Revision observation_revision,
    Instant observed_at,
    Instant expires_at,
    boolean loaded,
    boolean observable,
    Set<Id> usable_capabilities,
    Set<Id> unavailable_resources,
    Revision protection_revision,
    List<EvidenceRef> conflicts,
    List<EvidenceRef> evidence) {
    public LocationObservation {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(observation_revision, "observation_revision");
        Objects.requireNonNull(observed_at, "observed_at");
        Objects.requireNonNull(expires_at, "expires_at");
        usable_capabilities = Checks.copy(usable_capabilities);
        unavailable_resources = Checks.copy(unavailable_resources);
        Objects.requireNonNull(protection_revision, "protection_revision");
        conflicts = Checks.copy(conflicts);
        evidence = Checks.copy(evidence);
        Checks.require(schema_version.equals(SchemaVersion.BASE), "Wrong record schema version");
        Checks.require(expires_at.isAfter(observed_at), "Observation expiry");
    }
}
