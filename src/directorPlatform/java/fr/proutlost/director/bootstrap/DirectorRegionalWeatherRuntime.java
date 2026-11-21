package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.effects.weathercore.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.model.RegionalWeatherJournal.Request;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Bounded regional lease coordinator inside the supplied J04 actor.
 * It never opens another store, creates an event, invents approval, or replays a birth.
 * Production reservations must come from the caller's existing global event arbiter.
 */
public final class DirectorRegionalWeatherRuntime implements AutoCloseable {
 public enum Stage { QUEUED,OWNER_PENDING,CAPTURE_PENDING,WAITING_ENVELOPE,EXECUTING,ACTIVE,SUSPENDED,REARMING,CLEANUP,END_PENDING,RECOVERING,COMPLETE,RECOVERY_REQUIRED }
 public record Reservation(ResourceArbiter arbiter,ReservationToken token){
  public Reservation{Objects.requireNonNull(arbiter);Objects.requireNonNull(token);}
 }
 public interface AdmissionGuard {
  ProviderMode mode();
  Set<CapabilityId> qualifiedCapabilities();
  default Set<CapabilityId> cleanupCapabilities(){return qualifiedCapabilities();}
  default List<UniqueUseRecord> uniqueness(Request request,ReservationToken token,boolean cleanup,boolean rearm){return List.of();}
  default CatalogueEventLedger.Contribution effectResult(Request request,PrepareRecord prepare,SliceResult actual,boolean cleanup,boolean rearm){return new CatalogueEventLedger.Contribution(List.of(),List.of());}
  default Epoch initialOwnerEpoch(Request request){return new Epoch(1);}
  default fr.proutlost.director.api.port.WorldEffectPort effectPort(PreparedEffect plan,BlobBundle blobs,ServerEffectPort actual,boolean cleanup){return actual;}
  boolean mayStart(Request request);
  boolean mayApply(Request request);
  boolean mayResume(Request request);
  default boolean mayAwaitResume(Request request){return false;}
  boolean available(Request request);
  /** Actual current J04 REGION/profile/scope head, not a memory approximation. */
  Revision regionalRevision(Request request);
  /** Actor-captured live health/loaded chunks/revisions/budget; no default NORMAL. */
  PlanningSnapshot capture(Request request,OwnershipView exactOwnedState);
  /** Current active-profile ceiling plus real existing global event reservation.
   * Empty is WAIT, never permission to allocate a private production arbiter. */
  Optional<Reservation> reservation(Request request,PreparedEffect captured,boolean cleanup);
  Tick clock();
  boolean acquireMetadataLane(Object owner);
  void releaseMetadataLane(Object owner);
 }
 public record Receipt(EventInstanceId event,Stage stage,Optional<UUID> nativeUuid,long nativeDeadline,
    boolean restoredFromCold,int forwardInvocationsThisBoot,int rearmInvocationsThisBoot,
    boolean uncertainHistoricalOutcome,boolean nativeBirthConfirmed,String diagnostic){}
 private final MinecraftServer server;private final WorldId world;private final JournalStore store;private final StoreSession session;
 private final AdmissionGuard guard;private final int capacity;
 private final Map<EventInstanceId,Flight> flights=new LinkedHashMap<>();
 private final Map<EventInstanceId,RegionalWeatherJournal.State> journalHeads=new LinkedHashMap<>();
 private final Map<ResourceKey,OwnershipRecord> owners=new HashMap<>();
 private final Map<OperationToken,PrepareRecord> originalPrepares=new HashMap<>();
 private final Map<OperationToken,ResultRecord> originalResults=new HashMap<>();
 private final WeatherCoreRegionalBinding binding;
 private final Object recoveryLane=new Object();private boolean recoveryHeld,recovered,closed,failed;
 private StoreQuery scanQuery;private CompletableFuture<StoreReadResult> scan;private Optional<String> cursor=Optional.empty();
 private int scanRestarts;private String diagnostic="RECOVERING";
 private static final class Tx {DurableBatch batch;CompletableFuture<CommitAck> ack;Stage after;}
 private static final class Work {
  EffectPreparation captured;PreparedEffect plan;Reservation reservation;EventRunner runner;PrepareRecord prepare;
  CommitPermit.Authority authority;ServerEffectPort port;LiveValidationSnapshot live;boolean cleanup,rearm,invoked;
 }
 private static final class Flight {
  Request request;OwnershipRecord owner;RegionalWeatherJournal.State head;Stage stage=Stage.QUEUED;
  EffectPreparation forward,captured;Work work;Tx tx;boolean stop,cold,uncertain,oldResultMissing;
  boolean lane,birthConfirmed;final ArrayDeque<PrepareRecord> missingResults=new ArrayDeque<>();int births,rearms;String diagnostic="QUEUED";long deadline;UUID nativeUuid;
 }
 public DirectorRegionalWeatherRuntime(MinecraftServer server,WorldId world,JournalStore shared,AdmissionGuard guard,int capacity){
  this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.store=Objects.requireNonNull(shared);this.guard=Objects.requireNonNull(guard);
  if(capacity<1||capacity>4)throw new IllegalArgumentException("regional coordinator capacity");this.capacity=capacity;
  actor();session=store.session();require(world.equals(session.worldId())&&!store.readOnlySafe(),"current shared regional J04");
  binding=WeatherCoreIntegration.createRegionalBinding(d->level(d),()->new OwnershipView(owners),k->{
   var f=flights.values().stream().filter(x->key(x).equals(k)).findFirst().orElseThrow(()->new IllegalStateException("unknown regional owner"));
   return guard.regionalRevision(f.request);
  },capacity);
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("regional coordinator server actor");}
 private static void require(boolean v,String why){if(!v)throw new IllegalStateException(why);}
 private ServerLevel level(DimensionId id){
  for(var l:server.getAllLevels())if(l.dimension().location().toString().equals(id.value()))return l;
  throw new IllegalStateException("regional actual dimension unavailable");
 }
 private ResourceKey key(Flight f){return WeatherCoreRegionalBinding.resource(f.request.footprint().dimension(),f.request.event());}
 private boolean current(){return session.equals(store.session())&&!store.readOnlySafe();}
 private void qualify(boolean cleanup){require((cleanup?guard.cleanupCapabilities():guard.qualifiedCapabilities()).contains(WeatherCoreRegionalBinding.CAPABILITY),"regional capability not independently qualified");}
 public void submit(Request request){
  actor();Objects.requireNonNull(request);var old=flights.get(request.event());
  if(old!=null){require(old.request.equals(request),"regional event request changed");return;}
  require(!journalHeads.containsKey(request.event()),"regional event already journaled; no renewed birth");
  require(recovered&&!failed&&current()&&flights.values().stream().filter(f->f.stage!=Stage.COMPLETE).count()<capacity,"regional recovery/capacity");
  var f=new Flight();f.request=request;flights.put(request.event(),f);
 }
 public void stop(EventInstanceId event){actor();var f=flights.get(event);if(f!=null)f.stop=true;}
 public void stopAll(){actor();flights.values().forEach(f->f.stop=true);}
 public boolean recovering(){actor();return !recovered;}
 public boolean metadataBusy(){actor();return recoveryHeld||flights.values().stream().anyMatch(f->f.lane);}
 public boolean hasActive(){actor();return flights.values().stream().anyMatch(f->f.stage!=Stage.COMPLETE);}
 public Optional<Receipt> receipt(EventInstanceId event){
  actor();var f=flights.get(event);return f==null?Optional.empty():Optional.of(new Receipt(event,f.stage,Optional.ofNullable(f.nativeUuid),f.deadline,f.cold,f.births,f.rearms,f.uncertain,f.birthConfirmed,f.diagnostic));
 }
 public List<Request> requests(){actor();return flights.values().stream().map(f->f.request).toList();}
 public List<Request> activeRequests(){actor();return flights.values().stream().filter(f->f.stage!=Stage.COMPLETE).map(f->f.request).toList();}
 public Optional<PreparedEffect> capturedForward(EventInstanceId event){actor();var f=flights.get(event);return f==null||f.forward==null?Optional.empty():f.forward.prepared();}
 private boolean acquire(Flight f){if(f.lane)return true;f.lane=guard.acquireMetadataLane(f);return f.lane;}
 private void release(Flight f){if(f.lane){guard.releaseMetadataLane(f);f.lane=false;}}
 private boolean currentOwner(Flight f){
  if(f.owner==null)return true;
  var query=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.OWNERSHIP,f.owner.header().id())),new Sequence(0),Optional.empty(),1);
  var read=store.view(query);ReadContracts.validate(read,query,session);
  return read.status()==ReadStatus.AVAILABLE&&!read.page().orElseThrow().hasMore()&&read.page().orElseThrow().aggregates().size()==1
    &&read.page().orElseThrow().aggregates().getFirst().state() instanceof OwnershipAggregate actual&&actual.value().equals(f.owner);
 }
 private PlanningSnapshot live(Flight f){
  require(currentOwner(f),"actual current J04 owner unavailable/changed; preserve regional guard");
  var p=Objects.requireNonNull(guard.capture(f.request,new OwnershipView(owners)));
  require(p.snapshot().scopes().worldId().equals(world),"regional live world");
  if(f.owner!=null)require(f.owner.equals(p.ownership().records().get(key(f))),"regional actual owned projection differs");
  return p;
 }
 private RegionalWeatherJournal.State state(Flight f,boolean complete,String why){
  var forward=f.forward==null?Optional.<PreparedEffect>empty():f.forward.prepared();
  return new RegionalWeatherJournal.State(1,f.head==null?1:Math.addExact(f.head.ordinal(),1),f.request,f.owner,forward,
    f.forward==null?List.of():RegionalWeatherJournal.seal(f.forward.blobs()),f.uncertain,complete,why);
 }
 private void persist(Flight f,Stage after,List<DurableRecord> extra,boolean complete,String why){
  require(f.tx==null&&f.lane,"regional sole metadata lane");
  var s=state(f,complete,why);var rows=new ArrayList<DurableRecord>(extra);rows.add(new DurableFact(RegionalWeatherJournal.fact(world,s)));
  var bytes=f.forward==null?List.<BlobContent>of():f.forward.blobs().contents();
  var tx=new Tx();tx.batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),session.storeGeneration(),Map.of(),rows,bytes);
  tx.after=after;tx.ack=store.append(tx.batch).toCompletableFuture();f.tx=tx;f.head=s;f.diagnostic=why;
 }
 private boolean metadata(Flight f){
  var tx=f.tx;if(tx==null)return true;if(!tx.ack.isDone())return false;
  var ack=tx.ack.getNow(null);
  require(current()&&ack!=null&&ack.ackStatus()==AckStatus.DURABLE&&ack.transactionId().equals(tx.batch.transactionId())
    &&ack.transactionHash().equals(tx.batch.transactionHash())&&ack.storeGeneration().equals(session.storeGeneration())
    &&ack.durableSequence().value()>0&&ack.permitAttempt().isEmpty()&&ack.forcedBlobs().equals(DurableBlobReferences.collect(tx.batch.records())),"regional exact metadata ACK");
  var exact=store.retainedFact(RegionalWeatherJournal.id(f.head)).orElseThrow(()->new IllegalStateException("regional actual J04 fact absent"));
  require(RegionalWeatherJournal.state(exact).equals(f.head),"regional J04 metadata readback differs");
  journalHeads.put(f.request.event(),f.head);f.stage=tx.after;f.tx=null;release(f);return true;
 }
 private void initialize(Flight f){
  qualify(false);if(f.stop){f.stage=Stage.COMPLETE;f.diagnostic="CANCELLED_BEFORE_OWNER";return;}
  if(!guard.mayStart(f.request)||!guard.available(f.request)||!acquire(f))return;
  var actual=binding.observedState(f.request.footprint().dimension(),f.request.event());
  require(actual.state().kind()==WeatherCoreRegionalState.Kind.ABSENT,"regional birth would replace existing weather");
  f.owner=new OwnershipRecord(new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(0),guard.clock()),key(f),
    Set.of(new OwnerToken(UUID.randomUUID())),guard.initialOwnerEpoch(f.request),OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),
    binding.observedFingerprint(f.request.footprint().dimension(),f.request.event()),new Epoch(0),Optional.empty());
  owners.put(key(f),f.owner);persist(f,Stage.WAITING_ENVELOPE,List.of(new DurableOwnership(f.owner)),false,"OWNER_DURABLE_REQUIRED");
 }
 private void captureForward(Flight f){
  if(f.stop){finish(f,"STOPPED_BEFORE_PREPARE",false);return;}
  if(!guard.mayApply(f.request)||!guard.available(f.request)){f.stage=Stage.SUSPENDED;return;}
  qualify(false);if(!acquire(f))return;
  var i=WeatherCoreRegionalBinding.request(f.request.operation(),f.request.event(),f.request.footprint(),f.request.duration(),guard.regionalRevision(f.request));
  f.forward=binding.capture(i,live(f));f.captured=f.forward;
  var post=WeatherCoreRegionalWitness.read(f.forward.blobs().contents().stream().filter(b->b.reference().equals(f.forward.prepared().orElseThrow().durableBlobs().preparedPlan())).findFirst().orElseThrow().bytes()).state();
  f.nativeUuid=post.weather();f.deadline=post.deadline();
  persist(f,Stage.WAITING_ENVELOPE,List.of(),false,"CAPTURED_NO_REPLAY");
 }
 private final class FencedBinding implements ServerEffectPort.Binding {
  final Flight f;final Work work;FencedBinding(Flight f,Work work){this.f=f;this.work=work;}
  public ProviderMode mode(){return ProviderMode.PRODUCTION;}public Set<PrimitiveId> supportedPrimitives(){return binding.supportedPrimitives();}
  public Set<CapabilityId> capabilities(){return binding.capabilities();}
  public EffectPreparation capture(EffectIntent i,PlanningSnapshot p){return binding.capture(i,p);}
  public boolean revalidate(PreparedEffect p,BlobBundle b){
   if(!current()||!currentOwner(f)||(!work.cleanup&&(f.stop||!(work.rearm?guard.mayResume(f.request):guard.mayApply(f.request)))))return false;
   var fresh=live(f).snapshot();
   return fresh.scopeRevisions().equals(p.preconditions().revisions())
      &&(fresh.serverHealth()==HealthMode.NORMAL||fresh.serverHealth()==HealthMode.DEGRADED)&&binding.revalidate(p,b);
  }
  public SliceResult apply(PreparedEffect p,BlobBundle b){
   require(revalidate(p,b),"regional live approval/profile/cut revoked before apply");work.invoked=true;
   if(work.rearm)f.rearms++;else if(!work.cleanup)f.births++;return binding.apply(p,b);
  }
  public ReconcileResult reconcile(RecoveryProbe p){return binding.reconcile(p);}
 }
 private MutationRecord mutation(Flight f,Work w,MutationState state){
  var p=f.forward.prepared().orElseThrow();
  var query=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.MUTATION,f.owner.creationMutationId().value())),new Sequence(0),Optional.empty(),1);
  var read=store.view(query);ReadContracts.validate(read,query,session);require(read.status()==ReadStatus.AVAILABLE&&!read.page().orElseThrow().hasMore(),"actual regional mutation head unavailable");
  var rows=read.page().orElseThrow().aggregates();require(rows.size()<=1,"regional mutation cardinality");
  var revision=rows.isEmpty()?new Revision(1):rows.getFirst().state().header().revision().plus(1);
  return new MutationRecord(new AggregateHeader(1,f.owner.creationMutationId().value(),revision,rows.isEmpty()?new Sequence(0):rows.getFirst().committedSequence(),guard.clock()),
   f.owner.creationMutationId(),f.request.event(),w.prepare.slice().sliceId(),state,WeatherCoreRegionalBinding.PRIMITIVE,1,f.request.footprint(),
   p.durableBlobs().preStates().get(key(f)),p.durableBlobs().expectedPostStates().get(key(f)),f.owner.ownerTokens().iterator().next(),f.owner.epoch(),p.preconditions(),Set.of(),List.of(),Optional.empty(),new Sequence(0));
 }
 private void start(Flight f,boolean cleanup,boolean rearm){
  qualify(cleanup);var captured=Objects.requireNonNull(f.captured);var p=captured.prepared().orElseThrow();
  if(!acquire(f))return;
  var current=live(f);var snapshot=current.snapshot();
  // Waiting for the existing global reservation may span J01/J04 revisions.
  // No PREPARE has been submitted here. Re-observe the same immutable intent;
  // native UUID/deadline of REARM and the original birth operation never change.
  if(!p.preconditions().revisions().equals(snapshot.scopeRevisions())){
   if(cleanup){var stop=f.forward.prepared().orElseThrow().durableBlobs().stopPlan();binding.registerCleanup(p.intent(),f.forward.blobs().contents().stream().filter(b->b.reference().equals(stop)).findFirst().orElseThrow());}
   captured=binding.capture(p.intent(),current);p=captured.prepared().orElseThrow();f.captured=captured;
   if(!cleanup&&!rearm){f.forward=captured;persist(f,Stage.WAITING_ENVELOPE,List.of(),false,"FRESH_PREPARE_CAPTURE_SAME_BIRTH_INTENT");return;}
  }
  var reserved=guard.reservation(f.request,p,cleanup);
  if(reserved.isEmpty()){release(f);f.stage=Stage.WAITING_ENVELOPE;return;}
  var reservation=reserved.orElseThrow();var token=reservation.token();
  var beforePrepare=live(f).snapshot();
  if(!beforePrepare.scopeRevisions().equals(p.preconditions().revisions())){
   require(reservation.arbiter().release(token),"unused stale regional tranche release");release(f);f.stage=Stage.WAITING_ENVELOPE;return;
  }
  require(token.worldId().equals(world)&&token.instanceId().equals(f.request.event())
    &&token.claims().equals(p.claims())&&reservation.arbiter().valid(token,guard.clock()),"regional existing arbiter/token/claims differ");
  var w=new Work();w.captured=captured;w.plan=p;w.reservation=reservation;w.cleanup=cleanup;w.rearm=rearm;
  var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),p.intent().operationToken(),p.intent().expectedFootprint(),p.preconditions(),List.of(p.intent()),p.budgetEstimate());
  var expiry=new Tick(Math.min(token.expiryTick().value(),Math.addExact(guard.clock().value(),200)));
  w.prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),p.intent().operationToken(),token.generation(),p.preconditions(),expiry,slice,token,captured.validation(),guard.uniqueness(f.request,token,cleanup,rearm),Set.of(),List.of(),p.durableBlobs());
  w.live=new LiveValidationSnapshot(p.preconditions(),snapshot.loadedChunks(),snapshot.serverHealth(),guard.clock(),token.generation());
  w.authority=new CommitPermit.Authority(store,4);var fence=new FencedBinding(f,w);
  w.port=new ServerEffectPort(w.authority,i->{throw new IllegalStateException("already captured");},s->w.live,Map.of(WeatherCoreRegionalBinding.PRIMITIVE,fence),
   cleanup?guard.cleanupCapabilities():guard.qualifiedCapabilities(),4,s->new StabilizationLiveSnapshot(session,w.live,new OwnershipView(owners)),s->{},(a,b)->{});
  w.port.hydrate(p,captured.blobs());w.runner=new EventRunner(w.authority,store,guard.effectPort(p,captured.blobs(),w.port,cleanup),reservation.arbiter(),4);
  var submittedPlan=p;w.runner.submit(new DurableBatch(w.prepare.transactionId(),session.storeGeneration(),p.preconditions().revisions(),List.of(new DurablePrepare(w.prepare)),captured.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(w.prepare)),p.expectedPost(),w.live,actual->{
   var rows=new ArrayList<DurableRecord>();rows.add(new DurableResult(new ResultRecord(w.prepare.transactionId(),submittedPlan.intent().operationToken(),actual,Set.of(),List.of(),List.of())));
   if(!rearm)rows.add(new DurableMutation(mutation(f,w,cleanup?MutationState.ROLLED_BACK:MutationState.VERIFIED)));
   var ledger=guard.effectResult(f.request,w.prepare,actual,cleanup,rearm);rows.addAll(ledger.records());
   return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),session.storeGeneration(),Map.of(),rows,ledger.blobs());
  });
  f.work=w;f.stage=cleanup?Stage.CLEANUP:rearm?Stage.REARMING:Stage.EXECUTING;
 }
 private void work(Flight f){
  var w=f.work;var fresh=live(f).snapshot();
  w.live=new LiveValidationSnapshot(w.plan.preconditions(),fresh.loadedChunks(),fresh.serverHealth(),guard.clock(),w.reservation.token().generation());
  var v=w.runner.poll(w.prepare.transactionId(),w.live);
  if(v.stage()==EventRunner.Stage.PREPARING||v.stage()==EventRunner.Stage.RESULT_PENDING)return;
  if(v.stage()!=EventRunner.Stage.COMMITTED){f.uncertain|=w.invoked;f.stage=Stage.RECOVERY_REQUIRED;f.diagnostic="POSSIBLE_NATIVE_WRITE_OR_ACK_UNCERTAIN "+v.failure().orElse("UNKNOWN");return;}
  require(w.reservation.arbiter().release(w.reservation.token()),"regional exact tranche reservation release");w.runner.forget(w.prepare.transactionId());f.work=null;
  if(w.cleanup){finish(f,"OWNED_NATIVE_CLEANUP_CONFIRMED",true);return;}
  if(!w.rearm){var actual=v.result().orElseThrow();f.birthConfirmed=(actual.outcome()==Outcome.SUCCEEDED||actual.outcome()==Outcome.RECONCILED)&&actual.appliedOperationIds().contains(f.request.operation());}
  f.stage=Stage.ACTIVE;f.captured=null;persist(f,Stage.ACTIVE,List.of(),false,w.rearm?"REARM_DURABLE_SAME_UUID_DEADLINE":"FORWARD_RESULT_DURABLE");
 }
 private void finish(Flight f,String why,boolean cleanup){
  if(!acquire(f))return;require(currentOwner(f),"regional finish owner changed/unknown");
  var actual=binding.observedState(f.request.footprint().dimension(),f.request.event());
  require(actual.state().kind()==WeatherCoreRegionalState.Kind.ABSENT||actual.state().kind()==WeatherCoreRegionalState.Kind.RETIRED,"regional release still active");
  if(actual.state().kind()==WeatherCoreRegionalState.Kind.RETIRED){f.stage=Stage.ACTIVE;f.stop=true;f.diagnostic="WAIT_ACTUAL_NATIVE_REMOVAL";return;}
  var terminalRows=new ArrayList<DurableRecord>();
  var mutationQuery=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.MUTATION,f.owner.creationMutationId().value())),new Sequence(0),Optional.empty(),1);
  var mutationRead=store.view(mutationQuery);ReadContracts.validate(mutationRead,mutationQuery,session);require(mutationRead.status()==ReadStatus.AVAILABLE,"regional final actual mutation head unavailable");
  for(var row:mutationRead.page().orElseThrow().aggregates()){
   require(row.state() instanceof MutationAggregate,"regional final wrong mutation kind");var m=((MutationAggregate)row.state()).value();
   require(m.instanceId().equals(f.request.event())&&m.ownerEpoch().equals(f.owner.epoch())&&f.owner.ownerTokens().contains(m.ownerToken())&&m.primitiveId().equals(WeatherCoreRegionalBinding.PRIMITIVE),"regional final mutation ownership differs");
   if(m.state()!=MutationState.ROLLED_BACK){
    require(m.state()!=MutationState.YIELDED&&m.state()!=MutationState.CONFLICT_ISOLATED,"foreign/yielded regional mutation cannot be retired");
    var evidence=new ArrayList<>(m.reconciliationEvidence());evidence.add(new RecoveryEvidence(key(f),EvidenceStatus.CONFIRMED,Optional.of(binding.observedFingerprint(f.request.footprint().dimension(),f.request.event())),Optional.of(m.ownerToken()),m.ownerEpoch(),f.owner.playerOverrideEpoch(),true));
    var mh=m.header();terminalRows.add(new DurableMutation(new MutationRecord(new AggregateHeader(mh.schemaVersion(),mh.id(),mh.revision().plus(1),mh.lastSequence(),guard.clock()),m.mutationId(),m.instanceId(),m.sliceId(),MutationState.ROLLED_BACK,m.primitiveId(),m.primitiveVersion(),m.footprint(),m.preStateRef(),m.postStateRef(),m.ownerToken(),m.ownerEpoch(),m.readSetVersions(),m.noninvertibleEffects(),evidence,m.compensationRef(),m.durableSeq())));
   }
  }
  var old=f.owner;var h=old.header();var released=new OwnershipRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),h.lastSequence(),guard.clock()),old.resourceKey(),old.ownerTokens(),old.epoch(),OwnershipDisposition.RELEASED,old.creationMutationId(),binding.observedFingerprint(f.request.footprint().dimension(),f.request.event()),old.playerOverrideEpoch(),old.exceptionContractId());
  // Keep the exact sealed original owner inside the index; it remains evidence,
  // and the separate actual owner head explicitly records release.
  terminalRows.add(new DurableOwnership(released));persist(f,Stage.COMPLETE,terminalRows,true,why);f.stage=Stage.END_PENDING;
 }
 private void cleanup(Flight f){
  qualify(true);if(f.forward==null){finish(f,"NO_PHYSICAL_PREPARE",false);return;}
  if(!acquire(f))return;
  var i=WeatherCoreRegionalBinding.cleanupRequest(new OperationToken(UUID.randomUUID()),f.request.event(),new Footprint(f.request.footprint().dimension(),Set.of(),Set.of(),f.request.footprint().bounds()),guard.regionalRevision(f.request));
  var stop=f.forward.prepared().orElseThrow().durableBlobs().stopPlan();
  binding.registerCleanup(i,f.forward.blobs().contents().stream().filter(b->b.reference().equals(stop)).findFirst().orElseThrow());
  f.captured=binding.capture(i,live(f));start(f,true,false);
 }
 private void active(Flight f){
  var actual=binding.observedState(f.request.footprint().dimension(),f.request.event());
  if(actual.state().kind()==WeatherCoreRegionalState.Kind.ABSENT){finish(f,"ACTUAL_NATIVE_EXPIRY_OR_ABSENCE historical_uncertain="+f.uncertain,false);return;}
  require(actual.state().weather().equals(f.nativeUuid)&&actual.state().deadline()==f.deadline,"regional native identity/deadline changed");
  if(actual.state().kind()==WeatherCoreRegionalState.Kind.RETIRED){finish(f,"WAIT_ACTUAL_NATIVE_REMOVAL",true);return;}
  if(f.stop||!guard.mayResume(f.request)){
   if(!f.stop&&f.cold&&!actual.renderArmed()&&guard.mayAwaitResume(f.request)){f.stage=Stage.SUSPENDED;f.diagnostic="DISARMED_WAIT_FRESH_PRODUCTION_RESUME";return;}
   cleanup(f);return;
  }
  if(!actual.renderArmed()){
   if(!guard.available(f.request)){f.stage=Stage.SUSPENDED;return;}
   qualify(false);if(!acquire(f))return;
   f.captured=binding.capture(WeatherCoreRegionalBinding.resumeRequest(new OperationToken(UUID.randomUUID()),f.request.event(),f.request.footprint(),guard.regionalRevision(f.request)),live(f));
   start(f,false,true);
  }
 }
 private void advance(Flight f){
  if(f.tx!=null){metadata(f);return;}
  switch(f.stage){
   case QUEUED->initialize(f);
   case WAITING_ENVELOPE->{
    if(f.captured!=null){var args=(RegionalWeatherLease)f.captured.intent().typedArgs();start(f,args.profile().equals(WeatherCoreRegionalBinding.STOP),args.profile().equals(WeatherCoreRegionalBinding.REARM));}
    else if(f.forward==null)captureForward(f);
    else if(f.cold)active(f);
    else{f.captured=f.forward;start(f,false,false);}
   }
   case EXECUTING,REARMING,CLEANUP->work(f);
   case ACTIVE->active(f);
   case SUSPENDED->{if(f.stop||(!guard.mayResume(f.request)&&!guard.mayAwaitResume(f.request)))cleanup(f);else if(guard.mayResume(f.request)&&guard.available(f.request)){f.stage=f.forward==null?Stage.WAITING_ENVELOPE:Stage.ACTIVE;}else if(level(f.request.footprint().dimension()).getGameTime()>=f.deadline)cleanup(f);}
   case RECOVERING->recoverFlight(f);
   default->{}
  }
 }
 private void recover(){
  if(!recoveryHeld){recoveryHeld=guard.acquireMetadataLane(recoveryLane);if(!recoveryHeld)return;}
  if(scan==null){scanQuery=new StoreQuery(world,Set.of(),new Sequence(0),cursor,512);scan=store.read(scanQuery).toCompletableFuture();return;}
  if(!scan.isDone())return;var read=scan.getNow(null);scan=null;ReadContracts.validate(read,scanQuery,session);
  if(read.status()==ReadStatus.STALE){
   require(++scanRestarts<=4,"regional recovery cut repeatedly stale");cursor=Optional.empty();journalHeads.clear();originalPrepares.clear();originalResults.clear();return;
  }
  require(read.status()==ReadStatus.AVAILABLE,"regional exact J04 recovery unavailable");var page=read.page().orElseThrow();
  for(var row:page.records()){
   if(row instanceof DurableFact fact&&fact.value().factType().equals(RegionalWeatherJournal.TYPE)){
    require(fact.value().subjectRefs().equals(List.of(new ScopeRef(ScopeKind.WORLD,world.value()))),"foreign regional journal world");
    var s=RegionalWeatherJournal.state(fact.value());var old=journalHeads.get(s.request().event());
    require(old==null||s.ordinal()>old.ordinal()||s.equals(old),"regional chronological index regressed");
    journalHeads.put(s.request().event(),s);require(journalHeads.size()<=2048,"regional retained history bound");
   }
   if(row instanceof DurablePrepare p&&p.value().slice().operations().stream().anyMatch(i->i.primitive().equals(WeatherCoreRegionalBinding.PRIMITIVE))){
    originalPrepares.put(p.value().operationToken(),p.value());require(originalPrepares.size()<=8192,"regional PREPARE history bound");
   }
   if(row instanceof DurableResult r&&originalPrepares.containsKey(r.value().operationToken()))originalResults.put(r.value().operationToken(),r.value());
  }
  cursor=page.nextCursor();if(page.hasMore())return;
  for(var head:journalHeads.values()){
   if(head.completed()){
    if(store.physicalEventReadback(head.request().event(),4096).filter(PhysicalEventReadback::released).isPresent())continue;
   }
   require(flights.values().stream().filter(f->f.stage!=Stage.COMPLETE).count()<capacity&&flights.size()<capacity+128,"regional recovery cache bound; preserve all and refuse admission");
   var f=new Flight();f.request=head.request();f.owner=head.owner();f.head=head;f.cold=true;f.uncertain=head.uncertain();f.stage=Stage.RECOVERING;
   if(head.forward().isPresent()){
    var p=head.forward().orElseThrow();f.forward=new EffectPreparation(p.intent(),new ValidationResult(true,List.of(),p.preconditions()),Optional.of(p),new BlobBundle(head.blobs().stream().map(RegionalWeatherJournal.Bytes::content).toList()));
    var post=WeatherCoreRegionalWitness.read(f.forward.blobs().contents().stream().filter(b->b.reference().equals(p.durableBlobs().preparedPlan())).findFirst().orElseThrow().bytes()).state();
    f.nativeUuid=post.weather();f.deadline=post.deadline();
    for(var prepare:originalPrepares.values())if(prepare.reservation().instanceId().equals(f.request.event())&&!originalResults.containsKey(prepare.operationToken()))f.missingResults.add(prepare);
    f.uncertain|=!f.missingResults.isEmpty();
   }
   var born=originalResults.get(f.request.operation());f.birthConfirmed=born!=null&&(born.result().outcome()==Outcome.SUCCEEDED||born.result().outcome()==Outcome.RECONCILED)&&born.result().appliedOperationIds().contains(f.request.operation());
   if(head.completed()&&f.missingResults.isEmpty())f.stage=Stage.COMPLETE;else owners.put(key(f),f.owner);flights.put(f.request.event(),f);
  }
  recovered=true;guard.releaseMetadataLane(recoveryLane);recoveryHeld=false;diagnostic="RECOVERED_EXISTING_J04";
 }
 private void recoverFlight(Flight f){
  require(currentOwner(f),"cold actual regional J04 owner changed/unknown");
  if(reconcileMissingResult(f))return;
  if(f.forward==null){finish(f,"COLD_OWNER_WITHOUT_FORWARD_NO_REPLAY",false);return;}
  f.stage=Stage.ACTIVE;active(f);
 }
 /** A missing historical RESULT is unknown. Record only that uncertainty against
  * its exact original PREPARE; keep owner, native UUID/deadline and cleanup open. */
 private boolean reconcileMissingResult(Flight f){
  if(f.missingResults.isEmpty())return false;
  require(currentOwner(f),"cold actual regional J04 owner changed/unknown");
  if(!acquire(f))return true;var old=f.missingResults.removeFirst();
  var uncertain=new SliceResult(Outcome.DELIVERY_UNCERTAIN,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
  persist(f,Stage.RECOVERING,List.of(new DurableResult(new ResultRecord(old.transactionId(),old.operationToken(),uncertain,Set.of(),List.of(),List.of()))),false,"MISSING_RESULT_DELIVERY_UNCERTAIN_NO_FORWARD_REPLAY");return true;
 }
 /** Called while the official world is still booting. This path cannot invoke
  * advance/active/finish/start: it has no native apply or owner-release authority. */
 public void pollRecoveryOnly(){
  actor();if(failed)return;
  try{
   require(current(),"regional J04 session revoked");
   if(!recovered){recover();return;}
   for(var f:List.copyOf(flights.values())){
    if(f.tx!=null){metadata(f);continue;}
    if(f.stage==Stage.RECOVERING)reconcileMissingResult(f);
   }
  }catch(RuntimeException invalid){failRecovery(invalid);}
 }
 private void failRecovery(RuntimeException invalid){
  failed=true;diagnostic=invalid.getClass().getSimpleName()+":"+invalid.getMessage();
  for(var f:flights.values()){f.uncertain|=f.work!=null&&f.work.invoked;f.stage=Stage.RECOVERY_REQUIRED;f.diagnostic=diagnostic;
   if(f.tx==null&&f.work==null)release(f);
  }
 }
 public void poll(){
  actor();if(failed)return;
  try{require(current(),"regional J04 session revoked");if(!recovered){recover();return;}
   for(var f:List.copyOf(flights.values()))if(f.stage!=Stage.COMPLETE&&f.stage!=Stage.RECOVERY_REQUIRED){
    try{advance(f);}catch(SelectedEventStart.ContextUnavailable awaiting){f.diagnostic="WAIT_CURRENT_PRODUCTION_CONTEXT "+awaiting.getMessage();if(f.tx==null&&f.work==null)release(f);}
   }
   while(flights.size()>capacity+128){var oldest=flights.entrySet().stream().filter(e->e.getValue().stage==Stage.COMPLETE).findFirst();if(oldest.isEmpty())break;flights.remove(oldest.orElseThrow().getKey());}
  }catch(RuntimeException invalid){failRecovery(invalid);}
 }
 public String diagnostic(){actor();return diagnostic;}
 /** The shared J04 store belongs to bootstrap. Never cancel an uncertain write.
  * Native state disarms on the next server load; no cold permit is reconstructed. */
 public void close(){actor();
  for(var f:flights.values())if(f.tx==null&&f.work==null)release(f);
  if(recoveryHeld){guard.releaseMetadataLane(recoveryLane);recoveryHeld=false;}
  closed=true;
 }
}

