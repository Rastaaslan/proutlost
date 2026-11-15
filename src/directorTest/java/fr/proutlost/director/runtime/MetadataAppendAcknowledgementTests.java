package fr.proutlost.director.runtime;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.*;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
public final class MetadataAppendAcknowledgementTests {
 static int checks;static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception {
  var world=new WorldId(UUID.randomUUID());var dir=Files.createTempDirectory("metadata-same-transaction-");
  var store=JournalStore.open(dir,world,new StoreOptions(1048620,2097152,4194304,16777216,32,0,128)).toCompletableFuture().get(20,TimeUnit.SECONDS);
  var session=store.session();
  var fact=new DirectorFact(new FactId(UUID.randomUUID()),new FactType("fixture:metadata_ack"),List.of(new ScopeRef(ScopeKind.WORLD,world.value())),Optional.empty(),EvidenceStatus.CONFIRMED,Map.of(),true,Optional.empty());
  var batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),session.storeGeneration(),Map.of(),List.of(new DurableFact(fact)),List.of());
  var pressure=new CommitAck(batch.transactionId(),session.storeGeneration(),new Sequence(0),batch.transactionHash(),AckStatus.UNAVAILABLE,Optional.empty(),Set.of());
  check(MetadataAppendAcknowledgement.classify(batch,pressure,session,session,false)==MetadataAppendAcknowledgement.State.RETRY_SAME_BATCH,"healthy pressure preserves exact batch");
  check(MetadataAppendAcknowledgement.classify(batch,pressure,session,session,true)==MetadataAppendAcknowledgement.State.REFUSED,"writer loss never grants retry authority");
  var foreign=new CommitAck(new TransactionId(UUID.randomUUID()),session.storeGeneration(),new Sequence(0),batch.transactionHash(),AckStatus.UNAVAILABLE,Optional.empty(),Set.of());
  check(MetadataAppendAcknowledgement.classify(batch,foreign,session,session,false)==MetadataAppendAcknowledgement.State.REFUSED,"foreign unavailable ACK refused");
  var rejected=new CommitAck(batch.transactionId(),session.storeGeneration(),new Sequence(0),batch.transactionHash(),AckStatus.REJECTED,Optional.empty(),Set.of());
  check(MetadataAppendAcknowledgement.classify(batch,rejected,session,session,false)==MetadataAppendAcknowledgement.State.REFUSED,"true rejection never retried");
  var first=store.append(batch).toCompletableFuture().get(20,TimeUnit.SECONDS);
  check(MetadataAppendAcknowledgement.classify(batch,first,session,session,false)==MetadataAppendAcknowledgement.State.DURABLE,"real J04 ACK required");
  var again=store.append(batch).toCompletableFuture().get(20,TimeUnit.SECONDS);
  check(first.equals(again),"same immutable transaction after lost reply returns original durable ACK");
  var page=store.read(new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),128)).toCompletableFuture().get(20,TimeUnit.SECONDS);
  ReadContracts.validate(page,new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),128),session);
  check(page.status()==ReadStatus.AVAILABLE&&page.page().orElseThrow().records().stream().filter(x->x instanceof DurableFact f&&f.value().factId().equals(fact.factId())).count()==1,"actual journal contains one fact after exact retry");
  store.closeAsync().toCompletableFuture().get(20,TimeUnit.SECONDS);
  System.out.println("METADATA_APPEND_ACK_PASS assertions="+checks+" scope=ACTUAL_J04_IDEMPOTENCE_NOT_IO_FAILURE_SUITE");
 }
}
