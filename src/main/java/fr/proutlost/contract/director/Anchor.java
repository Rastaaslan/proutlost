package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record Anchor(
    Id anchor_id,
    Id role,
    Id dimension_id,
    Vec3 position,
    @Nullable Vec3 facing,
    int capacity,
    Set<Id> tags) {
    public Anchor {
        Objects.requireNonNull(anchor_id, "anchor_id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(dimension_id, "dimension_id");
        Objects.requireNonNull(position, "position");
        Checks.require(capacity > 0, "Positive capacity required");
        tags = Checks.copy(tags);
    }
}
