package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DirectorObservation(ObservationId observationId, ResourceId type, ScopeRef coarseScope, Tick tick, Map<String,Double> aggregateDelta, UnitValue confidence) {
 public DirectorObservation { Objects.requireNonNull(observationId); Objects.requireNonNull(type); Objects.requireNonNull(coarseScope); Objects.requireNonNull(tick); aggregateDelta=map(aggregateDelta); aggregateDelta.values().forEach(Checks::finite); Objects.requireNonNull(confidence);  }
}
