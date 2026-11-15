package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DecorativeMove(ResourceKey object, BlockPosition destination, Fingerprint expectedComponents) implements PrimitiveArgs {
 public DecorativeMove { Objects.requireNonNull(object); Objects.requireNonNull(destination); Objects.requireNonNull(expectedComponents);  }
}
