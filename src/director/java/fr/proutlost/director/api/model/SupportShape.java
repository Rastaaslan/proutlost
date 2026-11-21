package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record SupportShape(List<Aabb> boxes, List<SupportDisc> discs, List<RouteSegment> routes) {
 public SupportShape { boxes=list(boxes); discs=list(discs); routes=list(routes);  }
}
