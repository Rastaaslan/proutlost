package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DurableBatch(TransactionId transactionId, Epoch expectedStoreGeneration, Map<ScopeRef,Revision> expectedAggregateRevisions, List<DurableRecord> records, List<BlobContent> blobs, Optional<PermitAttempt> permitAttempt, Fingerprint transactionHash) {
 public DurableBatch { Objects.requireNonNull(transactionId); Objects.requireNonNull(expectedStoreGeneration); expectedAggregateRevisions=map(expectedAggregateRevisions); records=list(records); blobs=list(blobs); Objects.requireNonNull(permitAttempt); Objects.requireNonNull(transactionHash); require(blobs.size()<=256,"blob count"); require(blobs.stream().mapToLong(b->b.reference().bytes()).sum()<=BlobContent.MAX_BATCH_BYTES,"blob batch bound"); require(blobs.stream().map(BlobContent::reference).distinct().count()==blobs.size(),"duplicate blob"); }
}
