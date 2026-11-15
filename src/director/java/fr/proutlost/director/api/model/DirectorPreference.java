package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DirectorPreference(RequestId requestId, Selector selector, double boundedWeightBias, Tick expiresTick) implements RequestPayload {
 public DirectorPreference { Objects.requireNonNull(requestId); Objects.requireNonNull(selector); finite(boundedWeightBias); Objects.requireNonNull(expiresTick); require(boundedWeightBias>=0,"bias"); }
}
