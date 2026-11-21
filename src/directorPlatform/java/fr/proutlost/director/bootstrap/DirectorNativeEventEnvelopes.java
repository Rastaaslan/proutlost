package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** One world-wide event envelope. Existing local arbiters retain the exact
 * J05 tranche permits; they cannot admit an event outside this envelope. */
public final class DirectorNativeEventEnvelopes {
 public static final FactType TYPE=new FactType("proutlost_director:event_envelope_lease");
 private static final class Entry {
  SelectedEventStart selected;ReservationToken token,durableToken;long deadline;boolean releasing,cleanupOnly;
  DurableBatch batch;String pendingPhase,renewalWait;CompletableFuture<CommitAck> pending;
 }
 private final Thread actor=Thread.currentThread();private final JournalStore store;
 private final StoreSession session;private final DirectorRuntimeContent content;private final Supplier<Tick> clock;
 private final ResourceArbiter arbiter;private final Map<EventInstanceId,Entry> held=new LinkedHashMap<>();
 private record Ready(SelectedEventStart selected,List<PreparedEffect> prepared,Set<PlayerId> audience,java.util.function.BooleanSupplier authorized) {
  Ready {prepared=List.copyOf(prepared);audience=Set.copyOf(audience);}
 }
 private record Proposal(Ready source,ResourceArbiter.Candidate candidate,Map<ResourceKey,Epoch> epochs) {}
 private final Map<EventInstanceId,Ready> ready=new LinkedHashMap<>();
 private final Map<EventInstanceId,String> deferrals=new LinkedHashMap<>();
 private static final class RegionalTranche {ReservationToken token;DurableBatch batch;CompletableFuture<CommitAck> ack;boolean durable,lent,releasing;}
 private final Map<EventInstanceId,ReservationToken> historicalPhysical=new HashMap<>();
 private final Map<OperationToken,RegionalTranche> regionalTranches=new LinkedHashMap<>();
 private boolean failed;
 public DirectorNativeEventEnvelopes(JournalStore store,DirectorRuntimeContent content,Supplier<Tick> clock,Epoch floor){
  this.store=Objects.requireNonNull(store);this.session=store.session();this.content=Objects.requireNonNull(content);this.clock=Objects.requireNonNull(clock);
  if(!content.production())throw new IllegalArgumentException("production envelope required");
  arbiter=new ResourceArbiter(session.worldId(),floor,Long.MAX_VALUE,128,new ResourceArbiter.Compatibility(){
   public boolean share(ResourceClaim a,ResourceClaim b){return false;}
   public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}
  });
 }
 private void actor(){if(Thread.currentThread()!=actor)throw new IllegalStateException("event envelope actor");}
 private long lease(ConfigView config){return Math.max(1,config.values().get("scheduler.reservation_lease_ticks").value().longValueExact());}
 private DirectorNativeCatalogueExecution.GateResult waiting(String detail){return new DirectorNativeCatalogueExecution.GateResult(DirectorNativeCatalogueExecution.GateStatus.WAITING,detail);}
 public void restoreFloor(Epoch floor){actor();arbiter.restoreGenerationFloor(floor);}
 public boolean failed(){actor();return failed;}
 public void poll(){
  actor();if(failed)return;
  try{
   if(!session.equals(store.session())||store.readOnlySafe())throw new IllegalStateException("envelope WAL authority lost");
   Tick now=clock.get();
   for(var pair:List.copyOf(held.entrySet())){
    var entry=pair.getValue();
    if(entry.pending!=null){
     if(!entry.pending.isDone())continue;var ack=entry.pending.getNow(null);
     if(ack==null||ack.ackStatus()!=AckStatus.DURABLE||!ack.transactionId().equals(entry.batch.transactionId())
      ||!ack.transactionHash().equals(entry.batch.transactionHash())||!ack.storeGeneration().equals(session.storeGeneration())
      ||ack.durableSequence().value()<1||ack.permitAttempt().isPresent()||!ack.forcedBlobs().isEmpty())throw new IllegalStateException("envelope exact ACK missing");
     if(entry.releasing&&arbiter.hasBorrowed(entry.token))continue;
     entry.pending=null;entry.batch=null;entry.durableToken=entry.token;
     if(entry.releasing){
      if(!"RELEASED".equals(entry.pendingPhase)){persist(entry,"RELEASED");continue;}
      if(!arbiter.release(entry.token))throw new IllegalStateException("envelope release mismatch");held.remove(pair.getKey());continue;
     }
    }
    if(entry.releasing)continue;
    if(now.value()>=entry.deadline||now.compareTo(entry.token.expiryTick())>=0){
     if(!"EXPIRED_RETAINED".equals(entry.renewalWait)){
      entry.renewalWait="EXPIRED_RETAINED";System.out.println("DIRECTOR_ENVELOPE_RENEWAL_WAIT event="+pair.getKey().value()+" reason=EXPIRED_RETAINED tick="+now.value()+" expiry="+entry.token.expiryTick().value());
     }
     continue;
    }
    if(arbiter.hasBorrowed(entry.token))continue;
    long interval=lease(entry.selected.captured().config());
    if(entry.token.expiryTick().value()-now.value()>Math.max(1,interval/2))continue;
    try{entry.selected.refresh();entry.renewalWait=null;}catch(IllegalStateException unavailable){
     var reason=unavailable.getClass().getSimpleName()+": "+unavailable.getMessage();
     if(!reason.equals(entry.renewalWait)){
      entry.renewalWait=reason;System.out.println("DIRECTOR_ENVELOPE_RENEWAL_WAIT event="+pair.getKey().value()+" reason="+reason+" tick="+now.value()+" expiry="+entry.token.expiryTick().value());
     }
     continue;
    }
    long remaining=Math.min(interval,entry.deadline-now.value());
    entry.token=arbiter.renew(entry.token,now,new DurationTicks(remaining));persist(entry,"RENEWED");
   }
   pollRegionalTranches();resolveReady(now);
  }catch(RuntimeException rejected){failed=true;System.err.println("DIRECTOR_EVENT_ENVELOPE_FAILED_SAFE "+rejected.getClass().getSimpleName());}
 }
 public DirectorNativeCatalogueExecution.GateResult admit(SelectedEventStart selected,List<PreparedEffect> prepared,Set<PlayerId> audience){return admit(selected,prepared,audience,null);}
 public DirectorNativeCatalogueExecution.GateResult admit(SelectedEventStart selected,List<PreparedEffect> prepared,Set<PlayerId> audience,java.util.function.BooleanSupplier authorized){
  actor();if(failed)return waiting("GLOBAL_ENVELOPE_FAILED_SAFE");
  var previous=held.get(selected.eventId());
  if(previous!=null){
   if(!previous.selected.plan().equals(selected.plan())||!previous.selected.captured().equals(selected.captured())||!previous.selected.parentChain().equals(selected.parentChain())||previous.selected.mode()!=selected.mode())throw new IllegalArgumentException("held envelope identity changed");
   // An expired logical lease cannot become current by waiting. Reject only this
   // still-pre-driver admission; the executor journals its real no-invocation
   // abort before retirement releases any claim. Never infer physical cleanup here.
   var now=clock.get();
   if(now.value()>=previous.deadline||now.compareTo(previous.token.expiryTick())>=0)
    return new DirectorNativeCatalogueExecution.GateResult(DirectorNativeCatalogueExecution.GateStatus.REJECTED,"GLOBAL_ENVELOPE_EXPIRED_BEFORE_DRIVER");
   return forwardAllowed(selected.eventId())?new DirectorNativeCatalogueExecution.GateResult(DirectorNativeCatalogueExecution.GateStatus.READY,"GLOBAL_ENVELOPE_DURABLE"):waiting("WAIT_GLOBAL_ENVELOPE_ACK_OR_SAFE_EXIT");
  }
  var queued=ready.get(selected.eventId());
  if(queued!=null){
   if(!queued.selected().proposal().equals(selected.proposal())||!queued.selected().captured().equals(selected.captured())||!queued.selected().parentChain().equals(selected.parentChain())||!queued.prepared().equals(prepared)||!queued.audience().equals(audience))throw new IllegalStateException("ready envelope identity changed");
   if(authorized!=null)ready.put(selected.eventId(),new Ready(selected,prepared,audience,authorized));
   return waiting("WAIT_GLOBAL_READY_WINDOW");
  }
  if(held.size()+ready.size()>=64)return waiting("GLOBAL_EVENT_CAPACITY");
  ready.put(selected.eventId(),new Ready(selected,prepared,audience,authorized));
  return waiting(deferrals.getOrDefault(selected.eventId(),"WAIT_GLOBAL_READY_WINDOW"));
 }
 private Optional<Proposal> defer(EventInstanceId event,String reason){
  deferrals.put(event,reason);while(deferrals.size()>128)deferrals.remove(deferrals.keySet().iterator().next());return Optional.empty();
 }
 /** All plans here have an exact decision-audit ACK. No EVENT metadata or
  * physical provider is invoked while assembling this actor-owned ready window. */
 private void resolveReady(Tick now){
  if(ready.isEmpty())return;
  var before=store.residentAggregateKeys(Set.of(AggregateKind.WORLD),1).orElseThrow();
  if(!before.session().equals(session))throw new IllegalStateException("ready window session");
  DecisionInput shared=null;var window=new ArrayList<Proposal>();var epochs=new HashMap<ResourceKey,Epoch>();
  for(var entry:List.copyOf(ready.entrySet())){
   var source=entry.getValue();
   if(source.authorized()==null)continue; // Await the real queued-command authority, never infer consent from a DTO.
   if(!source.authorized().getAsBoolean()){ready.remove(entry.getKey());defer(entry.getKey(),"QUEUED_AUTHORITY_REVOKED");continue;}
   final DecisionInput current;try{current=source.selected().refresh();}catch(IllegalStateException unavailable){defer(entry.getKey(),"WAIT_GLOBAL_LIVE_CONTEXT");continue;}
   if(shared==null)shared=current;
   if(!shared.equals(current)){defer(entry.getKey(),"WAIT_GLOBAL_COMMON_INPUT_CUT");continue;}
   var proposed=proposal(source,current,now);if(proposed.isEmpty())continue;var next=proposed.get();
   if(next.epochs().entrySet().stream().anyMatch(e->epochs.containsKey(e.getKey())&&!epochs.get(e.getKey()).equals(e.getValue()))){defer(entry.getKey(),"INCONSISTENT_WINDOW_EPOCH");continue;}
   epochs.putAll(next.epochs());window.add(next);
  }
  if(window.isEmpty())return;
  final Map<ScopeRef,BudgetVector> capacities;try{capacities=capacities(shared);}catch(IllegalStateException unavailable){return;}
  var after=store.residentAggregateKeys(Set.of(AggregateKind.WORLD),1).orElseThrow();
  if(!after.session().equals(session)||!after.cut().equals(before.cut()))return;
  var byId=new HashMap<ReservationId,Proposal>();window.forEach(w->byId.put(w.candidate().id(),w));
  var decisions=arbiter.arbitrate(window.stream().map(Proposal::candidate).toList(),now,epochs,capacities);
  System.out.println("DIRECTOR_GLOBAL_READY_WINDOW candidates="+window.size()+" granted="+decisions.stream().filter(d->d.accepted().isPresent()).count()+" cut="+before.cut().value());
  for(var decision:decisions){
   var source=byId.get(decision.candidate()).source();var selected=source.selected();ready.remove(selected.eventId());
   if(decision.accepted().isEmpty()){defer(selected.eventId(),"GLOBAL_"+decision.rejection().orElseThrow().name());continue;}
   var entry=new Entry();entry.selected=selected;entry.token=decision.accepted().orElseThrow();
   entry.deadline=Math.addExact(now.value(),Math.addExact(selected.definition().timing().maxDurationTicks().value(),shared.config().values().get("scheduler.recovery_deadline_ticks").value().longValueExact()));
   held.put(selected.eventId(),entry);deferrals.remove(selected.eventId());persist(entry,"RESERVED");
  }
 }
 private Optional<Proposal> proposal(Ready source,DecisionInput current,Tick now){
  var selected=source.selected();var prepared=source.prepared();var audience=source.audience();
  var claims=new LinkedHashMap<ResourceKey,ResourceClaim>();BudgetVector estimated=BudgetMath.zero();
  for(var plan:prepared){
   if(!plan.intent().instanceId().equals(selected.eventId()))throw new IllegalArgumentException("foreign envelope operation");
   estimated=BudgetMath.add(estimated,plan.budgetEstimate());
   for(var claim:plan.claims()){
    var exclusive=new ResourceClaim(claim.resource(),ClaimPolicy.EXCLUSIVE,claim.cost(),Optional.empty(),claim.expectedEpoch());
    var old=claims.putIfAbsent(claim.resource(),exclusive);
    if(old!=null&&!old.expectedEpoch().equals(claim.expectedEpoch()))return defer(selected.eventId(),"INCONSISTENT_ENVELOPE_EPOCH");
   }
  }
  for(var plan:prepared)if(plan.intent().typedArgs() instanceof RegionalWeatherLease){
   var key=new ResourceKey(OwnershipDomain.WEATHER,plan.intent().expectedFootprint().dimension(),"weather");
   var owner=current.state().ownership().records().get(key);claims.putIfAbsent(key,new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,BudgetMath.zero(),Optional.empty(),owner==null?new Epoch(0):owner.epoch()));
  }
  // Native WARN/helper channels also belong to the event's complete audience.
  // Conservative exclusivity is used until a compatible mixer is registered.
  for(var player:audience){
   var known=current.context().context().players().get(player);if(known==null)return defer(selected.eventId(),"INCOMPLETE_ENVELOPE_AUDIENCE");
   var key=new ResourceKey(OwnershipDomain.PERCEPTION,known.position().position().dimension(),"director-event/"+player.value());
   claims.put(key,new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,BudgetMath.zero(),Optional.empty(),new Epoch(0)));
  }
  var cost=maximum(selected.definition().budget(),estimated);
  BudgetVector claimMinimum=BudgetMath.zero();for(var claim:claims.values())claimMinimum=BudgetMath.add(claimMinimum,claim.cost());cost=maximum(cost,claimMinimum);
  var costs=new HashMap<ScopeRef,BudgetVector>();costs.put(new ScopeRef(ScopeKind.WORLD,session.worldId().value()),cost);
  for(var scope:selected.plan().scopes().scopes())if(scope.kind()==ScopeKind.REGION)costs.put(scope,new BudgetVector(0,0,cost.regionalIntensity(),0,0,0,0,0,0,0));
  var epochs=new HashMap<ResourceKey,Epoch>();claims.values().forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
  // Ownership epochs are current actor DTOs; physical bytes are revalidated by J05.
  for(var claim:claims.values()){
   var owner=current.state().ownership().records().get(claim.resource());
   if(owner!=null&&!owner.epoch().equals(claim.expectedEpoch()))return defer(selected.eventId(),"STALE_GLOBAL_OWNERSHIP_EPOCH");
  }
  double score=selected.proposal().candidates().stream().filter(c->c.id().equals(selected.plan().candidateId())).mapToDouble(Candidate::score).findFirst().orElse(0);
  long duration=lease(current.config());
  var rank=new ResourceArbiter.Rank(false,selected.definition().conflicts().priority()==Priority.REQUIRE,selected.parentChain().isPresent(),
   selected.definition().conflicts().priority(),Math.max(0,now.value()-selected.captured().context().capturedTick().value()),score,selected.captured().seed());
  var id=new ReservationId(UUID.nameUUIDFromBytes((session.worldId().value()+"/event-envelope/"+selected.eventId().value()).getBytes(StandardCharsets.UTF_8)));
  var request=new ResourceArbiter.Candidate(id,selected.eventId(),rank,now.plus(duration),List.copyOf(claims.values()),costs,selected.physicalResources());
  return Optional.of(new Proposal(source,request,Map.copyOf(epochs)));
 }
 public boolean forwardAllowed(EventInstanceId event){
  actor();var entry=held.get(event);var now=clock.get();
  // A renewal never invalidates the still-unexpired, exactly acknowledged lease.
  // The new expiry is unusable until its own ACK; physical permits are unchanged.
  return !failed&&entry!=null&&!entry.releasing&&!entry.cleanupOnly&&entry.durableToken!=null
   &&(entry.pending==null||"RENEWED".equals(entry.pendingPhase))
   &&now.compareTo(entry.durableToken.expiryTick())<0&&session.equals(store.session())
   &&!store.readOnlySafe()&&arbiter.valid(entry.token,now);
 }
 public void releaseAfterTerminal(EventInstanceId event){actor();ready.remove(event);deferrals.remove(event);var entry=held.get(event);if(entry==null||entry.releasing)return;entry.releasing=true;if(entry.pending==null)persist(entry,"RELEASED");}
 private void persist(Entry entry,String phase){
  entry.pendingPhase=phase;var token=entry.token;var event=token.instanceId();var factId=UUID.nameUUIDFromBytes((session.worldId().value()+"/event-envelope/"+event.value()+"/"+token.generation().value()+"/"+phase).getBytes(StandardCharsets.UTF_8));
  var fact=new DirectorFact(new FactId(factId),TYPE,List.of(new ScopeRef(ScopeKind.WORLD,session.worldId().value()),new ScopeRef(ScopeKind.EVENT,event.value())),Optional.of(event),EvidenceStatus.CONFIRMED,
   Map.of("phase",new JsonValue.Text(phase),"token",CanonicalJsonV1.decode(new ModelCodec<>(ReservationToken.class).encode(token)),"physical_resources",new JsonValue.Array(entry.selected.physicalResources().stream().map(c->CanonicalJsonV1.decode(new ModelCodec<>(PhysicalResourceClaim.class).encode(c))).toList()),"session",CanonicalJsonV1.decode(new ModelCodec<>(StoreSession.class).encode(session))),true,Optional.empty());
  entry.batch=ResultTransaction.batch(new TransactionId(factId),session.storeGeneration(),Map.of(),List.of(new DurableFact(fact)),List.of());
  try{entry.pending=store.append(entry.batch).toCompletableFuture();}catch(RuntimeException failure){failed=true;entry.pending=CompletableFuture.failedFuture(failure);throw failure;}
 }
 /** A real tranche of the same global arbiter. Normal dispatch borrows its
  * already-ACKed event envelope. Cold cleanup/rearm reacquires only the proven
  * owned resource and journals this global reservation before any native call. */
 public Optional<DirectorRegionalWeatherRuntime.Reservation> regionalReservation(RegionalWeatherJournal.Request request,PreparedEffect plan,PlanningSnapshot context,boolean cleanup,BudgetVector completeCost){
  actor();if(failed||!session.equals(store.session())||store.readOnlySafe())return Optional.empty();
  var now=clock.get();var event=held.get(request.event());
  if(event!=null){
   if(event.pending!=null||event.releasing||arbiter.hasBorrowed(event.token)||!event.token.claims().containsAll(plan.claims()))return Optional.empty();
   if(cleanup&&!arbiter.valid(event.token,now)){
    var proof=store.physicalEventReadback(request.event(),4096).orElseThrow();
    requireOwnedRegional(proof,plan);event.token=arbiter.renewRetainedForCleanup(event.token,now,new DurationTicks(200));event.cleanupOnly=true;persist(event,"CLEANUP_RENEWED");return Optional.empty();
   }
   if(cleanup?(event.durableToken==null||!event.token.equals(event.durableToken)||!arbiter.valid(event.token,now)):!forwardAllowed(request.event()))return Optional.empty();
   return Optional.of(new DirectorRegionalWeatherRuntime.Reservation(arbiter,arbiter.borrow(event.token,plan.claims(),now,new DurationTicks(200))));}
  var key=new ResourceKey(OwnershipDomain.WEATHER,request.footprint().dimension(),"region:"+request.event().value());
  if(plan.claims().size()!=1||!plan.claims().getFirst().resource().equals(key))throw new IllegalArgumentException("regional exact owned claim required");
  var proof=store.physicalEventReadback(request.event(),4096).orElseThrow(()->new IllegalStateException("regional recovery ownership cut unavailable"));
  var owned=proof.owners().stream().filter(o->o.resourceKey().equals(key)&&o.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE).toList();
  if(owned.size()!=1||!owned.getFirst().epoch().equals(plan.claims().getFirst().expectedEpoch()))return Optional.empty();
  var operation=plan.intent().operationToken();var existing=regionalTranches.get(operation);
  if(existing==null){
   if(regionalTranches.size()>=128)return Optional.empty();
   var common=new ResourceKey(OwnershipDomain.WEATHER,key.dimension(),"weather");var commonOwner=context.ownership().records().get(common);
   var claims=new ArrayList<>(plan.claims());claims.add(new ResourceClaim(common,ClaimPolicy.EXCLUSIVE,BudgetMath.zero(),Optional.empty(),commonOwner==null?new Epoch(0):commonOwner.epoch()));
   var epochs=new HashMap<ResourceKey,Epoch>();claims.forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
   // The exact owned weather mutation is already debited by J04. Reuse that
   // one slot; all foreign durable debits remain charged and caps stay pinned.
   var available=SelectedEventAdmission.available(context.budgets());var cap=context.budgets().capacity();
   var restored=new BudgetVector(available.tickMillis(),available.globalIntensity(),available.regionalIntensity(),available.liveEntities(),available.persistentEntities(),
    Math.min(cap.mutationsInFlight(),Math.addExact(available.mutationsInFlight(),1)),available.queuedIoBytes(),available.clientUnits(),available.payloadBytes(),available.navigationNodes());
   var costs=new HashMap<ScopeRef,BudgetVector>();var capacities=new HashMap<ScopeRef,BudgetVector>();var worldScope=new ScopeRef(ScopeKind.WORLD,session.worldId().value());
   costs.put(worldScope,completeCost);capacities.put(worldScope,restored);
   for(var scope:context.snapshot().scopes().scopes())if(scope.kind()==ScopeKind.REGION){costs.put(scope,new BudgetVector(0,0,completeCost.regionalIntensity(),0,0,0,0,0,0,0));capacities.put(scope,new BudgetVector(0,0,cap.regionalIntensity(),0,0,0,0,0,0,0));}
   var id=new ReservationId(UUID.nameUUIDFromBytes(("regional-global-tranche/"+session.bootEpoch().value()+"/"+operation.value()).getBytes(StandardCharsets.UTF_8)));
   var candidate=new ResourceArbiter.Candidate(id,request.event(),new ResourceArbiter.Rank(cleanup,false,false,cleanup?Priority.DANGER:Priority.AMBIENT,0,0,0),now.plus(200),claims,costs);
   var result=arbiter.arbitrate(List.of(candidate),now,epochs,capacities).getFirst();if(result.accepted().isEmpty())return Optional.empty();
   existing=new RegionalTranche();existing.token=result.accepted().orElseThrow();regionalTranches.put(operation,existing);persistRegional(existing,"RESERVED");return Optional.empty();
  }
  if(!existing.durable||existing.releasing||existing.lent||!arbiter.valid(existing.token,now))return Optional.empty();
  var child=arbiter.borrow(existing.token,plan.claims(),now,new DurationTicks(200));existing.lent=true;
  return Optional.of(new DirectorRegionalWeatherRuntime.Reservation(arbiter,child));
 }
 private static void requireOwnedRegional(PhysicalEventReadback proof,PreparedEffect plan){
  if(plan.claims().size()!=1)throw new IllegalArgumentException("one regional cleanup claim required");var claim=plan.claims().getFirst();
  if(proof.owners().stream().filter(o->o.resourceKey().equals(claim.resource())&&o.epoch().equals(claim.expectedEpoch())&&o.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE).count()!=1)
   throw new IllegalStateException("actual retained regional owner absent; cleanup renewal refused");
 }
 private void persistRegional(RegionalTranche entry,String phase){
  var id=new FactId(UUID.nameUUIDFromBytes(("regional-global-tranche/"+entry.token.id().value()+"/"+phase).getBytes(StandardCharsets.UTF_8)));
  var fact=new DirectorFact(id,TYPE,List.of(new ScopeRef(ScopeKind.WORLD,session.worldId().value()),new ScopeRef(ScopeKind.EVENT,entry.token.instanceId().value())),Optional.of(entry.token.instanceId()),EvidenceStatus.CONFIRMED,
   Map.of("phase",new JsonValue.Text(phase),"token",CanonicalJsonV1.decode(new ModelCodec<>(ReservationToken.class).encode(entry.token)),"session",CanonicalJsonV1.decode(new ModelCodec<>(StoreSession.class).encode(session))),true,Optional.empty());
  entry.batch=ResultTransaction.batch(new TransactionId(id.value()),session.storeGeneration(),Map.of(),List.of(new DurableFact(fact)),List.of());entry.ack=store.append(entry.batch).toCompletableFuture();
 }
 private void pollRegionalTranches(){
  for(var pair:List.copyOf(regionalTranches.entrySet())){var entry=pair.getValue();
   if(entry.ack!=null){if(!entry.ack.isDone())continue;var ack=entry.ack.getNow(null);
    if(ack==null||ack.ackStatus()!=AckStatus.DURABLE||!ack.transactionId().equals(entry.batch.transactionId())||!ack.transactionHash().equals(entry.batch.transactionHash())||!ack.storeGeneration().equals(session.storeGeneration())||ack.durableSequence().value()<1||!ack.forcedBlobs().isEmpty()||ack.permitAttempt().isPresent())throw new IllegalStateException("regional global reservation ACK uncertain");
    entry.ack=null;entry.durable=true;if(entry.releasing){if(!arbiter.release(entry.token))throw new IllegalStateException("regional global reservation still in use");regionalTranches.remove(pair.getKey());continue;}
   }
   if(entry.lent&&!arbiter.hasBorrowed(entry.token)&&!entry.releasing){entry.releasing=true;persistRegional(entry,"RELEASED");}
  }
 }
 public void retainHistoricalPhysical(PhysicalSitePin pin){
  actor();if(pin.resources().isEmpty()||historicalPhysical.containsKey(pin.event()))return;
  if(!pin.catalog().key().directorWorld().equals(session.worldId())||!pin.catalog().session().storeGeneration().equals(session.storeGeneration()))throw new IllegalArgumentException("historical logical pin world/generation differs");
  if(store.physicalEventReadback(pin.event(),4096).filter(PhysicalEventReadback::released).isPresent())return;
  historicalPhysical.put(pin.event(),arbiter.retainPhysicalResources(pin.event(),pin.resources()));
 }
 public void retireHistoricalPhysical(EventInstanceId event){
  actor();var token=historicalPhysical.get(event);if(token==null)return;
  if(store.physicalEventReadback(event,4096).filter(PhysicalEventReadback::released).isEmpty())throw new IllegalStateException("historical D0 ownership still unresolved");
  if(!arbiter.release(token))throw new IllegalStateException("historical logical pin release refused");historicalPhysical.remove(event);
 }
 public List<ResourceClaim> claims(EventInstanceId event){actor();var entry=held.get(event);return entry==null?List.of():entry.token.claims();}
 public boolean pendingWrites(){actor();return held.values().stream().anyMatch(e->e.pending!=null)||regionalTranches.values().stream().anyMatch(e->e.ack!=null);}
 private Map<ScopeRef,BudgetVector> capacities(DecisionInput input){
  var index=store.residentBudgetAggregateKeys(4096).orElseThrow();if(!index.session().equals(session)||index.keys().size()>1024)throw new IllegalStateException("budget heads not resident");
  var keys=index.keys().isEmpty()?Set.of(new AggregateKey(AggregateKind.WORLD,session.worldId().value())):index.keys();
  var query=new StoreQuery(session.worldId(),keys,new Sequence(0),Optional.empty(),1024);var read=store.view(query);ReadContracts.validate(read,query,session);
  if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().hasMore()||!read.page().orElseThrow().lastDurableSequence().equals(index.cut()))throw new IllegalStateException("budget cut changed");
  var heads=read.page().orElseThrow().aggregates().stream().map(RestoredAggregate::state).toList();
  if(!CatalogueBudgetProjection.from(input.config(),heads).equals(input.state().budgets()))throw new IllegalStateException("mixed budget cut");
  var mutations=new HashSet<AggregateKey>();for(var state:heads)if(state instanceof OwnershipAggregate owner)mutations.add(new AggregateKey(AggregateKind.MUTATION,owner.value().creationMutationId().value()));
  var coveredBlocks=new HashSet<ResourceKey>();
  if(!mutations.isEmpty()){
   var lookup=new StoreQuery(session.worldId(),mutations,new Sequence(0),Optional.empty(),1024);var joined=store.view(lookup);ReadContracts.validate(joined,lookup,session);
   if(joined.status()!=ReadStatus.AVAILABLE||joined.page().orElseThrow().hasMore()||!joined.page().orElseThrow().lastDurableSequence().equals(index.cut())||joined.page().orElseThrow().aggregates().size()!=mutations.size())throw new IllegalStateException("budget mutation join unavailable");
   var coveredIds=new HashSet<MutationId>();for(var row:joined.page().orElseThrow().aggregates())if(row.state() instanceof MutationAggregate mutation&&held.containsKey(mutation.value().instanceId()))coveredIds.add(mutation.value().mutationId());
   for(var state:heads)if(state instanceof OwnershipAggregate owner&&coveredIds.contains(owner.value().creationMutationId()))coveredBlocks.add(owner.value().resourceKey());
  }
  var external=heads.stream().filter(state->!(state instanceof EntityOwnershipAggregate entity&&held.containsKey(entity.value().instanceId())))
   .filter(state->!(state instanceof OwnershipAggregate owner&&coveredBlocks.contains(owner.value().resourceKey()))).toList();
  var debits=CatalogueBudgetProjection.from(input.config(),external);var remaining=SelectedEventAdmission.available(debits);
  double factor=input.config().values().get("pacing."+input.state().world().pacingState().name().toLowerCase(Locale.ROOT)+"_intensity_factor").value().doubleValue();
  var c=remaining;int regions=Math.max(1,debits.scopes().size());
  var worldCap=new BudgetVector(c.tickMillis(),c.globalIntensity()*factor,c.regionalIntensity()*factor*regions,c.liveEntities(),c.persistentEntities(),c.mutationsInFlight(),c.queuedIoBytes(),c.clientUnits(),c.payloadBytes(),c.navigationNodes());
  var result=new HashMap<ScopeRef,BudgetVector>();result.put(new ScopeRef(ScopeKind.WORLD,session.worldId().value()),worldCap);
  for(var scope:debits.scopes().keySet())result.put(scope,new BudgetVector(0,0,c.regionalIntensity()*factor,0,0,0,0,0,0,0));return Map.copyOf(result);
 }
 private static BudgetVector maximum(BudgetVector a,BudgetVector b){return new BudgetVector(Math.max(a.tickMillis(),b.tickMillis()),Math.max(a.globalIntensity(),b.globalIntensity()),Math.max(a.regionalIntensity(),b.regionalIntensity()),Math.max(a.liveEntities(),b.liveEntities()),Math.max(a.persistentEntities(),b.persistentEntities()),Math.max(a.mutationsInFlight(),b.mutationsInFlight()),Math.max(a.queuedIoBytes(),b.queuedIoBytes()),Math.max(a.clientUnits(),b.clientUnits()),Math.max(a.payloadBytes(),b.payloadBytes()),Math.max(a.navigationNodes(),b.navigationNodes()));}
}
