package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ParticleParameters(int count, double radius) implements PerceptionParameters {
 public ParticleParameters { nonnegative(count); finite(radius); require(count<=4096&&radius>=0&&radius<=512,"particle bounds"); }
}
