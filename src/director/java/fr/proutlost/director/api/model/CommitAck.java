package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record CommitAck(TransactionId transactionId, Epoch storeGeneration, Sequence durableSequence, Fingerprint transactionHash, AckStatus ackStatus, Optional<PermitAttempt> permitAttempt, Set<BlobRef> forcedBlobs) {
 public CommitAck { Objects.requireNonNull(transactionId); Objects.requireNonNull(storeGeneration); Objects.requireNonNull(durableSequence); Objects.requireNonNull(transactionHash); Objects.requireNonNull(ackStatus); Objects.requireNonNull(permitAttempt); forcedBlobs=set(forcedBlobs);  }
}
