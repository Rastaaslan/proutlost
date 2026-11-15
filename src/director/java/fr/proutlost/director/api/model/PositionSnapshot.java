package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PositionSnapshot(BlockPosition position, Tick tick) {
 public PositionSnapshot { Objects.requireNonNull(position); Objects.requireNonNull(tick);  }
}
