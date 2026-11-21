package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ImportedRegion(RegionId regionId, DimensionId dimension, ImportBounds bounds, Set<TagId> tags) {
 public ImportedRegion { Objects.requireNonNull(regionId); Objects.requireNonNull(dimension); Objects.requireNonNull(bounds); tags=set(tags);  }
}
