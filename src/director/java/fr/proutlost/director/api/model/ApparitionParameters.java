package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ApparitionParameters(DurationTicks followTicks, double maximumDistance) implements PerceptionParameters {
 public ApparitionParameters { Objects.requireNonNull(followTicks); finite(maximumDistance); require(maximumDistance>=0&&maximumDistance<=512,"follow bound"); }
}
