package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ImportedAnchor(AnchorId anchorId, LocationId locationId, DimensionId dimension, ImportPosition position, Set<TagId> tags) {
 public ImportedAnchor { Objects.requireNonNull(anchorId); Objects.requireNonNull(locationId); Objects.requireNonNull(dimension); Objects.requireNonNull(position); tags=set(tags);  }
}
