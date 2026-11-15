package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ConsumerSpec(ConsumerId consumerId, Set<FactType> filters, Sequence nextSequence, int batchLimit) {
 public ConsumerSpec { Objects.requireNonNull(consumerId); filters=set(filters); Objects.requireNonNull(nextSequence); require(batchLimit>0&&batchLimit<=1024,"consumer page bound");  }
}
