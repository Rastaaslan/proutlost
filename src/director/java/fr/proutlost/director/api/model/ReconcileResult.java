package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ReconcileResult(ReconcileStatus status, List<RecoveryEvidence> proofs, List<SystemError> errors) {
 public ReconcileResult { Objects.requireNonNull(status); proofs=list(proofs); errors=list(errors);  }
}
