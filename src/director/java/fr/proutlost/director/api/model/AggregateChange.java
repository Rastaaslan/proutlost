package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AggregateChange(AggregateKey key, Optional<Revision> expectedRevision, AggregateState next) {
 public AggregateChange { Objects.requireNonNull(key); Objects.requireNonNull(expectedRevision); Objects.requireNonNull(next); require(key.equals(next.key()),"aggregate change identity"); require(next.header().revision().equals(expectedRevision.map(r->r.plus(1)).orElse(new Revision(1))),"aggregate revision CAS"); }
}
