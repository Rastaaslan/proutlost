package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record CampEpisodeObservation(ObservationId observationId, RegionId regionId, AnchorId anchorId, EvidenceWindowId windowId, EpisodeId episodeId, Tick windowStart, Tick windowEnd, DurationTicks occupiedTicks, ActivityHistogram histogram) {
 public CampEpisodeObservation { Objects.requireNonNull(observationId); Objects.requireNonNull(regionId); Objects.requireNonNull(anchorId); Objects.requireNonNull(windowId); Objects.requireNonNull(episodeId); Objects.requireNonNull(windowStart); Objects.requireNonNull(windowEnd); Objects.requireNonNull(occupiedTicks); Objects.requireNonNull(histogram); require(windowStart.compareTo(windowEnd)<0,"observation window"); }
}
