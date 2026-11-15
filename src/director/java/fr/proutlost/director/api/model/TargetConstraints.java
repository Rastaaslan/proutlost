package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record TargetConstraints(Set<RegionId> regionIds, Set<PlayerId> playerIds) {
 public TargetConstraints { regionIds=set(regionIds); playerIds=set(playerIds);  }
}
