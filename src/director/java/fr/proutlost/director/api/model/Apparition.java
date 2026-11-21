package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Apparition(String role, ResourceId model, DurationTicks followTicks, double maxDistanceBlocks) implements PrimitiveArgs {
 public Apparition { text(role); Objects.requireNonNull(model); Objects.requireNonNull(followTicks); finite(maxDistanceBlocks); require(maxDistanceBlocks>=0,"negative maxDistanceBlocks");  }
}
