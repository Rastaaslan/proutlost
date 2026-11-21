package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record TargetSound(String role, ResourceId sound, double volume, double radiusBlocks, int repeats) implements PrimitiveArgs {
 public TargetSound { text(role); Objects.requireNonNull(sound); finite(volume); require(volume>=0,"negative volume"); finite(radiusBlocks); require(radiusBlocks>=0,"negative radiusBlocks"); nonnegative(repeats);  }
}
