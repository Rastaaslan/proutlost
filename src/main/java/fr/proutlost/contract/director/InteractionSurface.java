package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record InteractionSurface(
    Id surface_id,
    Id kind,
    List<Id> anchor_ids,
    List<Id> slot_ids,
    Set<Id> record_ids,
    Set<Id> observable_actions,
    Id player_action_policy,
    Set<Id> protected_zone_ids) {
    public InteractionSurface {
        Objects.requireNonNull(surface_id, "surface_id");
        Objects.requireNonNull(kind, "kind");
        anchor_ids = Checks.copy(anchor_ids);
        slot_ids = Checks.copy(slot_ids);
        record_ids = Checks.copy(record_ids);
        observable_actions = Checks.copy(observable_actions);
        Objects.requireNonNull(player_action_policy, "player_action_policy");
        protected_zone_ids = Checks.copy(protected_zone_ids);
    }
}
