package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RestoredAggregate(AggregateState state, Sequence committedSequence, Fingerprint transactionHash) {
 public RestoredAggregate { Objects.requireNonNull(state); Objects.requireNonNull(committedSequence); Objects.requireNonNull(transactionHash); require(committedSequence.compareTo(state.header().lastSequence())>=0,"aggregate commit sequence"); }
}
