package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RouteTransitionObservation(ObservationId observationId, RouteKey route, EvidenceWindowId windowId, EpisodeId episodeId, Tick windowStart, Tick windowEnd, Sector direction, long transitionCount) {
 public RouteTransitionObservation { Objects.requireNonNull(observationId); Objects.requireNonNull(route); Objects.requireNonNull(windowId); Objects.requireNonNull(episodeId); Objects.requireNonNull(windowStart); Objects.requireNonNull(windowEnd); Objects.requireNonNull(direction); nonnegative(transitionCount); require(windowStart.compareTo(windowEnd)<0&&transitionCount>0,"route observation"); }
}
