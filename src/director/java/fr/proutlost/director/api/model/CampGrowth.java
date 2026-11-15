package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record CampGrowth(TagId anchorTag, ResourceId recipe, long maxBlocks) implements PrimitiveArgs {
 public CampGrowth { Objects.requireNonNull(anchorTag); Objects.requireNonNull(recipe); nonnegative(maxBlocks);  }
}
