package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record LocationConstraint(
    Id constraint_id,
    Id owner_arc_id,
    Set<Id> location_ids,
    List<StatePredicate> requires_state,
    List<StatePredicate> forbids_state,
    @Nullable StatePredicate locks_location_until) {
    public LocationConstraint {
        Objects.requireNonNull(constraint_id, "constraint_id");
        Objects.requireNonNull(owner_arc_id, "owner_arc_id");
        location_ids = Checks.copy(location_ids);
        requires_state = Checks.copy(requires_state);
        forbids_state = Checks.copy(forbids_state);
        Checks.require(!location_ids.isEmpty(), "Empty constraint footprint");
    }
}
