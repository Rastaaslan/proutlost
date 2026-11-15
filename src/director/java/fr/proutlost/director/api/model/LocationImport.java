package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record LocationImport(int schemaVersion, WorldId worldId, ResourceId sourceNamespace, Revision exportRevision, Fingerprint exportDigest, List<ImportedRegion> regions, List<ImportedLocation> locations, List<ImportedAnchor> anchors) {
 public LocationImport { nonnegative(schemaVersion); Objects.requireNonNull(worldId); Objects.requireNonNull(sourceNamespace); Objects.requireNonNull(exportRevision); Objects.requireNonNull(exportDigest); regions=list(regions); locations=list(locations); anchors=list(anchors); require(schemaVersion==1,"import version"); }
}
