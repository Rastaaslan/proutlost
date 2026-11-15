package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record GhostParameters(ResourceId model, UnitValue opacity) implements PerceptionParameters {
 public GhostParameters { Objects.requireNonNull(model); Objects.requireNonNull(opacity);  }
}
