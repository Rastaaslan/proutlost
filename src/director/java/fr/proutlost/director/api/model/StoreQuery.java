package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record StoreQuery(WorldId worldId, Set<AggregateKey> aggregateKeys, Sequence sequence, Optional<String> pageCursor, int limit) {
 public StoreQuery { Objects.requireNonNull(worldId); aggregateKeys=set(aggregateKeys); Objects.requireNonNull(sequence); Objects.requireNonNull(pageCursor); pageCursor.ifPresent(c->require(c.length()<=4096,"cursor bound")); nonnegative(limit); require(limit>0&&limit<=1024,"store page bound"); }
}
