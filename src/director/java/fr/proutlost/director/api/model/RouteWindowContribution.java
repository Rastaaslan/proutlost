package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Immutable per-window sufficient statistics; no positions or trajectories. Same window ID must have identical contribution when merging; conflicting versions require reconciliation. */
public record RouteWindowContribution(EvidenceWindow window, long transitions, Map<Sector,Long> directionHistogram) {
 public RouteWindowContribution { Objects.requireNonNull(window); nonnegative(transitions); directionHistogram=map(directionHistogram); directionHistogram.values().forEach(Checks::nonnegative); }
}
