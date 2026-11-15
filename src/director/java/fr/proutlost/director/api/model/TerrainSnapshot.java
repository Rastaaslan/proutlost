package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record TerrainSnapshot(Footprint loadedFootprint, Map<BlockPosition,TerrainCellSnapshot> cells, List<RouteSegment> safeExits, Map<ResourceId,UnitValue> risks, Revision revision) {
 public TerrainSnapshot { Objects.requireNonNull(loadedFootprint); cells=map(cells); safeExits=list(safeExits); risks=map(risks); Objects.requireNonNull(revision); require(cells.entrySet().stream().allMatch(e->e.getKey().equals(e.getValue().position())&&loadedFootprint.cells().contains(e.getKey())),"terrain index"); }
}
