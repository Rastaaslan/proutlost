package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record EventAggregate(EventInstance value) implements AggregateState {
 public EventAggregate { Objects.requireNonNull(value);  }
 public AggregateHeader header() { return value.header(); }
 public AggregateKey key() { return new AggregateKey(AggregateKind.EVENT, header().id()); }
}
