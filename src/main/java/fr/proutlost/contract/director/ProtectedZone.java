package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record ProtectedZone(
    Id zone_id,
    Bounds bounds,
    Id access_policy,
    Id presentation_policy,
    Id player_action_policy) {
    public ProtectedZone {
        Objects.requireNonNull(zone_id, "zone_id");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(access_policy, "access_policy");
        Objects.requireNonNull(presentation_policy, "presentation_policy");
        Objects.requireNonNull(player_action_policy, "player_action_policy");
    }
}
