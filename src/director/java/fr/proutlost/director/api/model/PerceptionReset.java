package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PerceptionReset(PerceptionHeader header, PerceptionResetScope scope) {
 public PerceptionReset { Objects.requireNonNull(header); Objects.requireNonNull(scope);  }
}
