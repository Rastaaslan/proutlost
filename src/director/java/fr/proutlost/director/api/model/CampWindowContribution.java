package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Immutable per-window sufficient statistics; no positions or trajectories. Same window ID must have identical contribution when merging; conflicting versions require reconciliation. */
public record CampWindowContribution(EvidenceWindow window, long independentVisits, DurationTicks occupiedTicks, ActivityHistogram histogram) {
 public CampWindowContribution { Objects.requireNonNull(window); nonnegative(independentVisits); Objects.requireNonNull(occupiedTicks); Objects.requireNonNull(histogram); }
}
