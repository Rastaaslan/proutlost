package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DamageProfile(ResourceId id, Set<ResourceId> damageTypes, double perHit, double maxPerPlayer, DurationTicks hitGap, List<Aabb> excludedZones, Set<fr.proutlost.director.api.error.ReasonCode> pauseReasons, ResourceId hitLedgerCodec) {
 public DamageProfile { Objects.requireNonNull(id); damageTypes=set(damageTypes); finite(perHit); finite(maxPerPlayer); Objects.requireNonNull(hitGap); excludedZones=list(excludedZones); pauseReasons=set(pauseReasons); Objects.requireNonNull(hitLedgerCodec); require(perHit>=0 && maxPerPlayer>=perHit,"damage"); }
}
