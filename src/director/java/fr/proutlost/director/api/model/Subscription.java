package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Subscription(ConsumerSpec consumer, List<DirectorEnvelope> batch, Sequence nextSequence, boolean hasMore) {
 public Subscription { Objects.requireNonNull(consumer); batch=list(batch); Objects.requireNonNull(nextSequence);  }
}
