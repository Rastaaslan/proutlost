package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record Diagnostic(
    Id world_id,
    Id correlation_id,
    ReasonCode reason,
    Map<Id,Revision> revisions,
    Set<Id> resource_ids,
    Set<Id> constraint_ids,
    Instant timestamp) {
    public Diagnostic {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(correlation_id, "correlation_id");
        Objects.requireNonNull(reason, "reason");
        revisions = Checks.copy(revisions);
        resource_ids = Checks.copy(resource_ids);
        constraint_ids = Checks.copy(constraint_ids);
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
