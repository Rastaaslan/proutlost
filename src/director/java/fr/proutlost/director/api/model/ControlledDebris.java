package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ControlledDebris(ResourceId recipe, Footprint envelope, int slots, ResourceId damageProfile) implements PrimitiveArgs {
 public ControlledDebris { Objects.requireNonNull(recipe); Objects.requireNonNull(envelope); nonnegative(slots); Objects.requireNonNull(damageProfile);  }
}
