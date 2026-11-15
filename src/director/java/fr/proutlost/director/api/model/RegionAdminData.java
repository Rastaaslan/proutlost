package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RegionAdminData(RegionRecord region, RegionMemory memory, Set<TagId> tags, List<AnchorRecord> anchors, List<LocationRecord> locations) implements AdminData {
 public RegionAdminData { Objects.requireNonNull(region); Objects.requireNonNull(memory); tags=set(tags); anchors=list(anchors); locations=list(locations);  }
}
