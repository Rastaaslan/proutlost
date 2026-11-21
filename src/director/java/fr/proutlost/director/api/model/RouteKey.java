package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RouteKey(RegionId from, RegionId to) {
 public RouteKey { Objects.requireNonNull(from); Objects.requireNonNull(to); require(!from.equals(to),"route regions"); }
}
