package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ReservationToken(ReservationId id, WorldId worldId, EventInstanceId instanceId, Epoch generation, Tick expiryTick, List<ResourceClaim> claims) {
 public ReservationToken { Objects.requireNonNull(id); Objects.requireNonNull(worldId); Objects.requireNonNull(instanceId); Objects.requireNonNull(generation); Objects.requireNonNull(expiryTick); claims=list(claims);  }
}
