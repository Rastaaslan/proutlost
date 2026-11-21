package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record Lease(
    Id world_id,
    SchemaVersion schema_version,
    Id lease_id,
    Id owner_event_instance_id,
    Id assignment_id,
    List<ResourceRequest> resources,
    long fence,
    Id server_epoch,
    Instant acquired_at,
    Instant expires_at,
    long monotonic_expiry_ms,
    long ledger_revision,
    LeaseStatus status) {
    public Lease {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(lease_id, "lease_id");
        Objects.requireNonNull(owner_event_instance_id, "owner_event_instance_id");
        Objects.requireNonNull(assignment_id, "assignment_id");
        resources = Checks.copy(resources);
        Checks.nonnegative(fence, "fence");
        Objects.requireNonNull(server_epoch, "server_epoch");
        Objects.requireNonNull(acquired_at, "acquired_at");
        Objects.requireNonNull(expires_at, "expires_at");
        Checks.nonnegative(monotonic_expiry_ms, "monotonic_expiry_ms");
        Checks.nonnegative(ledger_revision, "ledger_revision");
        Objects.requireNonNull(status, "status");
        Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "Wrong record schema version");
        Checks.require(!resources.isEmpty() && expires_at.isAfter(acquired_at), "Invalid lease");
    }
}
