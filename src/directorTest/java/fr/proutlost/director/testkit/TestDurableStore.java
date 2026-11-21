package fr.proutlost.director.testkit;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.bootstrap.DirectorBootstrap.TestOnlyBackend;
import java.util.*;
import java.util.concurrent.*;
/** TEST_ONLY in-memory durable-ledger oracle; explicitly no disk durability claim.
 * Ledger survives simulated reboot; pending deliveries do not. Real persistence belongs to J04.
 */
public final class TestDurableStore implements DurableStore,TestOnlyBackend {
 public static final class Ledger {
  private final Map<TransactionId,CommitAck> transactions=new HashMap<>();
  private final Set<OperationClaim> claims=new HashSet<>();
  private final Map<BlobRef,BlobContent> blobs=new HashMap<>();private long sequence;
 }
 private record OperationClaim(WorldId world,ReservationId reservation,Epoch generation,OperationToken operation) {}
 private final Ledger ledger;private StoreSession session;
 private final Map<TransactionId,CompletableFuture<CommitAck>> pending=new HashMap<>();private final Map<TransactionId,DurableBatch> submissions=new HashMap<>();
 public TestDurableStore(){this(new Ledger(),new StoreSession(new WorldId(new UUID(0,1)),new Epoch(7),new Epoch(1),new SessionId(UUID.randomUUID())));}
 public TestDurableStore(Ledger ledger,StoreSession session){this.ledger=ledger;this.session=session;}
 public Ledger ledger(){return ledger;}
 public StoreSession session(){return session;}
 public void reboot(){session=new StoreSession(session.worldId(),session.storeGeneration(),session.bootEpoch().plus(1),new SessionId(UUID.randomUUID()));pending.values().forEach(f->f.completeExceptionally(new IllegalStateException("TEST_ONLY reboot")));pending.clear();submissions.clear();}
 public DurableBatch submitted(TransactionId id){return submissions.get(id);}
 private boolean writerLost;
 public void loseWriter(){writerLost=true;}
 public CompletionStage<CommitAck> append(DurableBatch batch){
  if(writerLost)return CompletableFuture.failedFuture(new IllegalStateException("TEST_ONLY writer lost"));
  if(batch.permitAttempt().isPresent()&&!batch.permitAttempt().orElseThrow().session().equals(session))return CompletableFuture.completedFuture(rejected(batch));
  if(ledger.transactions.containsKey(batch.transactionId()))return CompletableFuture.completedFuture(ledger.transactions.get(batch.transactionId()));
  var future=new CompletableFuture<CommitAck>();if(pending.putIfAbsent(batch.transactionId(),future)!=null)throw new IllegalArgumentException("pending transaction");submissions.put(batch.transactionId(),batch);return future;
 }
 private CommitAck rejected(DurableBatch b){return new CommitAck(b.transactionId(),session.storeGeneration(),new Sequence(0),b.transactionHash(),AckStatus.REJECTED,b.permitAttempt(),Set.of());}
 public CommitAck force(TransactionId id){
  DurableBatch b=submissions.get(id);PermitAttempt a=b.permitAttempt().orElseThrow();var claim=new OperationClaim(a.session().worldId(),a.reservationId(),a.reservationGeneration(),a.operationToken());
  if(ledger.claims.contains(claim)||!a.session().equals(session)){var ack=rejected(b);acknowledge(ack);return ack;}
  for(var blob:b.blobs())ledger.blobs.put(blob.reference(),blob);
  var prepare=((DurablePrepare)b.records().getFirst()).value();var refs=CommitPermit.Authority.references(prepare.durableBlobs());
  if(!ledger.blobs.keySet().containsAll(refs)){var ack=rejected(b);acknowledge(ack);return ack;}
  ledger.claims.add(claim);var ack=new CommitAck(id,session.storeGeneration(),new Sequence(++ledger.sequence),b.transactionHash(),AckStatus.DURABLE,b.permitAttempt(),refs);ledger.transactions.put(id,ack);acknowledge(ack);return ack;
 }
 /** Deliberately permits corrupt/replayed ACK injection to test the Authority boundary. */
 public void acknowledge(CommitAck ack){var f=pending.remove(ack.transactionId());submissions.remove(ack.transactionId());if(f!=null)f.complete(ack);}
 public void fail(TransactionId id){var f=pending.remove(id);submissions.remove(id);f.completeExceptionally(new IllegalStateException("TEST_ONLY injected IO fault"));}
 public StoreReadResult view(StoreQuery query){return new StoreReadResult(ReadStatus.NOT_RESIDENT,Optional.empty(),Optional.empty(),List.of());}
 /** This permit oracle has no archive reader. Never certify absence from its missing projection. */
 public CompletionStage<StoreReadResult> read(StoreQuery query){return CompletableFuture.completedFuture(new StoreReadResult(ReadStatus.UNAVAILABLE,Optional.empty(),Optional.empty(),List.of()));}
 public CompletionStage<BlobReadResult> readBlobs(BlobReadRequest request){if(!request.worldId().equals(session.worldId())||!request.storeGeneration().equals(session.storeGeneration())||!ledger.blobs.keySet().containsAll(request.references()))return CompletableFuture.completedFuture(new BlobReadResult(AckStatus.UNAVAILABLE,List.of(),List.of()));return CompletableFuture.completedFuture(new BlobReadResult(AckStatus.DURABLE,request.references().stream().map(ledger.blobs::get).toList(),List.of()));}
}
