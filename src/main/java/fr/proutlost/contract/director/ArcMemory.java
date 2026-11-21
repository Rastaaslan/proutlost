package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record ArcMemory(
    Id world_id,
    SchemaVersion schema_version,
    Id id,
    long revision,
    Id arc_instance_id,
    DefinitionRef definition,
    AudienceScope scope,
    ArcStatus status,
    Map<Id,StateValue> states,
    Map<Id,LocationRef> bindings,
    List<LocationConstraint> constraints,
    Set<Id> accepted_fact_ids,
    long context_cursor) {
    public ArcMemory {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(id, "id");
        Checks.nonnegative(revision, "revision");
        Objects.requireNonNull(arc_instance_id, "arc_instance_id");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(status, "status");
        states = Checks.copy(states);
        bindings = Checks.copy(bindings);
        constraints = Checks.copy(constraints);
        accepted_fact_ids = Checks.copy(accepted_fact_ids);
        Checks.nonnegative(context_cursor, "context_cursor");
        Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "Wrong record schema version");
        Checks.require(id.equals(arc_instance_id), "Arc identity mismatch"); for (var c : constraints) Checks.require(c.owner_arc_id().equals(id), "Constraint owner mismatch");
    }
}
