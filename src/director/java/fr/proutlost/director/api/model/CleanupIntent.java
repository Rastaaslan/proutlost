package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record CleanupIntent(EventInstanceId instanceId, OperationToken operationToken, CleanupReason reason, Footprint footprint, OwnershipView ownership, BlobBundle blobs) {
 public CleanupIntent { Objects.requireNonNull(blobs); Objects.requireNonNull(instanceId); Objects.requireNonNull(operationToken); Objects.requireNonNull(reason); Objects.requireNonNull(footprint); Objects.requireNonNull(ownership);  }
}
