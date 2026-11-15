package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ImportedLocation(LocationId locationId, String externalId, Set<RegionId> regionIds, DimensionId dimension, ImportBounds bounds, Set<TagId> tags) {
 public ImportedLocation { Objects.requireNonNull(locationId); text(externalId); require(!externalId.isEmpty(),"external id"); regionIds=set(regionIds); Objects.requireNonNull(dimension); Objects.requireNonNull(bounds); tags=set(tags);  }
}
