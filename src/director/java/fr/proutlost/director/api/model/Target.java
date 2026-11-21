package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Target(TargetScope scope, Set<TagId> regionTags, PlayerPolicy playerPolicy, Map<String,Role> roles) {
 public Target { Objects.requireNonNull(scope); regionTags=set(regionTags); Objects.requireNonNull(playerPolicy); roles=map(roles); require(!roles.isEmpty(),"roles"); }
}
