package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record EffectProgress(EffectCursor cursor, SliceResult result) {
 public EffectProgress { Objects.requireNonNull(cursor); Objects.requireNonNull(result);  }
}
