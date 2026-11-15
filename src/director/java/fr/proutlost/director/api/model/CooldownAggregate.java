package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record CooldownAggregate(CooldownRecord value) implements AggregateState {
 public CooldownAggregate { Objects.requireNonNull(value);  }
 public AggregateHeader header() { return value.header(); }
 public AggregateKey key() { return new AggregateKey(AggregateKind.COOLDOWN, header().id()); }
}
