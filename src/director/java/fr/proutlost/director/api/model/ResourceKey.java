package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ResourceKey(OwnershipDomain domain, DimensionId dimension, String key) {
 public ResourceKey { Objects.requireNonNull(domain); Objects.requireNonNull(dimension); text(key);  }
}
