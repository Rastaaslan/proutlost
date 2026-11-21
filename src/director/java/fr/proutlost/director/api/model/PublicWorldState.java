package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PublicWorldState(Revision revision, GlobalTension tension, PacingState pacing, HealthMode health, Set<RegionId> publicRegionStateRefs) {
 public PublicWorldState { Objects.requireNonNull(revision); Objects.requireNonNull(tension); Objects.requireNonNull(pacing); Objects.requireNonNull(health); publicRegionStateRefs=set(publicRegionStateRefs);  }
}
