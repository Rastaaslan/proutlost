package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ObservationBatch(List<ObservationId> inputIds, List<DirectorObservation> observations, Map<ScopeRef,Revision> beforeRevisions, SnapshotBatch context, List<CampEpisodeObservation> campEpisodes, List<RouteTransitionObservation> routeTransitions, Map<PlayerId,List<Exposure>> exposures) {
 public ObservationBatch { inputIds=list(inputIds); observations=list(observations); beforeRevisions=map(beforeRevisions); Objects.requireNonNull(context); campEpisodes=list(campEpisodes); routeTransitions=list(routeTransitions); exposures=map(exposures); require(inputIds.stream().distinct().count()==inputIds.size(),"duplicate input identity"); require(campEpisodes.size()<=4096&&routeTransitions.size()<=4096,"observation batch bound"); Map<PlayerId,List<Exposure>> copied=new HashMap<>(); exposures.forEach((p,e)->copied.put(p,list(e))); exposures=Map.copyOf(copied); }
}
