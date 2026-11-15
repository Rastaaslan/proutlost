package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Landslide(ResourceId recipe, long maxBlocks, ResourceId damageProfile) implements PrimitiveArgs {
 public Landslide { Objects.requireNonNull(recipe); nonnegative(maxBlocks); Objects.requireNonNull(damageProfile);  }
}
