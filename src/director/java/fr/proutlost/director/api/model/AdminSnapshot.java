package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AdminSnapshot(RequestId requestId, Revision snapshotRevision, Tick capturedTick, Sequence lastDurableSequence, List<AdminRow> rows, Optional<String> nextCursor, Set<AdminOperation> capabilities) {
 public AdminSnapshot { Objects.requireNonNull(requestId); Objects.requireNonNull(snapshotRevision); Objects.requireNonNull(capturedTick); Objects.requireNonNull(lastDurableSequence); rows=list(rows); Objects.requireNonNull(nextCursor); capabilities=set(capabilities); require(rows.size()<=50,"page bound"); }
}
