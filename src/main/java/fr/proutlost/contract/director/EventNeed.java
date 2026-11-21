package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record EventNeed(
    Id world_id,
    SchemaVersion schema_version,
    Id need_id,
    Family family,
    List<CapabilityRequirement> required_capabilities,
    Set<Id> source_types,
    Set<SemanticRegionId> semantic_region_ids,
    Set<Id> dimensions,
    List<LocationRef> required_locations,
    Set<Id> required_records,
    Set<Id> required_surface_kinds,
    @Nullable Id same_as_binding,
    Set<Id> distinct_from_bindings,
    List<EvidenceRef> evidence,
    Set<Channel> channels,
    int min_capacity,
    List<Preference> preferences,
    List<ResourceRequest> resources,
    RecoveryPolicy recovery) {
    public EventNeed {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(need_id, "need_id");
        Objects.requireNonNull(family, "family");
        required_capabilities = Checks.copy(required_capabilities);
        source_types = Checks.copy(source_types);
        semantic_region_ids = Checks.copy(semantic_region_ids);
        dimensions = Checks.copy(dimensions);
        required_locations = Checks.copy(required_locations);
        required_records = Checks.copy(required_records);
        required_surface_kinds = Checks.copy(required_surface_kinds);
        distinct_from_bindings = Checks.copy(distinct_from_bindings);
        evidence = Checks.copy(evidence);
        channels = Checks.copy(channels);
        Checks.require(min_capacity > 0, "Positive min_capacity required");
        preferences = Checks.copy(preferences);
        resources = Checks.copy(resources);
        Objects.requireNonNull(recovery, "recovery");
        Checks.require(schema_version.equals(SchemaVersion.BASE), "Wrong record schema version");
    }
}
