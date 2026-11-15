package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record EventDefinition(
    Id world_id,
    SchemaVersion schema_version,
    Id definition_id,
    Revision revision,
    Digest digest,
    Family family,
    Intensity intensity,
    EventNeed need_template,
    Id audience_policy,
    Set<Id> eligibility_validators,
    List<EffectSpec> effects,
    List<ResultRule> result_rules,
    long max_duration_ms,
    Id cleanup_policy,
    RecoveryPolicy recovery,
    Set<Id> compatibility_tags,
    Set<Id> exclusion_tags,
    Id budget_class,
    Set<StateAddress> state_reads,
    Set<StateAddress> state_writes,
    Set<DefinitionRef> mutation_operation_refs) {
    public EventDefinition {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(definition_id, "definition_id");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(intensity, "intensity");
        Objects.requireNonNull(need_template, "need_template");
        Objects.requireNonNull(audience_policy, "audience_policy");
        eligibility_validators = Checks.copy(eligibility_validators);
        effects = Checks.copy(effects);
        result_rules = Checks.copy(result_rules);
        Checks.nonnegative(max_duration_ms, "max_duration_ms");
        Checks.duration(max_duration_ms);
        Objects.requireNonNull(cleanup_policy, "cleanup_policy");
        Objects.requireNonNull(recovery, "recovery");
        compatibility_tags = Checks.copy(compatibility_tags);
        exclusion_tags = Checks.copy(exclusion_tags);
        Objects.requireNonNull(budget_class, "budget_class");
        state_reads = Checks.copy(state_reads);
        state_writes = Checks.copy(state_writes);
        mutation_operation_refs = Checks.copy(mutation_operation_refs);
        Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "Wrong record schema version");
        Checks.require(world_id.equals(need_template.world_id()), "Cross-world need"); Checks.require(!need_template.resources().isEmpty(), "Missing declared resources");
    }
}
