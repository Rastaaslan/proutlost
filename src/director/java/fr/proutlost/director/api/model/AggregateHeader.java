package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AggregateHeader(int schemaVersion, UUID id, Revision revision, Sequence lastSequence, Tick updatedTick) {
 public AggregateHeader { nonnegative(schemaVersion); Objects.requireNonNull(id); Objects.requireNonNull(revision); Objects.requireNonNull(lastSequence); Objects.requireNonNull(updatedTick); require(schemaVersion==1,"schema version"); }
}
