package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Exposure(DefinitionId definitionId, Family family, String role, Tick tick, UnitValue intensity) {
 public Exposure { Objects.requireNonNull(definitionId); Objects.requireNonNull(family); text(role); Objects.requireNonNull(tick); Objects.requireNonNull(intensity);  }
}
