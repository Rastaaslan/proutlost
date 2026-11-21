package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record BlobReadRequest(WorldId worldId, Epoch storeGeneration, Set<BlobRef> references) {
 public BlobReadRequest { Objects.requireNonNull(worldId); Objects.requireNonNull(storeGeneration); references=set(references); require(!references.isEmpty()&&references.size()<=256,"blob read bound"); }
}
