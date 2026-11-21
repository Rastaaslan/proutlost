package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Footprint(DimensionId dimension, Set<ChunkRef> loadedChunks, Set<BlockPosition> cells, List<Aabb> bounds) {
 public Footprint { Objects.requireNonNull(dimension); loadedChunks=set(loadedChunks); cells=set(cells); bounds=list(bounds); require(loadedChunks.stream().allMatch(c->c.dimension().equals(dimension)) && cells.stream().allMatch(c->c.dimension().equals(dimension)) && bounds.stream().allMatch(c->c.dimension().equals(dimension)),"mixed dimension"); }
}
