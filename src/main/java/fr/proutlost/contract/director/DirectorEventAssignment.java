package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record DirectorEventAssignment(
    Id world_id,
    SchemaVersion schema_version,
    Id assignment_id,
    Id event_instance_id,
    Id need_id,
    @Nullable OpportunityRef opportunity,
    Id binding_id,
    Revision catalog_revision,
    Digest catalog_digest,
    List<LocationRef> locations,
    List<AnchorRef> anchors,
    List<SurfaceRef> surfaces,
    AudienceScope audience,
    Set<QuestScope> quest_scopes,
    DefinitionRef definition,
    Revision tuning_revision,
    Revision observation_revision,
    SelectionDecision decision,
    Set<Id> lease_ids,
    RecoveryPolicy recovery) {
    public DirectorEventAssignment {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(assignment_id, "assignment_id");
        Objects.requireNonNull(event_instance_id, "event_instance_id");
        Objects.requireNonNull(need_id, "need_id");
        Objects.requireNonNull(binding_id, "binding_id");
        Objects.requireNonNull(catalog_revision, "catalog_revision");
        Objects.requireNonNull(catalog_digest, "catalog_digest");
        locations = Checks.copy(locations);
        anchors = Checks.copy(anchors);
        surfaces = Checks.copy(surfaces);
        Objects.requireNonNull(audience, "audience");
        quest_scopes = Checks.copy(quest_scopes);
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(tuning_revision, "tuning_revision");
        Objects.requireNonNull(observation_revision, "observation_revision");
        Objects.requireNonNull(decision, "decision");
        lease_ids = Checks.copy(lease_ids);
        Objects.requireNonNull(recovery, "recovery");
        Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "Wrong record schema version");
    }
}
