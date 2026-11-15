package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AggregateKey(AggregateKind kind, UUID id) {
 public AggregateKey { Objects.requireNonNull(kind); Objects.requireNonNull(id);  }
}
