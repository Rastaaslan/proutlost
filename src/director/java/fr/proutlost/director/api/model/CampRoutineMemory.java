package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Bounded aggregate statistics, never trajectories. Windows are authoritative merge contributions.
 * Merge by window ID, requiring identical contents for overlapping IDs; conflicting versions must
 * be reconciled before summation. Totals are projections of the retained window union, not an
 * additional contribution. Retention/eviction is the reducer's explicit policy, never implicit IO.
 */
public record CampRoutineMemory(AnchorId anchorId, List<CampWindowContribution> windows, long independentVisits, DurationTicks occupiedTicks, ActivityHistogram histogram, UnitValue confidence) {
 public CampRoutineMemory { Objects.requireNonNull(anchorId); windows=list(windows); nonnegative(independentVisits); Objects.requireNonNull(occupiedTicks); Objects.requireNonNull(histogram); Objects.requireNonNull(confidence); require(windows.size()<=16&&windows.stream().map(w->w.window().id()).distinct().count()==windows.size(),"camp windows"); }
}
