package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DirectorClock(Tick simTick, long monotonicNanos, java.time.Instant utc) {
 public DirectorClock { Objects.requireNonNull(simTick);  Objects.requireNonNull(utc);  }
}
