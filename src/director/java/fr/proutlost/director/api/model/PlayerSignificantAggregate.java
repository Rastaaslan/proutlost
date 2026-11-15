package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PlayerSignificantAggregate(PlayerSignificantMemory value) implements AggregateState {
 public PlayerSignificantAggregate { Objects.requireNonNull(value);  }
 public AggregateHeader header() { return value.header(); }
 public AggregateKey key() { return new AggregateKey(AggregateKind.PLAYER_SIGNIFICANT, header().id()); }
}
