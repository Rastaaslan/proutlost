package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record SemanticRegion(
    SemanticRegionId region_id,
    Revision revision,
    List<Bounds> bounds,
    Set<Id> tags,
    int priority) {
    public SemanticRegion {
        Objects.requireNonNull(region_id, "region_id");
        Objects.requireNonNull(revision, "revision");
        bounds = Checks.copy(bounds);
        tags = Checks.copy(tags);
    }
}
