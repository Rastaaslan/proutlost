package fr.proutlost.director.api.permission;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.api.error.ReasonCode;
import java.util.*;
import java.util.concurrent.*;
/** Opaque one-slice authority. Never serializable or reconstructible from persisted DTOs. */
public final class CommitPermit {
 private final PermitId permitId=new PermitId(UUID.randomUUID());
 private final PrepareRecord prepare;private final Sequence prepareSequence;private final Authority issuer;
 private boolean used;
 private final Optional<StabilizationPermit> stabilization;
 private CommitPermit(Authority issuer,PrepareRecord prepare,Sequence sequence){this.issuer=issuer;this.prepare=prepare;prepareSequence=sequence;stabilization=prepare.stabilizationPlan().map(plan->new StabilizationPermit(this,plan));}
 /** Issued with the forced PREPARE; retained independently of impact consumption/expiry. */
 public Optional<StabilizationPermit> stabilizationPermit(){issuer.checkThread();return stabilization;}
 /** Opaque bounded stop-only authority; never decode, reconstruct or use for ordinary effects.
  * A successful claim permits exactly one immediate actor-thread attempt. The adapter must write
  * the returned marker locally and retain actual outcome evidence; exceptions are ambiguous, not
  * retry authorization. Reboot/session change invalidates this object and requires reconciliation.
  */
 public static final class StabilizationPermit {
  private final CommitPermit original;private final StabilizationPlan plan;private final Set<SliceId> attempted=new HashSet<>();
  private StabilizationPermit(CommitPermit original,StabilizationPlan plan){this.original=original;this.plan=plan;}
  public StabilizationMarker consume(Authority expectedIssuer,PreparedSlice requested,StabilizationLiveSnapshot live){
   Objects.requireNonNull(expectedIssuer);expectedIssuer.checkThread();
   Checks.require(original.issuer==expectedIssuer&&expectedIssuer.current(),"foreign issuer or obsolete session");
   Checks.require(expectedIssuer.session.equals(live.session()),"stop session binding");
   Checks.require(original.prepare.reservationGeneration().equals(live.validation().reservationGeneration()),"stale stop reservation");
   var stop=plan.slices().stream().filter(s->s.slice().equals(requested)).findFirst().orElseThrow(()->new IllegalArgumentException("stop substitution or footprint expansion"));
   Checks.require(!attempted.contains(requested.sliceId()),"stop already attempted; reconcile local marker");
   Checks.require(live.validation().readSet().equals(requested.preconditions()),"stale stop epoch/hash/revision");
   Checks.require(live.validation().loadedChunks().containsAll(requested.footprint().loadedChunks()),"unknown stop chunks");
   for(var e:stop.expectedOwnership().entrySet())Checks.require(e.getValue().equals(live.ownership().records().get(e.getKey())),"stop ownership changed or ceded");
   // Health/impact expiry deliberately do not veto an already forced stop; no writer call here.
   attempted.add(requested.sliceId());
   return new StabilizationMarker(expectedIssuer.session,original.prepareSequence,original.prepare.operationToken(),requested.sliceId(),requested.operationToken(),original.prepare.durableBlobs().stopPlan());
  }
 }
 public PermitId permitId(){return permitId;}
 public Epoch reservationGeneration(){return prepare.reservationGeneration();}
 public Sequence prepareSequence(){return prepareSequence;}
 public Tick expiryTick(){return prepare.expiryTick();}
 /** Adapter must supply its bootstrap's trusted Authority, not an issuer taken from the permit. */
 public void consume(Authority expectedIssuer,PreparedSlice requested,LiveValidationSnapshot live){
  Objects.requireNonNull(expectedIssuer);expectedIssuer.checkThread();Checks.require(issuer==expectedIssuer,"foreign permit issuer");
  Checks.require(!used&&issuer.current(),"used permit or obsolete store session");
  issuer.validate(prepare.slice(),prepare.expiryTick(),prepare.reservationGeneration(),live);
  Checks.require(prepare.slice().equals(requested),"different slice");used=true;
 }
 public static final class Authority {
  private final Thread actor=Thread.currentThread();private final DurableStore store;private final StoreSession session;
  private final Set<ProviderMode> supportedModes;
  private final Map<TransactionId,Pending> pending=new HashMap<>();private final int capacity;
  private long nextAttempt;
  private record Delivery(CommitAck ack,boolean failed) {}
  private record Pending(DurableBatch batch,PrepareRecord prepare,PermitAttempt attempt,CompletableFuture<Delivery> mailbox) {}
  /** One authority per exclusive session published by the trusted durable writer. */
  public Authority(DurableStore store,int capacity){this.store=Objects.requireNonNull(store);session=Objects.requireNonNull(store.session());Checks.require(capacity>0&&capacity<=65536,"pending bound");this.capacity=capacity;
   supportedModes=store instanceof fr.proutlost.director.api.port.ProviderProvenance provenance
    ?Set.copyOf(Objects.requireNonNull(provenance.supportedModes(),"store modes")):Set.of(ProviderMode.TEST_ONLY);
   Checks.require(!supportedModes.isEmpty(),"store mode set");
  }
  private void checkThread(){if(Thread.currentThread()!=actor)throw new IllegalStateException("server actor required");}
  private boolean current(){return session.equals(store.session());}
  public StoreSession session(){return session;}
  /** Immutable backend identity only; never exposes the writer or grants a permit. */
  public Set<ProviderMode> supportedModes(){return supportedModes;}
  public int inFlight(){checkThread();return pending.size();}
  private void validate(PreparedSlice slice,Tick expiry,Epoch generation,LiveValidationSnapshot live){
   Checks.require(current(),"obsolete store session");Checks.require(live.now().compareTo(expiry)<0,"expired permit");Checks.require(generation.equals(live.reservationGeneration()),"stale reservation");
   Checks.require(live.health()==HealthMode.NORMAL||live.health()==HealthMode.DEGRADED,"unsafe health");
   Checks.require(live.readSet().equals(slice.preconditions()),"stale preconditions");Checks.require(live.loadedChunks().containsAll(slice.footprint().loadedChunks()),"unloaded footprint");
  }
  /** Stale pending callbacks become unreachable; durable claims remain owned by store recovery. */
  public void expire(Tick now){checkThread();pending.entrySet().removeIf(e->!current()||e.getValue().prepare().expiryTick().compareTo(now)<=0);}
  public void submit(DurableBatch batch,LiveValidationSnapshot live){
   checkThread();expire(live.now());Checks.require(pending.size()<capacity&&!pending.containsKey(batch.transactionId()),"pending capacity/transaction");
   Checks.require(batch.permitAttempt().isEmpty(),"authority assigns attempt");
   Checks.require(batch.records().size()==1&&batch.records().getFirst() instanceof DurablePrepare,"one PREPARE required");
   PrepareRecord prepare=((DurablePrepare)batch.records().getFirst()).value();Checks.require(prepare.transactionId().equals(batch.transactionId()),"transaction mismatch");
   Checks.require(prepare.reservation().worldId().equals(session.worldId())&&batch.expectedStoreGeneration().equals(session.storeGeneration()),"store binding");
   Checks.require(prepare.hardValidation().valid()&&prepare.readSet().equals(prepare.slice().preconditions())&&batch.expectedAggregateRevisions().equals(prepare.readSet().revisions()),"prepare read set");
   Checks.require(batch.transactionHash().equals(prepareHash(prepare)),"prepare hash");
   validate(prepare.slice(),prepare.expiryTick(),prepare.reservationGeneration(),live);
   PermitAttempt attempt=new PermitAttempt(session,new Sequence(Math.incrementExact(nextAttempt)),prepare.operationToken(),prepare.reservation().id(),prepare.reservationGeneration());nextAttempt=attempt.attemptSequence().value();
   DurableBatch submission=new DurableBatch(batch.transactionId(),batch.expectedStoreGeneration(),batch.expectedAggregateRevisions(),batch.records(),batch.blobs(),Optional.of(attempt),batch.transactionHash());
   var mailbox=new CompletableFuture<Delivery>();pending.put(batch.transactionId(),new Pending(submission,prepare,attempt,mailbox));
   try{store.append(submission).whenComplete((ack,error)->mailbox.complete(new Delivery(ack,error!=null)));}catch(RuntimeException e){mailbox.complete(new Delivery(null,true));}
  }
  /** getNow reads an ACK mailbox; it never waits or executes effects. No join/get on actor. */
  public PermitResult poll(TransactionId id,LiveValidationSnapshot live){
   checkThread();Pending p=pending.get(id);if(p==null)return PermitResult.rejected(ReasonCode.STALE_CONTEXT);
   if(!current()||live.now().compareTo(p.prepare().expiryTick())>=0){pending.remove(id);return PermitResult.rejected(ReasonCode.STALE_CONTEXT);}
   Delivery delivery=p.mailbox().getNow(null);if(delivery==null)return PermitResult.pending();pending.remove(id);
   CommitAck ack=delivery.ack();if(delivery.failed()||ack==null||ack.ackStatus()!=AckStatus.DURABLE){
    System.err.println("DIRECTOR_PREPARE_ACK_REFUSED tx="+id.value()+" actual_status="+(ack==null?"NO_ACK":ack.ackStatus())+" forced_blobs="+(ack==null?List.of():ack.forcedBlobs())+" session_current="+current());
    return PermitResult.rejected(ReasonCode.WAL_UNAVAILABLE);
   }
   if(!ack.transactionId().equals(id)||!ack.transactionHash().equals(p.batch().transactionHash())||!ack.storeGeneration().equals(session.storeGeneration())||ack.durableSequence().value()==0||!ack.permitAttempt().equals(Optional.of(p.attempt())))return PermitResult.rejected(ReasonCode.STALE_CONTEXT);
   if(!ack.forcedBlobs().containsAll(references(p.prepare().durableBlobs())))return PermitResult.rejected(ReasonCode.WAL_UNAVAILABLE);
   try{validate(p.prepare().slice(),p.prepare().expiryTick(),p.prepare().reservationGeneration(),live);}catch(IllegalArgumentException stale){return PermitResult.rejected(ReasonCode.STALE_CONTEXT);}
   return PermitResult.granted(new CommitPermit(this,p.prepare(),ack.durableSequence()));
  }
  public static Set<BlobRef> references(EffectBlobs b){Set<BlobRef> refs=new HashSet<>(b.snapshots());refs.add(b.preparedPlan());refs.add(b.stopPlan());refs.addAll(b.preStates().values());refs.addAll(b.expectedPostStates().values());return Set.copyOf(refs);}
  public static Fingerprint prepareHash(PrepareRecord prepare){return BlobContent.hash(new fr.proutlost.director.api.codec.ModelCodec<>(PrepareRecord.class).encode(prepare));}
 }
}
