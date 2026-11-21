package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record LocationImportResult(AckStatus status, Revision revision, Map<RegionId,RegionId> canonicalRegions, List<SystemError> errors) {
 public LocationImportResult { Objects.requireNonNull(status); Objects.requireNonNull(revision); canonicalRegions=map(canonicalRegions); errors=list(errors);  }
}
