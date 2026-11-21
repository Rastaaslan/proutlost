package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Reduction(List<AggregateChange> aggregateChanges, List<AggregateDelta> counterDeltas, List<DirectorFact> proposedFacts, List<PatternUpdate> patternUpdates, List<ObservationId> consumedInputs) {
 public Reduction { aggregateChanges=list(aggregateChanges); counterDeltas=list(counterDeltas); proposedFacts=list(proposedFacts); patternUpdates=list(patternUpdates); consumedInputs=list(consumedInputs); require(aggregateChanges.stream().map(AggregateChange::key).distinct().count()==aggregateChanges.size(),"duplicate aggregate write"); require(consumedInputs.stream().distinct().count()==consumedInputs.size(),"duplicate consumed input"); }
}
