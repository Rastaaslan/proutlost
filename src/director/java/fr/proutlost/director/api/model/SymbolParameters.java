package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record SymbolParameters(ResourceId symbol, UnitValue opacity) implements PerceptionParameters {
 public SymbolParameters { Objects.requireNonNull(symbol); Objects.requireNonNull(opacity);  }
}
