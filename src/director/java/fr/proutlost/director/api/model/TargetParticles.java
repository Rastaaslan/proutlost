package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record TargetParticles(String role, ResourceId particle, int count, double radiusBlocks) implements PrimitiveArgs {
 public TargetParticles { text(role); Objects.requireNonNull(particle); nonnegative(count); finite(radiusBlocks); require(radiusBlocks>=0,"negative radiusBlocks");  }
}
