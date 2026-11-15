package fr.proutlost.director.runtime;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

/** Actor-owned bounded mailbox. Audit and proposed state share one durable transaction.
 * No mutable world action is a handler: handlers propose data; committed publication is actor-only.
 * No eviction/reexecution: capacity exhaustion and unknown historical keys explicitly defer.
 */
public final class RuntimeAdminMailbox implements RuntimeAdminPort,ProviderProvenance {
 public record Change(AdminSnapshotStamp after,AdminResult result,Map<ScopeRef,Revision> expectedRevisions,List<DurableRecord> records,List<BlobContent> blobs){
  public Change {Objects.requireNonNull(after);Objects.requireNonNull(result);expectedRevisions=Map.copyOf(expectedRevisions);records=List.copyOf(records);blobs=List.copyOf(blobs);if(records.isEmpty()||result.status()!=AdminStatus.SUCCEEDED)throw new IllegalArgumentException("actual control changes required");}
 }
 public interface Handler {ProviderMode mode();Change propose(AdminCommand command,AdminSnapshotStamp before);}
 public static final class Rejected extends IllegalArgumentException {
  private final ReasonCode reason;
  public Rejected(ReasonCode reason){super(Objects.requireNonNull(reason).name());this.reason=reason;}
  public ReasonCode reason(){return reason;}
 }
 private record Delivery(CommitAck ack,Throwable failure){}
 private static final class Entry {
  final AdminCommand command;final Fingerprint hash;AdminResult result;Change change;DurableBatch batch;StoreSession session;
  final CompletableFuture<Delivery> mailbox=new CompletableFuture<>();
  Entry(AdminCommand command,Fingerprint hash,AdminResult result){this.command=command;this.hash=hash;this.result=result;}
 }
 private final Thread actor=Thread.currentThread();private final DurableStore store;private final ActorAuthorityPort authority;
 private final Supplier<AdminSnapshotStamp> current;private final Consumer<Change> publish;private final Map<AdminOperation,Handler> handlers;
 private final int capacity;private final ProviderMode mode;private final Map<RuntimeAdminKey,Entry> entries=new LinkedHashMap<>();
 private Entry active;private boolean uncertain;
 private BiConsumer<AdminCommand,Change> afterCommitted=(command,change)->{};private boolean afterCommittedBound;
 public RuntimeAdminMailbox(DurableStore store,ActorAuthorityPort authority,Supplier<AdminSnapshotStamp> current,Consumer<Change> publish,Map<AdminOperation,Handler> handlers,int capacity,ProviderMode mode){
  this.store=Objects.requireNonNull(store);this.authority=Objects.requireNonNull(authority);this.current=Objects.requireNonNull(current);this.publish=Objects.requireNonNull(publish);this.handlers=Map.copyOf(handlers);
  if(capacity<1)throw new IllegalArgumentException("capacity");this.capacity=capacity;
  this.mode=Objects.requireNonNull(mode);
  if(mode==ProviderMode.PRODUCTION&&handlers.values().stream().anyMatch(h->h.mode()!=ProviderMode.PRODUCTION))throw new IllegalArgumentException("TEST_ONLY admin handler");
 }
 public Set<ProviderMode> supportedModes(){return Set.of(mode);}
 private void actor(){if(Thread.currentThread()!=actor)throw new IllegalStateException("server actor required");}
 /** Actor-only notification for a newly committed command, after publication.
  * Historical restore, poll and idempotent resubmission never invoke it. */
 public void afterCommitted(BiConsumer<AdminCommand,Change> callback){
  actor();if(afterCommittedBound)throw new IllegalStateException("admin commit callback already bound");
  afterCommitted=Objects.requireNonNull(callback);afterCommittedBound=true;
 }
 private AdminResult result(RequestId request,AdminStatus status,ReasonCode reason){return new AdminResult(status,request,current.get().snapshotRevision(),Optional.empty(),reason==null?Optional.empty():Optional.of(new SystemError(reason,Severity.WARNING,status==AdminStatus.DEFERRED,Optional.empty(),Set.of(),new CorrelationId(request.value()),SafeState.UNCHANGED)));}
 private Optional<ActorIdentity> authorized(AdminCommand command){return authority.current(command.actorSession()).filter(a->AdminContracts.authorized(command,a));}
 public AdminResult submit(AdminCommand command){
  actor();var key=new RuntimeAdminKey(command.actorSession(),command.requestId());var hash=BlobContent.hash(new ModelCodec<>(AdminCommand.class).encode(command));var old=entries.get(key);
  if(old!=null)return old.hash.equals(hash)?old.result:result(command.requestId(),AdminStatus.REJECTED,ReasonCode.IDEMPOTENCY_CONFLICT);
  if(authorized(command).isEmpty())return result(command.requestId(),AdminStatus.REJECTED,ReasonCode.AUTH_DENIED);
  if(uncertain||entries.size()>=capacity)return result(command.requestId(),AdminStatus.DEFERRED,ReasonCode.WAL_UNAVAILABLE);
  if(!handlers.containsKey(command.operation()))return result(command.requestId(),AdminStatus.REJECTED,ReasonCode.CAPABILITY_MISSING);
  var entry=new Entry(command,hash,result(command.requestId(),AdminStatus.PENDING,null));entries.put(key,entry);return entry.result;
 }
 public AdminResult poll(RuntimeAdminKey key){actor();var e=entries.get(key);return e==null?result(key.requestId(),AdminStatus.DEFERRED,ReasonCode.STALE_CONTEXT):e.result;}
 public boolean hasPendingWrites(){actor();return active!=null;}
 public boolean owns(RuntimeAdminKey key){actor();return entries.containsKey(key);}
 public boolean handles(AdminOperation operation){actor();return handlers.containsKey(operation);}
 /** One bounded application per actor pump, independently of submit/poll callers. */
 public void pump(){
  actor();if(uncertain)return;
  if(active!=null){
   var e=active;var delivery=e.mailbox.getNow(null);if(delivery==null)return;
   var ack=delivery.ack();boolean valid=delivery.failure()==null&&ack!=null&&ack.ackStatus()==AckStatus.DURABLE&&e.session.equals(store.session())
     &&ack.transactionId().equals(e.batch.transactionId())&&ack.transactionHash().equals(e.batch.transactionHash())&&ack.storeGeneration().equals(e.session.storeGeneration())
     &&ack.durableSequence().value()>0&&ack.permitAttempt().isEmpty()&&ack.forcedBlobs().equals(DurableBlobReferences.collect(e.batch.records()));
   if(!valid){uncertain=true;e.result=result(e.command.requestId(),AdminStatus.DEFERRED,ReasonCode.WAL_UNAVAILABLE);return;}
   try{
    var stamp=e.change.after();
    var committed=new AdminSnapshotStamp(stamp.worldId(),stamp.bootEpoch(),stamp.snapshotRevision(),stamp.capturedTick(),ack.durableSequence());
    var published=new Change(committed,e.change.result(),e.change.expectedRevisions(),e.change.records(),e.change.blobs());
    publish.accept(published);e.result=e.change.result();active=null;
    // Clear the active delivery before notifying: a callback failure is fenced,
    // never retried as another command application or another notification.
    afterCommitted.accept(e.command,published);
   }catch(RuntimeException failure){uncertain=true;e.result=result(e.command.requestId(),AdminStatus.DEFERRED,ReasonCode.RECOVERY_AMBIGUOUS);}
   return;
  }
  var candidate=entries.values().stream().filter(e->e.result.status()==AdminStatus.PENDING).findFirst();if(candidate.isEmpty())return;
  var e=candidate.orElseThrow();var identity=authorized(e.command);var before=current.get();
  if(identity.isEmpty()){e.result=result(e.command.requestId(),AdminStatus.REJECTED,ReasonCode.AUTH_DENIED);return;}
  if(!e.command.expectedRevision().equals(before.snapshotRevision())){e.result=result(e.command.requestId(),AdminStatus.DEFERRED,ReasonCode.STALE_CONTEXT);return;}
  try{
   var change=handlers.get(e.command.operation()).propose(e.command,before);
   if(!change.after().worldId().equals(before.worldId())||!change.after().bootEpoch().equals(before.bootEpoch())||change.after().snapshotRevision().compareTo(before.snapshotRevision())<=0
      ||!change.result().requestId().equals(e.command.requestId())||!change.result().revision().equals(change.after().snapshotRevision())
      ||change.records().stream().anyMatch(r->r instanceof DurableRuntimeAdminAudit||r instanceof DurablePrepare))throw new IllegalArgumentException("admin proposal binding");
   var audit=new RuntimeAdminAudit(e.command,e.hash,identity.orElseThrow(),before,change.after(),change.result());
   var records=new ArrayList<DurableRecord>(change.records());records.add(new DurableRuntimeAdminAudit(audit));
   var transaction=new TransactionId(UUID.nameUUIDFromBytes((before.worldId().value()+":"+e.command.actorSession().value()+":"+e.command.requestId().value()).getBytes(StandardCharsets.UTF_8)));
   e.session=store.session();e.change=change;e.batch=ResultTransaction.batch(transaction,e.session.storeGeneration(),change.expectedRevisions(),records,change.blobs());active=e;
   try{store.append(e.batch).whenComplete((ack,error)->e.mailbox.complete(new Delivery(ack,error)));}catch(RuntimeException failure){e.mailbox.complete(new Delivery(null,failure));}
  }catch(Rejected rejected){e.result=result(e.command.requestId(),AdminStatus.REJECTED,rejected.reason());}
  catch(RuntimeException invalid){e.result=result(e.command.requestId(),AdminStatus.REJECTED,ReasonCode.INTERNAL_FAULT);}
 }
 /** Actor work remains authoritative even if a caller's result future was revoked. */
 public boolean hasWork(){
  actor();return uncertain||active!=null||entries.values().stream().anyMatch(e->e.result.status()==AdminStatus.PENDING);
 }
 /** A queued command has no irreversible write yet. Revoke it immediately,
  * independently of WORLD-writer availability, so later re-OP cannot revive it. */
 public void rejectRevokedQueued(){
  actor();for(var e:entries.values())if(e!=active&&e.result.status()==AdminStatus.PENDING&&authorized(e.command).isEmpty())
   e.result=result(e.command.requestId(),AdminStatus.REJECTED,ReasonCode.AUTH_DENIED);
 }
 /** Restore terminal durable audits before accepting requests after reboot. */
 public void restore(RuntimeAdminAudit audit){
  actor();if(entries.size()>=capacity)throw new IllegalStateException("audit capacity");var key=new RuntimeAdminKey(audit.command().actorSession(),audit.command().requestId());
  if(entries.putIfAbsent(key,new Entry(audit.command(),audit.commandHash(),audit.result()))!=null)throw new IllegalArgumentException("duplicate audit");
 }
}
