package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Bounded aggregate statistics, never trajectories. Windows are authoritative merge contributions.
 * Merge by window ID, requiring identical contents for overlapping IDs; conflicting versions must
 * be reconciled before summation. Totals are projections of the retained window union, not an
 * additional contribution. Retention/eviction is the reducer's explicit policy, never implicit IO.
 */
public record RouteTransitionMemory(RouteKey route, long transitions, Map<Sector,Long> directionHistogram, List<RouteWindowContribution> windows) {
 public RouteTransitionMemory { Objects.requireNonNull(route); nonnegative(transitions); directionHistogram=map(directionHistogram); windows=list(windows); directionHistogram.values().forEach(Checks::nonnegative); require(windows.size()<=16&&windows.stream().map(w->w.window().id()).distinct().count()==windows.size(),"route windows"); }
}
