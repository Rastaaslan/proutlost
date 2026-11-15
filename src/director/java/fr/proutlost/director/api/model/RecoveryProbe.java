package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RecoveryProbe(PreparedEffect durableIntent, List<RecoveryEvidence> loadedProofs, BlobBundle blobs) {
 public RecoveryProbe { Objects.requireNonNull(blobs); Objects.requireNonNull(durableIntent); loadedProofs=list(loadedProofs);  }
}
