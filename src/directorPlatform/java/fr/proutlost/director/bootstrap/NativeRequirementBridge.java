package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.quest.DurableQuestAdapter;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Explicit native LAB REQUIRE producer. No quest mod, client spoof, or world effect authority.
 * Minecraft access stays on the actor. The isolated adapter executor sees immutable DTOs only.
 */
public final class NativeRequirementBridge implements AutoCloseable {
 public enum GateStatus { WAITING,READY,REJECTED }
 public record GateResult(GateStatus status,String detail){}
 public record RequestSnapshot(StoreSession session,Sequence cut,List<RequestView> requests){
  public RequestSnapshot{requests=List.copyOf(requests);}
 }
 private record Submission(UUID actor,CompletableFuture<RequestAck> future){}
 private static final ResourceId PRODUCER=new ResourceId("proutlost_director:native_lab_require");
 private static final ResourceId OUTBOX_PRODUCER=new ResourceId("proutlost_director:native_catalogue");
 private static final FactType ABORTED=new FactType("proutlost_director:catalogue_event_aborted");
 private static final int MAX_PENDING=32;
 private final MinecraftServer server;private final WorldId world;private final JournalStore store;
 private final EventDefinition signal;private final Set<DefinitionId> selectors;
 private final StoreSession session;private final ConsumerId consumer;
 private final AtomicLong clock=new AtomicLong();private final ThreadPoolExecutor executor;
 private final DurableQuestAdapter adapter;
 private CompletableFuture<Void> boot,sync;private CompletableFuture<Sequence> pump;
 private final Map<RequestId,Submission> submissions=new LinkedHashMap<>();
 private static final class Binding {Set<RequestId> ids;CompletableFuture<Void> future;}
 private final Map<EventInstanceId,Binding> bindings=new HashMap<>();
 private boolean ready,closed,failed;private long nextPump,nextRestore;
 private String detail="RESTORING";
 public NativeRequirementBridge(MinecraftServer server,WorldId world,JournalStore store,EventDefinition signal){
  this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.store=Objects.requireNonNull(store);
  this.signal=Objects.requireNonNull(signal);actor();session=store.session();
  if(!(signal.eligibility() instanceof RequirementPending pending))throw new IllegalArgumentException("exact REQUIRE event required");
  var ids=new HashSet<DefinitionId>();ids.add(signal.id());ids.add(pending.selector());selectors=Set.copyOf(ids);
  consumer=new ConsumerId(stable("native-require-consumer-v1/"+world.value()+"/"+PRODUCER.value()));
  clock.set(server.overworld().getGameTime());
  executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64),task->{
   var thread=new Thread(task,"proutlost-director-native-require");thread.setDaemon(true);return thread;
  },new ThreadPoolExecutor.AbortPolicy());
  adapter=new DurableQuestAdapter(store,store,PRODUCER,executor,clock::get,64,.5);
  boot=adapter.restoreHydrated().toCompletableFuture();
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("native REQUIRE server actor");}
 private static UUID stable(String text){return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));}
 private static boolean terminal(RequestStatus status){return Set.of(RequestStatus.FULFILLED,RequestStatus.CANCELLED,RequestStatus.CONFLICT,RequestStatus.EXPIRED).contains(status);}
 private static Throwable cause(Throwable error){while(error.getCause()!=null)error=error.getCause();return error;}
 private static void require(boolean value,String reason){if(!value)throw new IllegalStateException(reason);}
 private List<RequestView> active(){return adapter.snapshot().stream().filter(v->!terminal(v.state().status())).toList();}
 private boolean available(){return ready&&adapter.isReady()&&!failed&&!store.readOnlySafe()&&session.equals(store.session());}
 public void poll(Tick actorTick){poll(actorTick,true);}
 /** Stop new bus work while the caller owns the EVENT chronology scan lane.
  * Existing writes and their actual ACKs still drain on the server actor. */
 public void poll(Tick actorTick,boolean allowNewPump){
  actor();clock.accumulateAndGet(actorTick.value(),Math::max);
  if(!session.equals(store.session())||store.readOnlySafe()){failed=true;detail="J04_UNAVAILABLE";return;}
  if(!ready){
   if(boot==null&&clock.get()>=nextRestore)boot=adapter.restoreHydrated().toCompletableFuture();
   if(boot==null||!boot.isDone())return;
   try{boot.join();ready=true;detail="READY";}catch(CompletionException failure){
    var reason=cause(failure);if(String.valueOf(reason.getMessage()).contains("STALE")){
     detail="WAIT_STABLE_RESTORE_CUT";boot=null;nextRestore=clock.get()+20;return;
    }
    failed=true;detail="RESTORE_FAILED "+reason;return;
   }
  }
  if(sync!=null&&sync.isDone()){try{sync.join();sync=null;}catch(CompletionException error){failed=true;detail="REQUEST_PROJECTION_FAILED "+cause(error);return;}}
  for(var iterator=submissions.entrySet().iterator();iterator.hasNext();){
   var entry=iterator.next();if(!entry.getValue().future().isDone())continue;
   String response;try{var ack=entry.getValue().future().join();response="REQUIRE "+ack.requestId().value()+" "+ack.state()+" revision="+ack.revision().value()+" reasons="+ack.reasons();}
   catch(CompletionException error){response="REQUIRE_PENDING_RECOVERY "+entry.getKey().value()+" "+cause(error);}
   var actor=server.getPlayerList().getPlayer(entry.getValue().actor());if(actor!=null)actor.sendSystemMessage(Component.literal("[World Director] "+response));
   iterator.remove();
  }
  bindings.entrySet().removeIf(entry->entry.getValue().future.isDone()&&!entry.getValue().future.isCompletedExceptionally()
   &&entry.getValue().ids.stream().allMatch(id->terminal(adapter.inspect(id).state().status())||!adapter.boundExecution(id,entry.getKey())));
  if(pump!=null){
   if(!pump.isDone())return;
   try{pump.join();pump=null;detail="READY";}catch(CompletionException error){failed=true;detail="FACT_BUS_RECOVERY_REQUIRED "+cause(error);return;}
  }
  if(!allowNewPump||sync!=null||clock.get()<nextPump)return;nextPump=clock.get()+20;
  var cursor=store.consumerCursor(consumer);if(cursor.isEmpty()||!cursor.get().session().equals(session))return;
  var expected=cursor.get().cursor();var spec=new ConsumerSpec(consumer,Set.of(signal.completion().factType(),ABORTED),expected.plus(1),16);
  pump=store.readSubscription(spec).thenCompose(read->{
   ReadContracts.validate(read,spec,session);
   require(session.equals(store.session())&&read.status()==ReadStatus.AVAILABLE,"native requirement outbox read unavailable");
   var page=read.page().orElseThrow();var through=read.stamp().orElseThrow().scannedThrough();
   if(through.equals(expected))return CompletableFuture.completedFuture(expected);
   var ids=active().stream().map(v->v.state().requestId()).collect(java.util.stream.Collectors.toSet());
   return adapter.refreshDurable(ids).thenCompose(ignored->{
    CompletionStage<Void> consumed=CompletableFuture.completedFuture(null);
    for(var envelope:page.batch())for(var request:active()){
     var id=request.state().requestId();
     consumed=consumed.thenCompose(v->((envelope.payload() instanceof FactPayload fact&&fact.value().factType().equals(ABORTED))
      ?adapter.acceptExecutionAbort(id,envelope):adapter.acceptFact(id,envelope)).thenApply(ack->{
      require(!ack.reasons().contains(fr.proutlost.director.api.error.ReasonCode.WAL_UNAVAILABLE),"native requirement fulfillment not durable");return (Void)null;
     }));
    }
    return consumed;
   }).thenCompose(ignored->store.acknowledge(new ConsumerAck(consumer,page.batch().stream().map(DirectorEnvelope::messageId)
      .collect(java.util.stream.Collectors.toSet()),through,expected))).thenApply(ack->{
    require(ack.status()==AckStatus.DURABLE&&ack.consumerId().equals(consumer)&&ack.cursor().equals(through),"native requirement bus ACK uncertain");return through;
   });
  }).toCompletableFuture();
 }
 /** The pump includes all adapter writes AND its final bus cursor ACK.
  * Read-only restore/refresh work does not prevent a chronology scan. */
 public boolean writesInFlight(){
  actor();return pump!=null&&!pump.isDone()
   ||submissions.values().stream().anyMatch(s->!s.future().isDone())
   ||bindings.values().stream().anyMatch(b->!b.future.isDone());
 }
 /** Expose original payloads plus latest durable scheduler states at an exact J04 cut. */
 public Optional<RequestSnapshot> snapshot(){
  actor();if(!available()||sync!=null)return Optional.empty();
  var views=adapter.snapshot();if(views.size()>128){detail="REQUEST_PROJECTION_CAPACITY";return Optional.empty();}
  var keys=views.stream().map(v->new AggregateKey(AggregateKind.REQUEST,v.state().requestId().value())).collect(java.util.stream.Collectors.toSet());
  // Empty query keys mean chronological WAL, so use WORLD for a genuine cut when no requests exist.
  if(keys.isEmpty())keys=Set.of(new AggregateKey(AggregateKind.WORLD,world.value()));
  var query=new StoreQuery(world,keys,new Sequence(0),Optional.empty(),Math.max(1,keys.size()));
  var read=store.view(query);ReadContracts.validate(read,query,session);
  if(read.status()!=ReadStatus.AVAILABLE)return Optional.empty();
  var page=read.page().orElseThrow();
  if(!views.isEmpty()){
   var current=new HashMap<RequestId,DirectorRequestState>();
   for(var row:page.aggregates())if(row.state() instanceof RequestAggregate request&&row.committedSequence().value()>0)current.put(request.value().requestId(),request.value());
   boolean same=current.size()==views.size()&&views.stream().allMatch(v->v.state().equals(current.get(v.state().requestId())));
   if(!same){sync=adapter.refreshDurable(views.stream().map(v->v.state().requestId()).collect(java.util.stream.Collectors.toSet())).toCompletableFuture();detail="WAIT_DURABLE_REQUEST_REFRESH";return Optional.empty();}
  }
  return Optional.of(new RequestSnapshot(session,page.lastDurableSequence(),views));
 }
 public String requireSignal(ServerPlayer player,String producerKey){
  actor();if(!Boolean.getBoolean("proutlost.director.nativeLab")||!player.hasPermissions(4)||!player.isAlive()
    ||server.getPlayerList().getPlayer(player.getUUID())!=player)return "Joueur connecte OP4 vivant et laboratoire requis.";
  if(!available())return "REQUIRE_NOT_READY "+detail;
  if(producerKey==null||!producerKey.matches("[A-Za-z0-9_.:-]{1,80}"))return "Cle producteur: 1..80 caracteres A-Z a-z 0-9 _ . : -";
  var block=player.blockPosition();var at=new BlockPosition(new DimensionId(player.serverLevel().dimension().location().toString()),block.getX(),block.getY(),block.getZ());
  var region=DirectorStandaloneMod.directorLifecycle().labPrimaryRegion(server,at);if(region.isEmpty())return "WAIT_ONE_REAL_DURABLE_REGION";
  var key=player.getUUID()+"/"+producerKey;var id=new RequestId(stable("native-require-v1/"+world.value()+"/"+key));
  if(submissions.containsKey(id))return "REQUIRE_PENDING "+id.value()+"; demande existante non dupliquee.";
  boolean known=adapter.snapshot().stream().anyMatch(view->view.state().requestId().equals(id));
  if(submissions.size()>=MAX_PENDING||!known&&active().size()>=MAX_PENDING)return "REQUIRE_BACKPRESSURE";
  var playerScope=new ScopeRef(ScopeKind.PLAYER,player.getUUID());
  var requirement=new DirectorRequirement(id,new AnyOfEvents(selectors),new TargetConstraints(Set.of(region.get()),Set.of(new PlayerId(player.getUUID()))),
   new FactMatch(signal.completion().factType(),playerScope,EvidenceStatus.CONFIRMED,Map.of()),FulfillmentMode.NEW_EXECUTION);
  var request=new DirectorRequest(new MessageId(stable("native-require-message-v1/"+world.value()+"/"+key)),world,PRODUCER,key,playerScope,
   new CorrelationId(stable("native-require-correlation-v1/"+world.value()+"/"+key)),Optional.empty(),new Sequence(1),new Tick(clock.get()),requirement);
  submissions.put(id,new Submission(player.getUUID(),adapter.submit(request).toCompletableFuture()));
  return "REQUIRE_PENDING "+id.value()+"; ACK J04 puis selection catalogue, aucune promesse de realisation.";
 }
 /** Invoked after decision journal ACK, before invoking any selected physical provider. */
 public GateResult gate(SelectedEventStart selected){
  actor();if(!selected.definition().id().equals(signal.id()))return new GateResult(GateStatus.READY,"NO_NATIVE_REQUIREMENT");
  if(!available()||sync!=null)return new GateResult(GateStatus.WAITING,detail);
  var binding=bindings.get(selected.eventId());
  if(binding==null){
   var ids=new HashSet<RequestId>();
   for(var change:selected.proposal().stateChanges())if(change.next() instanceof RequestAggregate request
      &&request.value().producerId().equals(PRODUCER)&&request.value().kind()==RequestKind.REQUIRE
      &&request.value().status()==RequestStatus.SCHEDULED)ids.add(request.value().requestId());
   if(ids.size()!=1)return new GateResult(GateStatus.REJECTED,"ONE_ACTUAL_SCHEDULED_REQUIREMENT_REQUIRED");
   for(var id:ids){
    var original=selected.captured().requests().stream().filter(v->v.state().requestId().equals(id)).findFirst();
    if(original.isEmpty()||!selected.plan().scopes().scopes().contains(original.get().request().scope())
       ||!selected.participants().containsAll(original.get().state().constraints().playerIds())
       ||!original.get().state().constraints().regionIds().stream().allMatch(region->selected.plan().scopes().scopes().contains(new ScopeRef(ScopeKind.REGION,region.value()))))
     return new GateResult(GateStatus.REJECTED,"REQUIREMENT_SELECTED_TARGET_MISMATCH");
   }
   if(bindings.size()>=128)return new GateResult(GateStatus.REJECTED,"REQUIRE_BINDING_CAPACITY");
   binding=new Binding();binding.ids=Set.copyOf(ids);
   binding.future=adapter.refreshDurable(ids).thenCompose(ignored->{
    CompletionStage<Void> sequence=CompletableFuture.completedFuture(null);
    for(var id:ids)sequence=sequence.thenCompose(v->{
     var current=adapter.inspect(id);require(current.state().status()==RequestStatus.SCHEDULED,"scheduled request state required");
     return adapter.bindExecution(id,current.revision(),selected.eventId()).thenApply(ack->{
      require(ack.reasons().isEmpty()&&ack.state()==RequestStatus.SCHEDULED&&adapter.boundExecution(id,selected.eventId()),"execution binding not durable");return (Void)null;
     });
    });return sequence;
   }).toCompletableFuture();
   bindings.put(selected.eventId(),binding);
  }
  if(!binding.future.isDone())return new GateResult(GateStatus.WAITING,"WAITING_FOR_REQUIRE_BINDING");
  try{binding.future.join();}catch(CompletionException error){return new GateResult(GateStatus.REJECTED,"REQUIRE_BINDING_FAILED "+cause(error));}
  return executionStillBound(selected)?new GateResult(GateStatus.READY,"ACTUAL_REQUIRE_BINDING_DURABLE")
   :new GateResult(GateStatus.REJECTED,"REQUIREMENT_NO_LONGER_BOUND");
 }
 /** Replaces only RequirementPending after its scheduled/bound decision; no other eligibility bypass. */
 public boolean executionStillBound(SelectedEventStart selected){
  actor();if(!available())return false;if(!selected.definition().id().equals(signal.id()))return true;
  var binding=bindings.get(selected.eventId());if(binding==null||!binding.future.isDone()||binding.future.isCompletedExceptionally())return false;
  return binding.ids.stream().allMatch(id->{var request=adapter.inspect(id);return
   Set.of(RequestStatus.SCHEDULED,RequestStatus.EXECUTING).contains(request.state().status())&&adapter.boundExecution(id,selected.eventId());});
 }
 /** Actual request IDs only after the real binding ACK and current nonterminal binding fence. */
 public Set<RequestId> boundRequirements(SelectedEventStart selected){
  actor();var binding=bindings.get(selected.eventId());
  return binding!=null&&executionStillBound(selected)?Set.copyOf(binding.ids):Set.of();
 }
 /** Original fact and its real bus envelope are committed in the SAME physical RESULT/END batch. */
 public List<DurableRecord> completionRecords(SelectedEventStart selected,Map<String,JsonValue> actualEvidence){
  actor();return withOutbox(selected,selected.originalCompletionRecords(actualEvidence),"completion");
 }
 public List<DurableRecord> abortRecords(SelectedEventStart selected,String reason,Map<String,JsonValue> evidence){
  actor();return withOutbox(selected,selected.originalAbortRecords(reason,evidence),"abort");
 }
 private List<DurableRecord> withOutbox(SelectedEventStart selected,List<DurableRecord> original,String kind){
  var records=new ArrayList<>(original);
  for(var row:List.copyOf(records))if(row instanceof DurableFact fact)
   records.add(NativeCatalogueFactOutbox.create(fact.value(),selected.proposal().proposalId(),new Tick(clock.get()),kind));
  return List.copyOf(records);
 }
 public String status(){actor();return "REQUIRE "+detail+" ready="+available()+" active="+active().size()+" submissions="+submissions.size()+" bindings="+bindings.size();}
 public CompletionStage<Void> closeAsync(){
  actor();closed=true;
  var pending=new ArrayList<CompletableFuture<?>>();if(boot!=null)pending.add(boot);if(sync!=null)pending.add(sync);if(pump!=null)pending.add(pump);
  submissions.values().forEach(s->pending.add(s.future()));bindings.values().forEach(b->pending.add(b.future));
  return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).handle((v,e)->null)
   .thenCompose(v->adapter.drain()).whenComplete((v,e)->executor.shutdown());
 }
 @Override public void close(){closeAsync();}
}
