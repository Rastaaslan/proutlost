package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Explicit OP4 LAB delivery of actual bundled perception events.
 * Natural eligibility, cooldowns and player opt-in journal entries are not fabricated.
 * Recovery closes interrupted deliveries without ever replaying sound/visual exposure.
 */
public final class DirectorNativePerceptionGameplay implements AutoCloseable {
 private final DirectorRuntimeContent content;
 private static final FactType START=new FactType("proutlost_director:lab_perception_start");
 private static final FactType END=new FactType("proutlost_director:lab_perception_end");
 private static final BudgetVector CAP=new BudgetVector(100,100,100,100,100,100,100,100,100,100);
 private final MinecraftServer server;private final WorldId world;private final JournalStore store;
 private final CatalogSnapshot catalog;
 private final Map<UUID,DirectorFact> starts=new LinkedHashMap<>();
 private final Set<UUID> ends=new HashSet<>();
 private final Map<OperationToken,PrepareRecord> unmatched=new LinkedHashMap<>();
 private final Set<OperationToken> resolved=new HashSet<>();
 private Optional<String> cursor=Optional.empty();
 private CompletableFuture<StoreReadResult> pendingRead;
 private CompletableFuture<CommitAck> recoveryAck;
 private UUID recoveringEvent;
 private long generationFloor;
 private boolean scanned,closed,failed;
 private Flight flight;private DirectorNativeChainGameplay chainCompletion;
 public void chainCompletion(DirectorNativeChainGameplay owner){actor();if(chainCompletion!=null&&chainCompletion!=owner)throw new IllegalStateException("chain owner changed");chainCompletion=owner;}
 public Optional<UUID> activeEvent(){actor();return flight==null?Optional.empty():Optional.of(flight.id);}
 private enum Phase { DOSSIER, DELAY, APPLYING, ABORTING, RECEIPT, LIVE, END }
 private static final class Flight {
  UUID id,player;EventDefinition definition;BlockPosition position;double yaw;SelectedEventStart selected;
  long started,delayUntil,expires,receiptDeadline;long qualifiedAt=-1;int index;Phase phase;boolean stopRequested;
  CompletableFuture<CommitAck> ack;Execution work;
  Set<String> completed=new HashSet<>();
  Map<String,PerceptionHeader> receipts=new LinkedHashMap<>();
  List<NativePerceptionBinding> bindings=new ArrayList<>();
  List<Execution> executions=new ArrayList<>();
 }
 private static final class Execution {
  EventRunner runner;TransactionId tx;PreparedEffect prepared;LiveValidationSnapshot live;
  NativePerceptionBinding binding;ResourceArbiter arbiter;ReservationToken token;
  AdmissionService admission;DecisionId decision;boolean committed;PrepareRecord prepare;
  EventRunner.Stage stage=EventRunner.Stage.PREPARING;
  DecisionInput applyingInput;StoreSession applyingSession,session;
 }
 public DirectorNativePerceptionGameplay(MinecraftServer server,WorldId world,JournalStore store){
  this.content=null;
  this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.store=Objects.requireNonNull(store);
  catalog=new DirectorLabPerceptionCatalog(DirectorLabObservationProfile.load()).snapshot();actor();
 }
 public DirectorNativePerceptionGameplay(MinecraftServer server,WorldId world,JournalStore store,DirectorRuntimeContent content){
  this.content=Objects.requireNonNull(content);
  this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.store=Objects.requireNonNull(store);
  catalog=content.catalog();actor();
 }
 private ProviderMode providerMode(){return content==null?ProviderMode.TEST_ONLY:content.mode();}
 private void requireLaboratory(){if(content!=null)content.requireLaboratory();}
 private void requireSelected(SelectedEventStart selected){
  Objects.requireNonNull(selected);
  if(selected.mode()!=providerMode()||(content!=null&&!selected.definition().equals(content.definition(selected.definition().id()))))
   throw new IllegalArgumentException("selected runtime content/mode mismatch");
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("perception LAB actor");}
 private Tick now(){return new Tick(fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server));}
 private CompletableFuture<CommitAck> append(List<DurableRecord> records){return append(records,List.of());}
 private CompletableFuture<CommitAck> append(List<DurableRecord> records,List<BlobContent> blobs){
  return store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),
   store.session().storeGeneration(),Map.of(),records,blobs)).toCompletableFuture();
 }
 private boolean durable(CompletableFuture<CommitAck> future){
  if(future==null||!future.isDone())return false;
  if(future.isCompletedExceptionally()||future.getNow(null)==null||
     future.getNow(null).ackStatus()!=AckStatus.DURABLE)throw new IllegalStateException("perception J04 ACK unavailable");
  return true;
 }
 private DirectorFact fact(FactType type,UUID id,Map<String,JsonValue> data){
  return new DirectorFact(new FactId(UUID.randomUUID()),type,
   List.of(new ScopeRef(ScopeKind.WORLD,world.value())),Optional.of(new EventInstanceId(id)),
   EvidenceStatus.CONFIRMED,data,true,Optional.empty());
 }
 private ServerPlayer player(Flight f){
  var p=server.getPlayerList().getPlayer(f.player);
  if(p==null||!p.isAlive()||f.selected==null&&!p.hasPermissions(4)||f.selected!=null&&(p.isSpectator()||p.isCreative())||
   !p.serverLevel().dimension().location().toString().equals(f.position.dimension().value()))return null;
  return p;
 }
 private boolean loaded(Flight f){
  var p=player(f);
  return p!=null&&p.serverLevel().getChunkSource().getChunkNow(f.position.x()>>4,f.position.z()>>4)!=null;
 }
 public boolean selectedAvailable(){actor();return scanned&&!failed&&recoveryAck==null&&!store.readOnlySafe()&&flight==null&&starts.isEmpty();}
 public DirectorNativeCatalogueExecution.Preview preview(Candidate candidate,DecisionInput input,EventInstanceId event) {
  actor();if(!selectedAvailable())throw new IllegalStateException("perception recovery/active");
  var definition=Objects.requireNonNull(input.catalog().events().get(candidate.definition().id()));
  if(!catalog.events().containsKey(definition.id())||definition.steps().stream().anyMatch(step->!(step.args() instanceof TargetSound||step.args() instanceof Apparition||step.args() instanceof LocalWeather)))throw new IllegalArgumentException("not a perception composition");
  var participants=NativeCataloguePlanning.players(candidate);
  if(participants.size()!=1)throw new IllegalArgumentException("original perception SOLO role required");
  var target=participants.iterator().next();var nativePlayer=server.getPlayerList().getPlayer(target.value());
  var observed=input.context().context().players().get(target);
  if(nativePlayer==null||observed==null||!nativePlayer.isAlive()||nativePlayer.isSpectator()||nativePlayer.isCreative())
   throw new IllegalArgumentException("actual eligible perception recipient absent");
  var p=observed.position().position();var footprint=NativeCataloguePlanning.point(p);
  if(nativePlayer.serverLevel().getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)==null)
   throw new IllegalArgumentException("perception site unloaded");
  if(!fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,target,definition.steps().size()))
   throw new SelectedEventStart.ContextUnavailable("perception session capacity/handshake pending");
  var preparations=new ArrayList<EffectPreparation>();
  for(var step:definition.steps()) {
   int ttl=step.args() instanceof TargetSound?80:step.args() instanceof LocalWeather?600:
    step.args() instanceof Apparition a?(int)Math.max(200,a.followTicks().value()):0;
   ttl=(int)Math.min(ttl,definition.timing().maxDurationTicks().value());
   var binding=new NativePerceptionBinding(server,target,step.primitive(),step.args(),
    new PerceptionTransform(p.x()+.5,p.y()+.1,p.z()+.5,net.minecraft.util.Mth.wrapDegrees(180-nativePlayer.getYRot()),0,
     step.args() instanceof LocalWeather?6:2),ttl);
   preparations.add(binding.capture(NativeCataloguePlanning.intent(candidate,definition,step,event,footprint,input),NativeCataloguePlanning.raw(input)));
  }
  return NativeCataloguePlanning.captured(candidate,input,event,preparations,participants);
 }
 public String beginSelected(SelectedEventStart selected) {requireSelected(selected);
  actor();if(!selectedAvailable())throw new IllegalStateException("perception recovery/active");
  var current=selected.refresh();var definition=selected.definition();
  if(!catalog.events().containsKey(definition.id())||selected.participants().size()!=1
       ||definition.steps().stream().anyMatch(step->!(step.args() instanceof TargetSound||step.args() instanceof Apparition||step.args() instanceof LocalWeather)))
   throw new IllegalArgumentException("actual original perception definition/roles required");
  var playerId=selected.participants().iterator().next();var player=server.getPlayerList().getPlayer(playerId.value());
  if(player==null||!player.isAlive()||player.isSpectator()||player.isCreative())throw new IllegalArgumentException("actual recipient lost");
  if(!fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,playerId,definition.steps().size()))
   throw new SelectedEventStart.ContextUnavailable("perception session capacity/handshake pending");
  var footprint=selected.plan().effects().getFirst().expectedFootprint();
  if(footprint.cells().size()!=1||selected.plan().effects().stream().anyMatch(i->!i.expectedFootprint().equals(footprint)))
   throw new IllegalArgumentException("captured perception footprint changed");
  Flight f=new Flight();f.id=selected.eventId().value();f.player=playerId.value();f.definition=definition;f.selected=selected;
  f.position=footprint.cells().iterator().next();f.yaw=net.minecraft.util.Mth.wrapDegrees(180-player.getYRot());f.started=now().value();
  for(var step:definition.steps())binding(f,step);
  f.ack=append(List.of(new DurableFact(fact(START,f.id,Map.of(
   "definition",new JsonValue.Text(definition.id().value()),"player",new JsonValue.Text(f.player.toString()),
   "selected_plan",new JsonValue.Text(Base64.getEncoder().encodeToString(new fr.proutlost.director.api.codec.ModelCodec<>(ExecutionPlan.class).encode(selected.plan()))))))));
  f.phase=Phase.DOSSIER;flight=f;
  return "Catalogue perception accepted event="+f.id+" definition="+definition.id().value();
 }
 public String begin(ServerPlayer player,String name){return begin(player,name,UUID.randomUUID());}
 public String begin(ServerPlayer player,String name,UUID requestedEvent){requireLaboratory();
  actor();
  if(!scanned||failed||recoveryAck!=null||store.readOnlySafe())return "Perceptions en attente de récupération J04.";
  if(flight!=null)return "Une perception LAB est déjà active.";
  var definition=catalog.events().get(new DefinitionId("proutlost_director:candidate/"+name));
  if(definition==null||!player.hasPermissions(4))return "Événement LAB inconnu ou accès refusé.";
  if(!fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,new PlayerId(player.getUUID()),definition.steps().size()))
   return "Perception en attente de renouvellement de session ou de capacité J06.";
  var look=player.getLookAngle();var p=BlockPos.containing(player.getX()+look.x*2.5,player.getY(),player.getZ()+look.z*2.5);
  if(player.serverLevel().getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)==null)
   return "La cellule de perception doit être chargée.";
  Flight f=new Flight();f.id=Objects.requireNonNull(requestedEvent);f.player=player.getUUID();f.definition=definition;
  f.position=new BlockPosition(new DimensionId(player.serverLevel().dimension().location().toString()),p.getX(),p.getY(),p.getZ());
  f.yaw=net.minecraft.util.Mth.wrapDegrees(180-player.getYRot());f.started=now().value();
  // Every real client asset required by this event must already be negotiated before its dossier.
  for(var step:definition.steps())binding(f,step);
  f.ack=append(List.of(new DurableFact(fact(START,f.id,Map.of(
   "definition",new JsonValue.Text(definition.id().value()),"player",new JsonValue.Text(f.player.toString()))))));
  f.phase=Phase.DOSSIER;flight=f;
  System.out.println("DIRECTOR_LAB_PERCEPTION_BEGIN event="+name+" instance="+f.id+" manual=true");
  return name+" : livraison J06 préparée sous journal durable.";
 }
 private long deadline(Flight f){return Math.addExact(f.started,f.definition.timing().maxDurationTicks().value());}
 private boolean expired(Flight f){return now().value()>=deadline(f);}
 private int ttl(Flight f,EventStep step){
  long wanted=switch(step.args()){
   case TargetSound ignored->80;case LocalWeather ignored->600;
   case Apparition a->Math.max(200,a.followTicks().value());
   default->throw new IllegalArgumentException("perception step");
  };
  long remaining=f.definition.timing().maxDurationTicks().value()-(now().value()-f.started);
  long bounded=Math.min(wanted,remaining);
  if(bounded<1||bounded>1200||step.args() instanceof Apparition a&&bounded<a.followTicks().value())
   throw new IllegalStateException("perception duration exhausted");
  return (int)bounded;
 }
 private NativePerceptionBinding binding(Flight f,EventStep step){
  double scale=step.args() instanceof LocalWeather?6:2;
  return new NativePerceptionBinding(server,new PlayerId(f.player),step.primitive(),step.args(),
   new PerceptionTransform(f.position.x()+.5,f.position.y()+.1,f.position.z()+.5,f.yaw,0,scale),ttl(f,step));
 }
 private EffectPreparation capture(Flight f,EventStep step,NativePerceptionBinding binding){
  if(f.selected!=null){
   var actual=f.selected.refresh();var intent=f.selected.effect(step.id());
   return binding.capture(intent,NativeCataloguePlanning.raw(actual));
  }
  var p=f.position;
  var footprint=new Footprint(p.dimension(),Set.of(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4)),Set.of(p),List.of());
  var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of());
  var sample=new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),now(),
   Map.of(),context,Set.of(),footprint.loadedChunks(),HealthMode.NORMAL);
  var intent=new EffectIntent(new OperationToken(UUID.randomUUID()),step.primitive(),1,step.args(),footprint,new EventInstanceId(f.id));
  return binding.capture(intent,new PlanningSnapshot(sample,new OwnershipView(Map.of()),
   new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of())));
 }
 private Execution execute(Flight f,EventStep step){
  if(!loaded(f)||!f.completed.containsAll(step.after()))throw new IllegalStateException("perception dependency/player missing");
  var binding=binding(f,step);var captured=capture(f,step,binding);var prepared=captured.prepared().orElseThrow();
  Execution e=new Execution();e.binding=binding;e.prepared=prepared;e.session=store.session();
  e.arbiter=new ResourceArbiter(world,new Epoch(Math.max(generationFloor,store.session().bootEpoch().value())),
   1000,4,new ResourceArbiter.Compatibility(){
    public boolean share(ResourceClaim a,ResourceClaim b){return false;}
    public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}
   });
  var scope=new ScopeRef(ScopeKind.WORLD,world.value());
  var roles=f.selected==null?new RoleAssignments(Map.of("witness",List.of(new PlayerId(f.player)))):f.selected.plan().roles();
  var definition=f.definition;
  var ref=new DefinitionRef(definition.id(),definition.contentRevision(),
   BlobContent.hash(new fr.proutlost.director.api.codec.ModelCodec<>(EventDefinition.class).encode(definition)));
  var plan=new ExecutionPlan(f.selected==null?new CandidateId(UUID.randomUUID()):f.selected.plan().candidateId(),ref,List.of(prepared.intent()),roles,
   f.selected==null?new ScopeSet(world,Set.of(scope,new ScopeRef(ScopeKind.PLAYER,f.player))):f.selected.plan().scopes(),prepared.preconditions());
  var request=new AdmissionRequest(f.selected==null?new DecisionId(UUID.randomUUID()):new DecisionId(UUID.nameUUIDFromBytes(
   ("catalogue-step-admission-v1/"+f.selected.proposal().proposalId().value()+"/"+step.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8))),prepared.preconditions(),prepared.claims(),roles,plan);
  var validator=new AdmissionService.HardValidator(){
   public ProviderMode mode(){return providerMode();}
   public AdmissionService.Assessment assess(AdmissionRequest actual,Tick tick){
    boolean valid=actual.equals(request)&&loaded(f)&&selectedLive(f,prepared)&&binding.revalidate(prepared,captured.blobs())&&f.completed.containsAll(step.after());
    boolean budgetFits=f.selected==null||BudgetMath.fits(definition.budget(),SelectedEventAdmission.available(f.selected));
    valid=valid&&budgetFits;
    return new AdmissionService.Assessment(new ValidationResult(valid,
     valid?List.of():List.of(budgetFits?ReasonCode.STALE_CONTEXT:ReasonCode.BUDGET_EXHAUSTED),prepared.preconditions()),prepared.claims(),
     new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1,42),Map.of(scope,definition.budget()));
   }
  };
  e.admission=new AdmissionService(()->f.selected==null?catalog:f.selected.captured().catalog(),validator,e.arbiter,this::now,new DurationTicks(Math.max(1,Math.min(1200,deadline(f)-now().value()))),4,providerMode());
  e.decision=request.proposalId();
  if((content==null?e.admission.admit(request):content.admit(e.admission,request)).status()!=AdmissionStatus.DEFERRED)throw new IllegalStateException("perception J03 queue refused");
  var answer=e.admission.resolveWindow(prepared.preconditions().epochs(),Map.of(scope,f.selected==null?CAP:SelectedEventAdmission.available(f.selected))).get(e.decision);
  if(answer==null||answer.status()!=AdmissionStatus.ACCEPTED)throw new IllegalStateException("perception J03 admission refused "+answer);
  e.token=answer.token().orElseThrow();generationFloor=e.token.generation().value();
  var intent=prepared.intent();var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),intent.operationToken(),
   intent.expectedFootprint(),prepared.preconditions(),List.of(intent),prepared.budgetEstimate());
  e.live=new LiveValidationSnapshot(prepared.preconditions(),intent.expectedFootprint().loadedChunks(),HealthMode.NORMAL,now(),e.token.generation());
  var authority=new CommitPermit.Authority(store,4);
  var port=new ServerEffectPort(authority,i->{throw new IllegalStateException("captured");},s->e.live,
   Map.of(step.primitive(),selectedGuard(f,binding,prepared,e)),binding.capabilities(),4,
   s->new StabilizationLiveSnapshot(store.session(),e.live,new OwnershipView(Map.of())),m->{},(m,r)->{});
  port.hydrate(prepared,captured.blobs());
  var irreversible=step.args() instanceof TargetSound?Set.of(NoninvertibleEffect.SOUND_EXPOSURE):Set.<NoninvertibleEffect>of();
  var prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),intent.operationToken(),e.token.generation(),
   prepared.preconditions(),e.token.expiryTick(),slice,e.token,captured.validation(),
   f.selected==null?List.of():f.selected.reservation(e.token,now()),irreversible,List.of(),prepared.durableBlobs());
  e.prepare=prepare;
  e.tx=prepare.transactionId();e.runner=new EventRunner(authority,store,content==null?port:content.attach(prepared,captured.blobs(),port,f.selected!=null),e.arbiter,4);
  e.runner.submit(new DurableBatch(e.tx,store.session().storeGeneration(),prepare.readSet().revisions(),
    List.of(new DurablePrepare(prepare)),captured.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(prepare)),
   prepared.expectedPost(),e.live,actual->{
    var rows=new ArrayList<DurableRecord>();rows.add(new DurableResult(new ResultRecord(e.tx,intent.operationToken(),actual,Set.of(),List.of(),List.of())));
    var blobs=new ArrayList<BlobContent>();
    if(f.selected!=null){var ledger=f.selected.effectContribution(prepare,actual,now());rows.addAll(ledger.records());blobs.addAll(ledger.blobs());}
    return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,blobs);
   });
  f.bindings.add(binding);f.executions.add(e);
  System.out.println("DIRECTOR_LAB_PERCEPTION_J03_ACCEPTED instance="+f.id+" step="+step.id()+" calibration=DEFAULT_FOR_TESTING");
  return e;
 }
 private boolean selectedLive(Flight f,PreparedEffect prepared){
  if(f.selected==null)return true;
  try{return f.selected.refresh().context().scopeRevisions().equals(prepared.preconditions().revisions());}
  catch(RuntimeException changed){return false;}
 }
 private boolean selectedApplying(Flight f,Execution e,PreparedEffect prepared){
  if(f.selected==null)return true;
  // This frame exists only within the current actor call to runner.poll.
  return e.applyingInput!=null&&e.applyingSession!=null&&e.applyingSession.equals(store.session())
    &&e.applyingInput.context().scopeRevisions().equals(prepared.preconditions().revisions());
 }
 private ServerEffectPort.Binding selectedGuard(Flight f,NativePerceptionBinding binding,PreparedEffect prepared,Execution e){
  return new ServerEffectPort.Binding(){
   public ProviderMode mode(){return binding.mode();}
   public Set<PrimitiveId> supportedPrimitives(){return binding.supportedPrimitives();}
   public Set<CapabilityId> capabilities(){return binding.capabilities();}
   public EffectPreparation capture(EffectIntent intent,PlanningSnapshot context){return binding.capture(intent,context);}
   public boolean revalidate(PreparedEffect p,BlobBundle blobs){return selectedApplying(f,e,prepared)&&binding.revalidate(p,blobs);}
   public SliceResult apply(PreparedEffect p,BlobBundle blobs){if(!selectedApplying(f,e,prepared))throw new IllegalStateException("selected perception context revoked");return binding.apply(p,blobs);}
   public ReconcileResult reconcile(RecoveryProbe probe){return binding.reconcile(probe);}
  };
 }
 private boolean committed(Execution e){
  try{
   if(flight.selected!=null&&e.stage==EventRunner.Stage.PREPARING&&e.session.equals(store.session())&&now().compareTo(e.token.expiryTick())<0
      &&e.binding.available()&&fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,new PlayerId(flight.player),1)){
    var session=store.session();DecisionInput current;
    try{current=flight.selected.refresh();}
    catch(SelectedEventStart.ContextUnavailable unavailable){return false;} // No permit poll/consume or send.
    if(!session.equals(store.session()))return false;
    e.applyingInput=current;e.applyingSession=session;
   }
   // Expired/obsolete authority still reaches its ordinary rejection; RESULT ACKs drain without a new context.
   e.live=new LiveValidationSnapshot(e.live.readSet(),e.live.loadedChunks(),e.live.health(),now(),e.token.generation());
   var result=e.runner.poll(e.tx,e.live);e.stage=result.stage();
   if(result.stage()==EventRunner.Stage.RECOVERY_REQUIRED&&e.binding.definitivelyNeverSent()){
    // GRANTED durable PREPARE reached ServerEffectPort, but validation rejected BEFORE
    // the network boundary. Drain that exact operation; never replay an attempted send.
    var safe=new SliceResult(Outcome.ABORTED_SAFE,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
    flight.ack=append(List.of(new DurableResult(new ResultRecord(e.tx,e.prepare.operationToken(),safe,Set.of(),List.of(),List.of()))));
    flight.phase=Phase.ABORTING;return false;
   }
   if(result.stage()==EventRunner.Stage.REJECTED||result.stage()==EventRunner.Stage.RECOVERY_REQUIRED)
    throw new IllegalStateException("perception "+result.stage()+" "+result.failure());
   e.committed=result.stage()==EventRunner.Stage.COMMITTED;return e.committed;
  }finally{e.applyingInput=null;e.applyingSession=null;}
 }
 private void finish(Flight f,String reason){
  System.out.println("DIRECTOR_PERCEPTION_TERMINAL event="+f.id+" reason="+reason+" steps="+f.completed.size()+"/"+f.definition.steps().size()+" started="+f.started+" tick="+now().value());
  for(var binding:f.bindings)binding.stop();
  var rows=new ArrayList<DurableRecord>();rows.add(new DurableFact(fact(END,f.id,Map.of("reason",new JsonValue.Text(reason)))));
  var terminalBlobs=new ArrayList<BlobContent>();
  if(f.selected!=null){
   var evidence=Map.<String,JsonValue>of("renderer_receipts",new JsonValue.Array(f.receipts.values().stream().map(h->(JsonValue)new JsonValue.Text(
    Base64.getEncoder().encodeToString(new fr.proutlost.director.api.codec.ModelCodec<>(PerceptionHeader.class).encode(h)))).toList()),"cleanup",new JsonValue.Text(reason),
    "outcome_observed_tick",new JsonValue.Text(Long.toString(f.qualifiedAt)),
    "cleanup_tick",new JsonValue.Text(Long.toString(now().value())),"max_duration_deadline",new JsonValue.Text(Long.toString(deadline(f))));
   if(f.qualifiedAt>=0&&f.qualifiedAt<deadline(f)&&f.completed.containsAll(f.definition.steps().stream().map(EventStep::id).toList())&&f.receipts.size()==f.definition.steps().size()&&f.executions.stream().allMatch(e->e.committed)){
    var contribution=f.selected.completionContribution(f.executions.getLast().prepare,evidence,now());
    rows.addAll(contribution.records());terminalBlobs.addAll(contribution.blobs());
   }else{
    var aborted=new HashMap<String,JsonValue>(evidence);aborted.put("no_physical_effect",new JsonValue.Bool(true));
    aborted.put("perception_exposure",new JsonValue.Bool(!f.executions.isEmpty()));
    rows.addAll(f.selected.abortRecords(reason,aborted));
   }
  }
  if(f.qualifiedAt>=0&&f.qualifiedAt<deadline(f)&&f.selected==null&&chainCompletion!=null&&f.completed.containsAll(f.definition.steps().stream().map(EventStep::id).toList())
    &&f.receipts.size()==f.definition.steps().size()&&f.executions.size()==f.definition.steps().size()&&f.executions.stream().allMatch(e->e.committed)){
   var receiptRows=new TreeMap<String,JsonValue>();f.receipts.forEach((step,header)->receiptRows.put(step,new JsonValue.Text(
    Base64.getEncoder().encodeToString(new fr.proutlost.director.api.codec.ModelCodec<>(PerceptionHeader.class).encode(header)))));
   rows.addAll(chainCompletion.completionRecords(f.id,f.definition.id(),Map.of(
    "renderer_receipts",new JsonValue.ObjectValue(receiptRows),"completion_reason",new JsonValue.Text(reason),
    "prepared_transactions",new JsonValue.Array(f.executions.stream().map(e->(JsonValue)new JsonValue.Text(e.tx.value().toString())).toList()))));
  }
  f.ack=append(rows,terminalBlobs);
  f.phase=Phase.END;
 }
 public boolean controlRecoveryReady(){actor();return !failed&&scanned&&!store.readOnlySafe();}
 /** Canonical admin ingress after its exact durable intent; completion observes this same physical flight. */
 public Optional<java.util.function.Supplier<AdminStatus>> requestStop(Optional<EventInstanceId> target){
  actor();var f=flight;if(f==null||target.filter(id->!id.value().equals(f.id)).isPresent())return Optional.empty();
  var event=f.id;f.stopRequested=true;
  return Optional.of(()->{actor();return ends.contains(event)?AdminStatus.SUCCEEDED:failed?AdminStatus.FAILED_SAFE:AdminStatus.PENDING;});
 }
 public void poll(){
  actor();if(failed)return;
  try{
   if(store.readOnlySafe())throw new IllegalStateException("perception WAL unavailable");
   if(!scanned){if(!sharedRecovery)scan();return;}
   if(recoveryAck!=null){
    if(durable(recoveryAck)){unmatched.values().removeIf(p->p.reservation().instanceId().value().equals(recoveringEvent));starts.remove(recoveringEvent);ends.add(recoveringEvent);recoveryAck=null;}
    return;
   }
   if(flight==null){
    if(!starts.isEmpty()){
     recoveringEvent=starts.keySet().iterator().next();
     var records=new ArrayList<DurableRecord>();
     for(var prepare:unmatched.values()){
      if(!prepare.reservation().instanceId().value().equals(recoveringEvent))continue;
      var uncertain=new SliceResult(Outcome.DELIVERY_UNCERTAIN,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
      records.add(new DurableResult(new ResultRecord(prepare.transactionId(),prepare.operationToken(),
       uncertain,Set.of(),List.of(),List.of())));
     }
     records.add(new DurableFact(fact(END,recoveringEvent,
      Map.of("reason",new JsonValue.Text("RESTART_CLEANUP_NO_REPLAY")))));
     recoveryAck=append(records);
     System.out.println("DIRECTOR_LAB_PERCEPTION_RECOVERED_NO_REPLAY instance="+recoveringEvent);
    }
    return;
   }
   var f=flight;
   switch(f.phase){
    case DOSSIER->{if(durable(f.ack)){if(f.stopRequested){finish(f,"ADMIN_STOP");return;}if(expired(f)){finish(f,"MAX_DURATION_BEFORE_FIRST_STEP");return;}f.delayUntil=now().value()+f.definition.steps().getFirst().delayTicks().value();f.phase=Phase.DELAY;}}
    case DELAY->{
     if(f.stopRequested){finish(f,"ADMIN_STOP");return;}
     if(expired(f)){finish(f,"MAX_DURATION_BEFORE_NEXT_STEP");return;}
     var pendingStep=f.definition.steps().get(f.index);
     if(pendingStep.args() instanceof Apparition apparition&&deadline(f)-now().value()<apparition.followTicks().value()){
      finish(f,"MAX_DURATION_APPARITION_WINDOW_EXHAUSTED");return;
     }
     if(!loaded(f)){finish(f,"PARTICIPANT_LOST");return;}
     if(now().value()>=f.delayUntil){
      if(!fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,new PlayerId(f.player),1))return;
      if(f.selected!=null&&!f.selected.forwardContextAvailable())return;
      f.work=execute(f,f.definition.steps().get(f.index));f.phase=Phase.APPLYING;
     }
    }
    case APPLYING->{
     if(committed(f.work)){if(f.stopRequested){finish(f,"ADMIN_STOP");return;}f.receiptDeadline=now().value()+100;f.phase=Phase.RECEIPT;}
    }
    case ABORTING->{if(durable(f.ack))finish(f,"SESSION_RENEWAL_BEFORE_SEND_NO_REPLAY");}
    case RECEIPT->{
     if(f.stopRequested){finish(f,"ADMIN_STOP");return;}
     if(expired(f)){finish(f,"MAX_DURATION_BEFORE_RENDERER_RECEIPT");return;}
     if(f.work.binding.received()){
      var step=f.definition.steps().get(f.index);f.completed.add(step.id());
      f.receipts.put(step.id(),f.work.binding.emittedHeader().orElseThrow());
      if(f.index==f.definition.steps().size()-1&&!expired(f))f.qualifiedAt=now().value();
      System.out.println("DIRECTOR_LAB_PERCEPTION_RENDERER_RECEIVED instance="+f.id+" step="+step.id()+" primitive="+step.primitive().value());
      if(now().value()-f.started>=f.definition.timing().maxDurationTicks().value()){
       finish(f,"DELIVERY_EXPIRED");return;
      }
      if(++f.index<f.definition.steps().size()){
       f.delayUntil=now().value()+f.definition.steps().get(f.index).delayTicks().value();f.phase=Phase.DELAY;
      }else{f.expires=Math.min(deadline(f),now().value()+ttl(f,step));f.phase=Phase.LIVE;}
     }else if(!f.work.binding.available()||now().value()>=f.receiptDeadline)finish(f,"RENDERER_RECEIPT_UNAVAILABLE");
    }
    case LIVE->{if(f.stopRequested||!loaded(f)||now().value()>=f.expires)finish(f,f.stopRequested?"ADMIN_STOP":"DELIVERY_EXPIRED");}
    case END->{if(durable(f.ack)){
     for(var e:f.executions){e.arbiter.release(e.token);e.admission.released(e.decision,e.token);}
     System.out.println("DIRECTOR_LAB_PERCEPTION_CLEANUP_COMPLETE instance="+f.id+" definition="+f.definition.id().value());
     ends.add(f.id);flight=null;
    }}
   }
  }catch(SelectedEventStart.ContextUnavailable unavailable){
   if(flight==null||flight.phase!=Phase.DELAY)throw unavailable;
   // No preparation/apply occurred; await a real fresh J01/J04 cut.
  }catch(RuntimeException error){failed=true;System.out.println("DIRECTOR_LAB_PERCEPTION_FAILED_SAFE "+error);error.printStackTrace();}
 }
 private void scan(){
  if(pendingRead==null){pendingRead=store.read(new StoreQuery(world,Set.of(),new Sequence(0),cursor,128)).toCompletableFuture();return;}
  if(!pendingRead.isDone())return;
  var read=pendingRead.getNow(null);pendingRead=null;
  if(read.status()==ReadStatus.STALE){cursor=Optional.empty();starts.clear();ends.clear();unmatched.clear();resolved.clear();return;}
  if(read.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("perception recovery read "+read.status());
  var page=read.page().orElseThrow();
  acceptRecoveryRows(page.records(),!page.hasMore());
  cursor=page.nextCursor();
 }
 private boolean sharedRecovery;
 public void useSharedRecoveryScan(){actor();sharedRecovery=true;resetRecoveryScan();}
 public void resetRecoveryScan(){actor();scanned=false;pendingRead=null;cursor=Optional.empty();starts.clear();ends.clear();unmatched.clear();resolved.clear();generationFloor=0;}
 public void acceptRecoveryRows(List<DurableRecord> rows,boolean last){actor();
  for(var record:rows){
   if(record instanceof DurablePrepare row){
    var prepare=row.value();generationFloor=Math.max(generationFloor,prepare.reservationGeneration().value());
    var operations=prepare.slice().operations();
    if(operations.size()==1&&Set.of("proutlost:target_sound","proutlost:local_weather","proutlost:apparition")
        .contains(operations.getFirst().primitive().value()))unmatched.put(prepare.operationToken(),prepare);
   }
   if(record instanceof DurableResult row)resolved.add(row.value().operationToken());
   if(record instanceof DurableFact row&&row.value().eventId().isPresent()){
    var fact=row.value();var id=fact.eventId().orElseThrow().value();
    if(fact.factType().equals(START))starts.put(id,fact);else if(fact.factType().equals(END))ends.add(id);
   }
  }

  if(last){
   unmatched.entrySet().removeIf(entry->resolved.contains(entry.getKey())||
    !starts.containsKey(entry.getValue().reservation().instanceId().value()));
   for(var id:ends)
    if(unmatched.values().stream().noneMatch(p->p.reservation().instanceId().value().equals(id)))starts.remove(id);
   scanned=true;
  }

 }

 public boolean recovering(){actor();return !scanned&&!failed;}
 public String status(){actor();return failed?"PERCEPTION_FAILED_SAFE":!scanned?"PERCEPTION_RECOVERING":
  flight==null?"PERCEPTION_IDLE pending="+starts.size():"PERCEPTION_"+flight.phase+" "+flight.definition.id().value();}
 @Override public void close(){actor();if(flight!=null)for(var binding:flight.bindings)binding.stop();closed=true;}
}
