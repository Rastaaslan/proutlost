package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ControlledWater(ResourceId recipe, BlobRef propagationMask, long transferUnits, int maxLevel) implements PrimitiveArgs {
 public ControlledWater { Objects.requireNonNull(recipe); Objects.requireNonNull(propagationMask); nonnegative(transferUnits); nonnegative(maxLevel);  }
}
