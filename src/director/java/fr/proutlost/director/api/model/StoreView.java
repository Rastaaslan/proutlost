package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record StoreView(List<DurableRecord> records, List<RestoredAggregate> aggregates, Sequence lastDurableSequence, boolean hasMore, Optional<String> nextCursor) {
 public StoreView { records=list(records); aggregates=list(aggregates); Objects.requireNonNull(lastDurableSequence); Objects.requireNonNull(nextCursor);  }
}
