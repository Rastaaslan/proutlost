package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record PlayerActionContext(
    Id world_id,
    Id context_id,
    AudienceScope audience,
    List<SurfaceRef> surfaces,
    Id policy_id) {
    public PlayerActionContext {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(context_id, "context_id");
        Objects.requireNonNull(audience, "audience");
        surfaces = Checks.copy(surfaces);
        Objects.requireNonNull(policy_id, "policy_id");
    }
}
