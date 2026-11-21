package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ResultRecord(TransactionId prepareTransactionId, OperationToken operationToken, SliceResult result, Set<MutationId> mutations, List<DirectorFact> facts, List<DirectorEnvelope> outbox) {
 public ResultRecord { Objects.requireNonNull(prepareTransactionId); Objects.requireNonNull(operationToken); Objects.requireNonNull(result); mutations=set(mutations); facts=list(facts); outbox=list(outbox);  }
}
