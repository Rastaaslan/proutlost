package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record UniqueAggregate(UniqueUseRecord value) implements AggregateState {
 public UniqueAggregate { Objects.requireNonNull(value);  }
 public AggregateHeader header() { return value.header(); }
 public AggregateKey key() { return new AggregateKey(AggregateKind.UNIQUE, header().id()); }
}
