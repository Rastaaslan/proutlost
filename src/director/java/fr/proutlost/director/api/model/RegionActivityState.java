package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RegionActivityState(Map<AnchorId,CampRoutineMemory> camps, Map<RouteKey,RouteTransitionMemory> routes) {
 public RegionActivityState { camps=map(camps); routes=map(routes); require(camps.size()<=128&&routes.size()<=256,"sparse regional activity bounds"); require(camps.entrySet().stream().allMatch(e->e.getKey().equals(e.getValue().anchorId()))&&routes.entrySet().stream().allMatch(e->e.getKey().equals(e.getValue().route())),"activity index"); }
}
