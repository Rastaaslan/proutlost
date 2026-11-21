package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record TerrainCellSnapshot(BlockPosition position, Fingerprint stateFingerprint, ResourceId codec, Set<TagId> tags, Set<TerrainHazard> hazards, boolean protectedContent, boolean knownContent, Epoch ownershipEpoch, Epoch playerOverrideEpoch) {
 public TerrainCellSnapshot { Objects.requireNonNull(position); Objects.requireNonNull(stateFingerprint); Objects.requireNonNull(codec); tags=set(tags); hazards=set(hazards); Objects.requireNonNull(ownershipEpoch); Objects.requireNonNull(playerOverrideEpoch);  }
}
