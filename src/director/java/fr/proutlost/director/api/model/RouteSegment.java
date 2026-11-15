package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RouteSegment(BlockPosition from, BlockPosition to, double radius) {
 public RouteSegment { Objects.requireNonNull(from); Objects.requireNonNull(to); finite(radius); require(from.dimension().equals(to.dimension()) && radius>=0,"route"); }
}
