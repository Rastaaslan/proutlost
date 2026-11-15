package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RegionFilter(Optional<RegionId> regionId, Set<TagId> tags) implements AdminFilter {
 public RegionFilter { Objects.requireNonNull(regionId); tags=set(tags);  }
}
