package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record MutationRequest(
    Id world_id,
    SchemaVersion schema_version,
    Id mutation_id,
    long revision,
    Id event_instance_id,
    DefinitionRef definition,
    Id operation_id,
    Revision operation_revision,
    List<LocationRef> location_refs,
    List<Bounds> bounded_footprint,
    Revision expected_physical_revision,
    Digest expected_before_digest,
    Revision protection_revision,
    Set<Id> constraint_refs,
    List<StateRevision> expected_state_revisions,
    long lease_fence,
    Id server_epoch,
    Id idempotency_key,
    List<StatePredicate> requested_logical_transition,
    Id recovery_policy_id,
    List<StatePredicate> requires_state,
    List<StatePredicate> forbids_state,
    @Nullable StatePredicate locks_location_until) {
    public MutationRequest {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(mutation_id, "mutation_id");
        Checks.nonnegative(revision, "revision");
        Objects.requireNonNull(event_instance_id, "event_instance_id");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(operation_id, "operation_id");
        Objects.requireNonNull(operation_revision, "operation_revision");
        location_refs = Checks.copy(location_refs);
        bounded_footprint = Checks.copy(bounded_footprint);
        Objects.requireNonNull(expected_physical_revision, "expected_physical_revision");
        Objects.requireNonNull(expected_before_digest, "expected_before_digest");
        Objects.requireNonNull(protection_revision, "protection_revision");
        constraint_refs = Checks.copy(constraint_refs);
        expected_state_revisions = Checks.copy(expected_state_revisions);
        Checks.nonnegative(lease_fence, "lease_fence");
        Objects.requireNonNull(server_epoch, "server_epoch");
        Objects.requireNonNull(idempotency_key, "idempotency_key");
        requested_logical_transition = Checks.copy(requested_logical_transition);
        Objects.requireNonNull(recovery_policy_id, "recovery_policy_id");
        requires_state = Checks.copy(requires_state);
        forbids_state = Checks.copy(forbids_state);
        Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "Wrong record schema version");
        var addresses = new java.util.HashSet<StateAddress>();
        for (var expected : expected_state_revisions) Checks.require(addresses.add(expected.address()), "Duplicate state revision guard");
        for (var predicate : requires_state) Checks.require(addresses.contains(predicate.address()), "Missing requires_state revision guard");
        for (var predicate : forbids_state) Checks.require(addresses.contains(predicate.address()), "Missing forbids_state revision guard");
        if (locks_location_until != null) Checks.require(addresses.contains(locks_location_until.address()), "Missing until revision guard");
        Checks.require(!location_refs.isEmpty() && !bounded_footprint.isEmpty(), "Missing bounded mutation footprint"); Checks.require(Checks.volume(bounded_footprint) <= Limits.MAX_MUTATION_CELLS, "Mutation footprint cap");
    }
}
