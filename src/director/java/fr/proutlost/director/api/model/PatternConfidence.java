package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PatternConfidence(Pattern pattern, UnitValue minimum) implements Condition {
 public PatternConfidence { Objects.requireNonNull(pattern); Objects.requireNonNull(minimum);  }
}
