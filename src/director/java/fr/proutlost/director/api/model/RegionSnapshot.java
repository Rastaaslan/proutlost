package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RegionSnapshot(RegionRecord region, Set<TagId> tags, Set<PlayerId> presentPlayers, List<CampSnapshot> camps, Map<ResourceId,UnitValue> risks) {
 public RegionSnapshot { Objects.requireNonNull(region); tags=set(tags); presentPlayers=set(presentPlayers); camps=list(camps); risks=map(risks);  }
}
