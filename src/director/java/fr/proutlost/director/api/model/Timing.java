package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Timing(DurationTicks maxDurationTicks, Cooldowns cooldowns) {
 public Timing { Objects.requireNonNull(maxDurationTicks); Objects.requireNonNull(cooldowns); require(maxDurationTicks.value()>0,"duration"); }
}
