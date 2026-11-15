package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record GroupSnapshot(UUID groupId, Set<PlayerId> members, Set<RegionId> regions, Tick capturedTick, Revision revision) {
 public GroupSnapshot { Objects.requireNonNull(groupId); members=set(members); regions=set(regions); Objects.requireNonNull(capturedTick); Objects.requireNonNull(revision);  }
}
