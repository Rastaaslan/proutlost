package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ScopeSnapshot(ScopeRef scope, Revision revision, Set<SensorKind> availableSensors, Set<SensorKind> missingSensors, Map<Feature,UnitValue> scoringFeatures) {
 public ScopeSnapshot { Objects.requireNonNull(scope); Objects.requireNonNull(revision); availableSensors=set(availableSensors); missingSensors=set(missingSensors); scoringFeatures=map(scoringFeatures); require(Collections.disjoint(availableSensors,missingSensors),"sensor status"); }
}
