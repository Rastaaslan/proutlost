package fr.proutlost.director.runtime;
import fr.proutlost.director.api.model.*;
import java.util.*;
/** An unavailable metadata ACK grants no progress. Retrying the exact immutable
 * batch through J04's existing transaction identity cannot duplicate a commit. */
public final class MetadataAppendAcknowledgement {
 private MetadataAppendAcknowledgement(){}
 public enum State { DURABLE, RETRY_SAME_BATCH, REFUSED }
 public static State classify(DurableBatch batch,CommitAck ack,StoreSession original,StoreSession current,boolean readOnly){
  Objects.requireNonNull(batch);Objects.requireNonNull(original);
  if(readOnly||!original.equals(current)||ack==null||!batch.expectedStoreGeneration().equals(original.storeGeneration())
   ||!ack.transactionId().equals(batch.transactionId())||!ack.storeGeneration().equals(batch.expectedStoreGeneration())
   ||!ack.transactionHash().equals(batch.transactionHash())||!ack.permitAttempt().equals(batch.permitAttempt()))return State.REFUSED;
  if(ack.ackStatus()==AckStatus.DURABLE)return ack.durableSequence().value()>0&&ack.forcedBlobs().equals(DurableBlobReferences.collect(batch.records()))?State.DURABLE:State.REFUSED;
  if(ack.ackStatus()==AckStatus.UNAVAILABLE&&ack.durableSequence().value()==0&&ack.forcedBlobs().isEmpty())return State.RETRY_SAME_BATCH;
  return State.REFUSED;
 }
}
