package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record EventInstance(
    Id world_id,
    SchemaVersion schema_version,
    Id event_instance_id,
    @Nullable Id assignment_id,
    DefinitionRef definition,
    @Nullable OpportunityRef opportunity,
    Revision catalog_revision,
    List<LocationRef> location_refs,
    Revision tuning_revision,
    AudienceScope audience,
    LifecycleState state,
    long state_revision,
    @Nullable Outcome requested_outcome,
    CleanupStatus cleanup_status,
    Instant created_at,
    Instant updated_at,
    Instant deadline,
    Id backup_generation,
    Id server_epoch,
    Digest seed,
    long next_draw_index,
    Set<Id> lease_ids,
    Set<Id> effect_intent_ids,
    Set<Id> observation_ids,
    Set<Id> fact_ids,
    Set<Id> completed_result_keys,
    int recovery_attempt,
    @Nullable Id recovery_reason,
    Revision checkpoint_revision,
    Set<Id> mutation_ids,
    @Nullable Id arbitration_decision_ref) {
    public EventInstance {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(event_instance_id, "event_instance_id");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(catalog_revision, "catalog_revision");
        location_refs = Checks.copy(location_refs);
        Objects.requireNonNull(tuning_revision, "tuning_revision");
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(state, "state");
        Checks.nonnegative(state_revision, "state_revision");
        Objects.requireNonNull(cleanup_status, "cleanup_status");
        Objects.requireNonNull(created_at, "created_at");
        Objects.requireNonNull(updated_at, "updated_at");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(backup_generation, "backup_generation");
        Objects.requireNonNull(server_epoch, "server_epoch");
        Objects.requireNonNull(seed, "seed");
        Checks.nonnegative(next_draw_index, "next_draw_index");
        lease_ids = Checks.copy(lease_ids);
        effect_intent_ids = Checks.copy(effect_intent_ids);
        observation_ids = Checks.copy(observation_ids);
        fact_ids = Checks.copy(fact_ids);
        completed_result_keys = Checks.copy(completed_result_keys);
        Checks.nonnegative(recovery_attempt, "recovery_attempt");
        Objects.requireNonNull(checkpoint_revision, "checkpoint_revision");
        mutation_ids = Checks.copy(mutation_ids);
        Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "Wrong record schema version");
        if (requested_outcome == Outcome.COMPLETED)
            Checks.require(state == LifecycleState.CLEANING || state == LifecycleState.RECOVERY_REQUIRED || state == LifecycleState.COMPLETED,
                "Success outcome requires committed cleanup or recovery state");
        Checks.require(!updated_at.isBefore(created_at) && !deadline.isBefore(created_at), "Instance time order"); if (Lifecycle.isTerminal(state)) Checks.require(cleanup_status == CleanupStatus.COMPLETE && requested_outcome != null && state.name().equals(requested_outcome.name()), "Terminal requires completed cleanup and matching outcome");
    }
}
