package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PatternUpdate(RegionId regionId, Pattern pattern, UnitValue confidence, long independentEpisodes) {
 public PatternUpdate { Objects.requireNonNull(regionId); Objects.requireNonNull(pattern); Objects.requireNonNull(confidence); nonnegative(independentEpisodes);  }
}
