package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RecoveryPolicy(RecoveryMode mode, DurationTicks deadlineTicks) {
 public RecoveryPolicy { Objects.requireNonNull(mode); Objects.requireNonNull(deadlineTicks); require(deadlineTicks.value()>0,"recovery deadline"); }
}
