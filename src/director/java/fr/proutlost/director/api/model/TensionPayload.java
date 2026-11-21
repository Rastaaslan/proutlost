package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record TensionPayload(GlobalTension tension, DurationTicks duration, Tick expiresTick) implements AdminPayload {
 public TensionPayload { Objects.requireNonNull(tension); Objects.requireNonNull(duration); Objects.requireNonNull(expiresTick); require(duration.value()>0,"bounded override"); }
}
