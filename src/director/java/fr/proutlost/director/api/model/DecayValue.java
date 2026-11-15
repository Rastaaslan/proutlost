package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DecayValue(double value, Tick observedTick, String halfLifeProfile, long evidenceCount, UnitValue confidence) {
 public DecayValue { finite(value); Objects.requireNonNull(observedTick); text(halfLifeProfile); nonnegative(evidenceCount); Objects.requireNonNull(confidence);  }
}
