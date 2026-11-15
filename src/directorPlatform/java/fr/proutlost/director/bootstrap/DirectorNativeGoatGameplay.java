package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.network.*;
import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.ownership.EntityDirectorAttachment;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.animal.goat.Goat;

/** Real manual OP4 and exact selected catalogue goat visits, warning, native entity, exact-owner inverse.
 * The existing WAL writer is shared; this lane never installs a production provider or autoplay.
 * Reboot only resumes cleanup of a proven entity. It never replays a spawn.
 */
public final class DirectorNativeGoatGameplay implements AutoCloseable {
 private final DirectorRuntimeContent content;
 private static final FactType START=new FactType("proutlost_director:lab_goat_start");
 private static final FactType END=new FactType("proutlost_director:lab_goat_end");
 private static final BudgetVector CAP=new BudgetVector(100,100,100,100,100,100,100,100,100,100);
 private static final ModelCodec<EntityOwnershipRecord> SLOT=new ModelCodec<>(EntityOwnershipRecord.class);
 private final MinecraftServer server;
 private final WorldId world;
 private final JournalStore store;
 private final CatalogSnapshot catalog;
 private final GoatVisit args;
 private final Map<UUID,DirectorFact> active=new LinkedHashMap<>();
 private final Map<UUID,EntityOwnershipRecord> slots=new HashMap<>();
 private final Set<UUID> ended=new HashSet<>();
 private final Set<UUID> spawnPrepared=new HashSet<>(),spawnedKnown=new HashSet<>();
 private CompletableFuture<StoreReadResult> pending;
 private Optional<String> cursor=Optional.empty();
 private boolean scanned,closed,failed;
 private long reservationFloor;
 private Flight flight;
 private enum Phase { INITIAL, WARNING, LEAD, SPAWNING, LIVE, DEPARTING, RECOVER, DONE }
 private static final class Flight {
  UUID id,player,goat;BlockPosition position;EntityOwnershipRecord slot;
  SelectedEventStart selection;ExecutionPlan selectedPlan;DecisionId selectedDecision;
  NativeCatalogueColdCompletion.Identity coldIdentity;NativeCatalogueColdCompletion.Read coldRead;
  Optional<ChainId> parentChain=Optional.empty();AnchorRecord selectedAnchor;boolean spawned;
  NativeWarningBinding warning;CompletableFuture<CommitAck> ack; Execution work,impact;
  long warnAt=-1,warnSent,expires;long selectedDeadline=Long.MAX_VALUE;boolean stopRequested;Phase phase;
 }
 private static final class Execution {
  NativeWarningBinding perception;PrepareRecord perceptionPrepare;CompletableFuture<CommitAck> noSendAck;boolean noSend;
  ResourceArbiter arbiter;ReservationToken token;AdmissionService admission;
  DecisionId decision;EventRunner runner;TransactionId tx;
  LiveValidationSnapshot live;boolean completed,retainClaim,released;DecisionInput selectedInput;EventRunner.Stage lastStage=EventRunner.Stage.PREPARING;
 }
 public DirectorNativeGoatGameplay(MinecraftServer server,WorldId world,JournalStore store) {
  this.content=null;
  this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);
  this.store=Objects.requireNonNull(store);
  catalog=new DirectorLabGoatCatalog(DirectorLabObservationProfile.load()).snapshot();
  args=(GoatVisit)catalog.events().get(new DefinitionId("proutlost_director:candidate/goat_guest"))
      .steps().getFirst().args();
  actor();
 }
 public DirectorNativeGoatGameplay(MinecraftServer server,WorldId world,JournalStore store,DirectorRuntimeContent content) {
  this.content=Objects.requireNonNull(content);
  this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);
  this.store=Objects.requireNonNull(store);
  catalog=content.catalog();
  args=(GoatVisit)catalog.events().get(new DefinitionId("proutlost_director:candidate/goat_guest"))
      .steps().getFirst().args();
  actor();
 }
 private ProviderMode providerMode(){return content==null?ProviderMode.TEST_ONLY:content.mode();}
 private void requireLaboratory(){if(content!=null)content.requireLaboratory();}
 private void requireSelected(SelectedEventStart selected){
  Objects.requireNonNull(selected);
  if(selected.mode()!=providerMode()||(content!=null&&!selected.definition().equals(content.definition(selected.definition().id()))))
   throw new IllegalArgumentException("selected runtime content/mode mismatch");
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("goat LAB actor");}
 private Tick now(){return new Tick(content!=null&&content.production()?directorNow():server.overworld().getGameTime());}
 private long directorNow(){return fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server);}
 private boolean selectedExpired(Flight f){return f.selectedPlan!=null&&directorNow()>=f.selectedDeadline;}
 private long selectedRemaining(Flight f){return f.selectedPlan==null?Long.MAX_VALUE:directorNow()>=f.selectedDeadline?0:f.selectedDeadline-directorNow();}
 private static BlockPos pos(BlockPosition p){return new BlockPos(p.x(),p.y(),p.z());}
 private ServerLevel level(BlockPosition p){
  for(var l:server.getAllLevels())
   if(l.dimension().location().toString().equals(p.dimension().value()))return l;
  throw new IllegalStateException("goat dimension unavailable");
 }
 private static ResourceKey key(Flight f){
  return new ResourceKey(OwnershipDomain.ENTITY,f.position.dimension(),f.goat.toString());
 }
 private boolean loaded(BlockPosition p){return level(p).getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)!=null;}
 private CompletableFuture<CommitAck> append(List<DurableRecord> records){
  return store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),
   store.session().storeGeneration(),Map.of(),records,List.of())).toCompletableFuture();
 }
 private boolean durable(CompletableFuture<CommitAck> ack){
  if(ack==null||!ack.isDone())return false;
  if(ack.isCompletedExceptionally()||ack.getNow(null)==null||
      ack.getNow(null).ackStatus()!=AckStatus.DURABLE)
   throw new IllegalStateException("goat WAL durable ack absent");
  return true;
 }
 private DirectorFact fact(FactType type,Flight f,Map<String,JsonValue> payload){
  return new DirectorFact(new FactId(UUID.randomUUID()),type,
   List.of(new ScopeRef(ScopeKind.WORLD,world.value())),
   Optional.of(new EventInstanceId(f.id)),EvidenceStatus.CONFIRMED,
   payload,true,Optional.empty());
 }
 public String begin(ServerPlayer player){requireLaboratory();
  actor();
  if(!scanned||failed||store.readOnlySafe())return "Goat LAB : WAL non disponible.";
  if(flight!=null||!active.isEmpty())return "Goat LAB : visite ou récupération en cours.";
  if(!player.hasPermissions(4)||!player.isAlive())return "Goat LAB : OP4 requis.";
  var l=player.serverLevel();
  var target=player.blockPosition().relative(player.getDirection(),3);
  var p=new BlockPosition(new DimensionId(l.dimension().location().toString()),
      target.getX(),target.getY(),target.getZ());
  if(!loaded(p)||!l.getWorldBorder().isWithinBounds(target)||
      target.getY()<=l.getMinBuildHeight()||target.getY()>=l.getMaxBuildHeight()-2||
      !l.getBlockState(target.below()).isSolidRender(l,target.below())||
      !l.getBlockState(target).getCollisionShape(l,target).isEmpty()||
      !l.getBlockState(target.above()).getCollisionShape(l,target.above()).isEmpty())
   return "Goat LAB : vise une zone dégagée à trois blocs devant toi.";
  var f=new Flight();f.id=UUID.randomUUID();f.player=player.getUUID();
  f.goat=UUID.randomUUID();f.position=p;
  initialize(f,player);
  return "Goat LAB : avertissement visible, arrivee de chevre, depart apres20secondes.";
 }
 public NativeGoatSelectedHabitat.Survey preview(Candidate candidate,DecisionInput input){
  actor();return NativeGoatSelectedHabitat.preview(server,candidate,input);
 }
 public boolean selectedAvailable(){actor();return scanned&&!failed&&!store.readOnlySafe()&&flight==null&&active.isEmpty();}
 public Optional<UUID> activeEvent(){actor();return flight==null?Optional.empty():Optional.of(flight.id);}
 public boolean selectedEntityAvailable(UUID id){actor();return scanned&&!failed&&!store.readOnlySafe()&&!slots.containsKey(id)
   &&java.util.stream.StreamSupport.stream(server.getAllLevels().spliterator(),false).noneMatch(level->level.getEntity(id)!=null);}
 public String beginSelected(SelectedEventStart selected){requireSelected(selected);
  actor();Objects.requireNonNull(selected);
  if(!scanned||failed||store.readOnlySafe()||flight!=null||!active.isEmpty())
   throw new IllegalStateException("goat selected lane unavailable");
  var current=selected.refresh();var plan=selected.plan();var def=catalog.events().get(new DefinitionId("proutlost_director:candidate/goat_guest"));
  var reference=new DefinitionRef(def.id(),def.contentRevision(),BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(def)));
  if(!plan.definition().equals(reference)||!plan.scopes().worldId().equals(world)
      ||selected.proposal().selectedPlan().filter(plan::equals).isEmpty()||plan.effects().size()!=1
      ||!plan.effects().getFirst().typedArgs().equals(args))
   throw new IllegalArgumentException("exact selected goat proposal required");
  var ids=plan.roles().roles().getOrDefault(args.role(),List.of());
  if(ids.size()!=1)throw new IllegalArgumentException("one selected witness required");
  var player=server.getPlayerList().getPlayer(ids.getFirst().value());
  var observed=current.context().context().players().get(ids.getFirst());
  if(player==null||!player.isAlive()||observed==null||observed.eligibility()!=PlayerEligibility.ELIGIBLE)
   throw new IllegalArgumentException("actual selected witness unavailable");
  NativeGoatSelectedHabitat.validate(server,plan,current.context());
  var id=selected.eventId().value();if(active.containsKey(id)||ended.contains(id))
   throw new IllegalArgumentException("selected goat already consumed");
  var f=new Flight();f.id=id;f.player=player.getUUID();f.goat=UUID.nameUUIDFromBytes(("catalogue-goat-v1\0"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  f.position=plan.effects().getFirst().expectedFootprint().cells().iterator().next();
  f.selection=selected;f.selectedPlan=plan;f.selectedDecision=selected.proposal().proposalId();
  f.selectedDeadline=Math.addExact(directorNow(),selected.definition().timing().maxDurationTicks().value());
  f.parentChain=selected.parentChain();f.selectedAnchor=NativeGoatSelectedHabitat.anchor(plan,current.context());
  f.coldIdentity=NativeCatalogueColdCompletion.beforeApply(selected,current);
  if(!witness(f)||!campLive(f))throw new IllegalArgumentException("selected camp or witness changed");
  initialize(f,player);return "Visite catalogue selectionnee "+id+"; chevre temporaire et depart durable.";
 }
 private DirectorFact dossier(Flight f){
  var p=f.position;var values=new TreeMap<String,JsonValue>();
  values.put("slot",new JsonValue.Text(Base64.getEncoder().encodeToString(SLOT.encode(f.slot))));
  values.put("dimension",new JsonValue.Text(p.dimension().value()));
  values.put("x",new JsonValue.Text(Integer.toString(p.x())));values.put("y",new JsonValue.Text(Integer.toString(p.y())));
  values.put("z",new JsonValue.Text(Integer.toString(p.z())));values.put("player",new JsonValue.Text(f.player.toString()));
  if(f.selectedPlan!=null){
   values.put("selected_plan",new JsonValue.Text(Base64.getEncoder().encodeToString(new ModelCodec<>(ExecutionPlan.class).encode(f.selectedPlan))));
   values.put("selected_decision",new JsonValue.Text(f.selectedDecision.value().toString()));
   values.put("selected_deadline",new JsonValue.Text(Long.toString(f.selectedDeadline)));
   NativeCatalogueColdCompletion.pin(Objects.requireNonNull(f.coldIdentity),values);
   values.put("selected_anchor",new JsonValue.Text(Base64.getEncoder().encodeToString(new ModelCodec<>(AnchorRecord.class).encode(f.selectedAnchor))));
   f.parentChain.ifPresent(id->values.put("parent_chain",new JsonValue.Text(id.value().toString())));
  }
  return fact(START,f,values);
 }
 private boolean witness(Flight f){
  var player=server.getPlayerList().getPlayer(f.player);
  return player!=null&&player.isAlive()&&(f.selectedPlan!=null||player.hasPermissions(4))
    &&player.serverLevel()==level(f.position)
    &&player.distanceToSqr(f.position.x()+.5,f.position.y()+.5,f.position.z()+.5)<=256;
 }
 private boolean campLive(Flight f){
  if(f.selectedPlan==null)return true;
  var session=store.session();var query=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.ANCHOR,
      f.selectedAnchor.anchorId().value())),new Sequence(0),Optional.empty(),1);
  var read=store.view(query);ReadContracts.validate(read,query,session);
  if(read.status()!=ReadStatus.AVAILABLE||!session.equals(store.session()))return false;
  return read.page().orElseThrow().aggregates().stream().anyMatch(row->
      row.state() instanceof AnchorAggregate anchor&&anchor.value().equals(f.selectedAnchor));
 }
 private void initialize(Flight f,ServerPlayer player){
  var p=f.position;var chunk=new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4);
  f.slot=new EntityOwnershipRecord(new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(0),now()),
   new OwnerToken(UUID.randomUUID()),f.goat,new EventInstanceId(f.id),new CohortId(UUID.randomUUID()),
   "goat-client-lab",EntityPhase.RESERVED,OwnershipDisposition.DIRECTOR_ACTIVE,
   chunk,now(),ArrivalPolicy.SETTLE_WORLD,CleanupReason.COMPLETED,Optional.empty(),new Epoch(1));
  var transform=new PerceptionTransform(p.x()+.5,p.y()+.8,p.z()+.5,
      net.minecraft.util.Mth.wrapDegrees(180-player.getYRot()),0,2);
  f.warning=new NativeWarningBinding(server,new PlayerId(f.player),transform,1200);
  var dossier=dossier(f);
  f.ack=append(List.of(new DurableAggregate(new EntityOwnershipAggregate(f.slot)),new DurableFact(dossier)));
  f.phase=Phase.INITIAL;flight=f;active.put(f.id,dossier);slots.put(f.goat,f.slot);
  System.out.println("DIRECTOR_LAB_GOAT_DOSSIER_DURABLE_REQUESTED id="+f.id+" uuid="+f.goat
      +" admission_mode="+(f.selectedPlan==null?"MANUAL_LAB":"CATALOGUE_SELECTED"));
 }
 private PlanningSnapshot snapshot(Flight f){
  var p=f.position;var chunk=new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4);
  var footprint=new Footprint(p.dimension(),Set.of(chunk),Set.of(p),List.of());
  var state=level(p).getBlockState(pos(p));
  var stateHash=BlockTransformBinding.stateBlob(state).reference().hash();
  var cell=new TerrainCellSnapshot(p,stateHash,BlockTransformBinding.STATE_CODEC,
      Set.of(),Set.of(),false,true,new Epoch(1),new Epoch(0));
  var terrain=new TerrainSnapshot(footprint,Map.of(p,cell),List.of(),Map.of(),new Revision(1));
  if(f.selection!=null&&(f.phase==Phase.INITIAL||f.phase==Phase.LEAD))return SelectedNativeSnapshot.terrain(f.selection.refresh(),terrain);
  var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),
      Map.of(),List.of(terrain),Map.of());
  var observed=new SnapshotBatch(new RequestId(UUID.randomUUID()),
      new ScopeSet(world,Set.of()),now(),Map.of(),context,Set.of(),Set.of(chunk),HealthMode.NORMAL);
  return new PlanningSnapshot(observed,new OwnershipView(Map.of()),
      new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));
 }
 private ServerEffectPort.Binding binding(Flight f){
  var nativeBinding=new GoatVisitBinding(d->level(f.position),i->f.slot,next->{f.slot=next;slots.put(f.goat,next);});
  if(f.selectedPlan==null)return nativeBinding;
  return new ServerEffectPort.Binding(){
   public ProviderMode mode(){return nativeBinding.mode();}
   public Set<PrimitiveId> supportedPrimitives(){return nativeBinding.supportedPrimitives();}
   public Set<CapabilityId> capabilities(){return nativeBinding.capabilities();}
   public EffectPreparation capture(EffectIntent intent,PlanningSnapshot context){return nativeBinding.capture(intent,context);}
   public boolean revalidate(PreparedEffect plan,BlobBundle blobs){
    return (plan.intent().primitive().equals(GoatVisitBinding.DEPART)||(witness(f)&&campLive(f)))
      &&nativeBinding.revalidate(plan,blobs);
   }
   public SliceResult apply(PreparedEffect plan,BlobBundle blobs){
    if(!revalidate(plan,blobs))throw new IllegalStateException("selected goat context changed");
    return nativeBinding.apply(plan,blobs);
   }
   public ReconcileResult reconcile(RecoveryProbe probe){return nativeBinding.reconcile(probe);}
  };
 }
 private EffectPreparation capture(Flight f,PrimitiveId primitive,ServerEffectPort.Binding provider){
  if(f.selection!=null&&primitive.equals(GoatVisitBinding.VISIT))f.selection.refresh();
  var context=snapshot(f);
  var intent=f.selectedPlan!=null&&primitive.equals(GoatVisitBinding.VISIT)?f.selectedPlan.effects().getFirst():
   new EffectIntent(new OperationToken(UUID.randomUUID()),primitive,1,
      f.selectedPlan==null?args:(GoatVisit)f.selectedPlan.effects().getFirst().typedArgs(),
      context.snapshot().context().terrain().getFirst().loadedFootprint(),new EventInstanceId(f.id));
  return provider.capture(intent,context);
 }
 private Execution run(Flight f,EffectPreparation captured,ServerEffectPort.Binding provider,boolean admit){
  return run(f,captured,provider,admit,admit&&f.selection!=null?f.selection.refresh():null);
 }
 private Execution run(Flight f,EffectPreparation captured,ServerEffectPort.Binding provider,boolean admit,DecisionInput selectedInput){
  var plan=captured.prepared().orElseThrow();
  var intent=plan.intent();
  long leaseTicks=f.selectedPlan!=null&&(admit||intent.primitive().equals(new PrimitiveId("proutlost:warning")))?Math.min(1200,selectedRemaining(f)):1200;
  if(leaseTicks<1)throw new IllegalStateException("selected goat deadline reached before PREPARE");
  var e=new Execution();if(provider instanceof NativeWarningBinding warning)e.perception=warning;e.retainClaim=admit;
  if(admit&&f.selection!=null)e.selectedInput=Objects.requireNonNull(selectedInput);
  e.arbiter=new ResourceArbiter(world,
      new Epoch(Math.max(1,Math.max(reservationFloor,store.session().bootEpoch().value()))),1000,4,
      new ResourceArbiter.Compatibility(){
       public boolean share(ResourceClaim a,ResourceClaim b){return false;}
       public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}
      });
  var scope=new ScopeRef(ScopeKind.WORLD,world.value());
  var epochs=new HashMap<ResourceKey,Epoch>();
  plan.claims().forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
  if(admit){
   var def=catalog.events().get(new DefinitionId("proutlost_director:candidate/goat_guest"));
   var player=new PlayerId(f.player);var roles=f.selectedPlan==null?new RoleAssignments(Map.of("witness",List.of(player))):f.selectedPlan.roles();
   var scopes=f.selectedPlan==null?new ScopeSet(world,Set.of(scope,new ScopeRef(ScopeKind.PLAYER,player.value()))):f.selectedPlan.scopes();
   var reference=new DefinitionRef(def.id(),def.contentRevision(),
       BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(def)));
   var execution=new ExecutionPlan(f.selectedPlan==null?new CandidateId(UUID.randomUUID()):f.selectedPlan.candidateId(),reference,
       List.of(intent),roles,scopes,plan.preconditions());
   var request=new AdmissionRequest(f.selectedDecision==null?new DecisionId(UUID.randomUUID()):f.selectedDecision,
       plan.preconditions(),plan.claims(),roles,execution);
   var validator=new AdmissionService.HardValidator(){
    public ProviderMode mode(){return providerMode();}
    public AdmissionService.Assessment assess(AdmissionRequest proposed,Tick tick){
     var p=server.getPlayerList().getPlayer(f.player);
     boolean valid=request.equals(proposed)&&witness(f)&&campLive(f)&&
       !store.readOnlySafe()&&f.warning!=null&&f.warning.received()&&
       intent.typedArgs().equals(def.steps().getFirst().args())&&
       provider.revalidate(plan,captured.blobs());
     boolean budgetFits=f.selection==null||BudgetMath.fits(plan.budgetEstimate(),SelectedEventAdmission.available(f.selection,store,new EventInstanceId(f.id)));
     valid=valid&&budgetFits;
     return new AdmissionService.Assessment(new ValidationResult(valid,
        valid?List.of():List.of(budgetFits?ReasonCode.STALE_CONTEXT:ReasonCode.BUDGET_EXHAUSTED),plan.preconditions()),
        plan.claims(),new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1.0,42),
        Map.of(scope,plan.budgetEstimate()));
    }
   };
   e.admission=new AdmissionService(()->catalog,validator,e.arbiter,this::now,
       new DurationTicks(leaseTicks),4,providerMode());
   e.decision=request.proposalId();
   if((content==null?e.admission.admit(request):content.admit(e.admission,request)).status()!=AdmissionStatus.DEFERRED)
    throw new IllegalStateException("goat J03 request not queued");
   var answer=e.admission.resolveWindow(epochs,Map.of(scope,f.selection==null?CAP:SelectedEventAdmission.available(f.selection,store,new EventInstanceId(f.id)))).get(request.proposalId());
   if(answer==null||answer.status()!=AdmissionStatus.ACCEPTED)
    throw new IllegalStateException("goat J03 denied "+answer);
   e.token=answer.token().orElseThrow();
   System.out.println("DIRECTOR_LAB_GOAT_J02_J03_ACCEPTED id="+f.id+" definition="+def.id().value());
  }else{
   var candidate=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),
       intent.instanceId(),new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1.0,42),
       now().plus(leaseTicks),plan.claims(),Map.of(scope,plan.budgetEstimate()));
   e.token=e.arbiter.arbitrate(List.of(candidate),now(),epochs,Map.of(scope,CAP))
       .getFirst().accepted().orElseThrow(()->new IllegalStateException("goat claim unavailable"));
  }
  reservationFloor=Math.max(reservationFloor,e.token.generation().value());
  var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),intent.operationToken(),
      intent.expectedFootprint(),plan.preconditions(),List.of(intent),plan.budgetEstimate());
  e.live=new LiveValidationSnapshot(plan.preconditions(),intent.expectedFootprint().loadedChunks(),
      HealthMode.NORMAL,now(),e.token.generation());
  var authority=new CommitPermit.Authority(store,4);
  var port=new ServerEffectPort(authority,i->{throw new IllegalStateException("already captured");},
      s->e.live,Map.of(intent.primitive(),provider),provider.capabilities(),4,
      s->new StabilizationLiveSnapshot(store.session(),e.live,new OwnershipView(Map.of())),
      m->{},(m,r)->{});
  port.hydrate(plan,captured.blobs());
  var prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),intent.operationToken(),
      e.token.generation(),plan.preconditions(),e.token.expiryTick(),slice,e.token,
      captured.validation(),e.selectedInput==null?List.of():CatalogueEventLedger.reserve(new CatalogueEventLedger.Selection(f.selection.definition(),f.selectedPlan),e.selectedInput,e.token,
       new Tick(fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server))),Set.of(),List.of(),plan.durableBlobs());
  e.perceptionPrepare=prepare;e.tx=prepare.transactionId();e.runner=new EventRunner(authority,store,content==null?port:content.attach(plan,captured.blobs(),port,f.selection!=null&&(admit||intent.primitive().equals(new PrimitiveId("proutlost:warning")))),e.arbiter,4);
  var batch=new DurableBatch(e.tx,store.session().storeGeneration(),
      prepare.readSet().revisions(),List.of(new DurablePrepare(prepare)),
      captured.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
  e.runner.submit(batch,plan.expectedPost(),e.live,outcome->{
   var rows=new ArrayList<DurableRecord>();var ledgerBlobs=new ArrayList<BlobContent>();
   rows.add(new DurableResult(new ResultRecord(e.tx,intent.operationToken(),outcome,
       Set.of(),List.of(),List.of())));
   if(!intent.primitive().equals(new PrimitiveId("proutlost:warning")))
    rows.add(new DurableAggregate(new EntityOwnershipAggregate(f.slot)));
   if(e.selectedInput!=null){var ledger=CatalogueEventLedger.effect(new CatalogueEventLedger.Selection(f.selection.definition(),f.selectedPlan),e.selectedInput,prepare,outcome,
       new Tick(fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server)));
    rows.addAll(ledger.records());ledgerBlobs.addAll(ledger.blobs());}
   return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),
       store.session().storeGeneration(),Map.of(),rows,ledgerBlobs);
  });
  return e;
 }
 private boolean committed(Execution e){
  if(e.noSend){if(!durable(e.noSendAck))return false;release(e);return true;}
  if(e.selectedInput!=null&&e.lastStage==EventRunner.Stage.PREPARING&&!flight.selection.forwardContextAvailable()&&now().compareTo(e.token.expiryTick())<0)return false;
  e.live=new LiveValidationSnapshot(e.live.readSet(),e.live.loadedChunks(),
      e.live.health(),now(),e.token.generation());
  var outcome=e.runner.poll(e.tx,e.live);e.lastStage=outcome.stage();
  if(outcome.stage()==EventRunner.Stage.RECOVERY_REQUIRED&&e.perception!=null&&e.perception.definitivelyNeverSent()){
   var safe=new SliceResult(Outcome.ABORTED_SAFE,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
   e.noSendAck=append(List.of(new DurableResult(new ResultRecord(e.tx,e.perceptionPrepare.operationToken(),safe,Set.of(),List.of(),List.of()))));e.noSend=true;return false;
  }
  if(outcome.stage()==EventRunner.Stage.REJECTED ||
     outcome.stage()==EventRunner.Stage.RECOVERY_REQUIRED)
   throw new IllegalStateException("goat J04 effect "+outcome.stage()+" "+outcome.failure());
  if(outcome.stage()!=EventRunner.Stage.COMMITTED)return false;
  if(!e.completed){
   e.completed=true;if(!e.retainClaim)release(e);
  }
  return true;
 }
 private void release(Execution e){
  if(e==null||e.released)return;
  e.arbiter.release(e.token);if(e.admission!=null)e.admission.released(e.decision,e.token);e.released=true;
 }
 private void done(Flight f,String why){
  if(f.warning!=null)f.warning.stop();
  var rows=new ArrayList<DurableRecord>();var completionBlobs=new ArrayList<BlobContent>();
  Map<ScopeRef,Revision> expected=Map.of();
  if(f.slot.phase()==EntityPhase.RESERVED&&(f.phase==Phase.INITIAL||f.phase==Phase.WARNING||
      f.phase==Phase.LEAD||f.phase==Phase.RECOVER&&!spawnPrepared.contains(f.id))){
   var o=f.slot;f.slot=new EntityOwnershipRecord(new AggregateHeader(1,o.header().id(),
       o.header().revision().plus(1),new Sequence(0),now()),o.ownerToken(),o.entityUuid(),
       o.instanceId(),o.cohortId(),o.slotId(),EntityPhase.REMOVED,OwnershipDisposition.RELEASED,
       o.lastKnownChunk(),now(),o.persistMode(),o.cleanupPolicy(),o.adoptedBy(),o.generation());
   slots.put(f.goat,f.slot);rows.add(new DurableAggregate(new EntityOwnershipAggregate(f.slot)));
  }
  if(f.selectedPlan!=null&&f.spawned&&(why.equals("EXACT_OWNER_REMOVED")||why.equals("ALREADY_RELEASED"))
      &&f.slot.phase()==EntityPhase.REMOVED&&f.slot.disposition()==OwnershipDisposition.RELEASED){
   var evidence=Map.<String,JsonValue>of("native_uuid",new JsonValue.Text(f.goat.toString()),
      "present_was_durable",new JsonValue.Bool(true),"exact_uuid_departed",new JsonValue.Bool(true),
      "warning_renderer_received",new JsonValue.Bool(f.warnAt>=0),
      "human_perception_qualified",new JsonValue.Bool(false));
   NativeCatalogueColdCompletion.View coldView=null;
   boolean expired=f.selection==null; // Legacy without sealed keys remains cleanup-only.
   if(f.selection!=null)expired=selectedExpired(f);
   else if(f.coldIdentity!=null){
    if(f.coldRead==null)f.coldRead=new NativeCatalogueColdCompletion.Read(store,f.coldIdentity);
    var cut=f.coldRead.poll();if(cut.isEmpty())return;
    coldView=cut.orElseThrow();expected=coldView.scopeRevisions();
    expired=NativeCatalogueColdCompletion.clock(coldView,now()).value()>=f.selectedDeadline;
   }
   if(expired){
    var expiredEvidence=new HashMap<String,JsonValue>(evidence);expiredEvidence.put("recovery_complete",new JsonValue.Bool(true));
    expiredEvidence.put("selected_deadline",new JsonValue.Text(Long.toString(f.selectedDeadline)));
    if(f.selection!=null)rows.addAll(f.selection.abortRecords("MAX_DURATION_CLEANUP_COMPLETE",expiredEvidence));
    else if(f.coldIdentity!=null){
     var view=Objects.requireNonNull(coldView);
     expiredEvidence.put("definition",new JsonValue.Text(f.coldIdentity.definition().id().value()));
     expiredEvidence.put("decision",new JsonValue.Text(f.selectedDecision.value().toString()));
     expiredEvidence.put("source",new JsonValue.Text("CATALOGUE_SELECTED"));
     expiredEvidence.put("reason",new JsonValue.Text("MAX_DURATION_CLEANUP_COMPLETE"));
     var abortId=new FactId(UUID.nameUUIDFromBytes(("catalogue-aborted-v1\0"+f.id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
     var aborted=new DirectorFact(abortId,new FactType("proutlost_director:catalogue_event_aborted"),
       f.selectedPlan.scopes().scopes().stream().sorted(Comparator.comparing(ScopeRef::toString)).toList(),Optional.of(new EventInstanceId(f.id)),
       EvidenceStatus.CONFIRMED,expiredEvidence,true,Optional.empty());
     rows.add(new DurableFact(aborted));rows.add(NativeCatalogueFactOutbox.abort(aborted,f.selectedDecision,NativeCatalogueColdCompletion.clock(view,now())));
    }
   }else if(f.selection!=null){var contribution=f.selection.completionContribution(evidence);rows.addAll(contribution.records());completionBlobs.addAll(contribution.blobs());}
   else if(f.coldIdentity!=null){
    var view=Objects.requireNonNull(coldView);
    var contribution=NativeCatalogueColdCompletion.completed(f.coldIdentity,view,evidence,false,
      now());
    rows.addAll(contribution.records());completionBlobs.addAll(contribution.blobs());expected=view.scopeRevisions();
   }else System.out.println("DIRECTOR_GOAT_COLD_LEGACY_CLEANUP_ONLY_NO_ORIGINAL_OUTCOME "+f.id);
  }
  if(f.stopRequested&&!f.spawned&&f.selection!=null){
   rows.addAll(f.selection.abortRecords("ADMIN_STOP_BEFORE_SPAWN",Map.of(
    "no_physical_effect",new JsonValue.Bool(true),"perception_exposure",new JsonValue.Bool(f.work!=null),
    "owner_released",new JsonValue.Bool(f.slot.disposition()==OwnershipDisposition.RELEASED))));
  }
  rows.add(new DurableFact(fact(END,f,Map.of("reason",new JsonValue.Text(why)))));
  f.ack=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),expected,rows,completionBlobs)).toCompletableFuture();
  f.phase=Phase.DONE;
 }
 private void warn(Flight f){
  if(!f.warning.available()){done(f,"SESSION_RENEWAL_BEFORE_WARNING");return;}
  PlanningSnapshot context;
  try{context=snapshot(f);}catch(SelectedEventStart.ContextUnavailable wait){return;}
  // No phase, claim, PREPARE or renderer send exists when the fresh frame is unavailable.
  var intent=new EffectIntent(new OperationToken(UUID.randomUUID()),
      new PrimitiveId("proutlost:warning"),1,
      new Warning("witness",DirectorGameplayAssets.WARNING,new DurationTicks(100)),
      context.snapshot().context().terrain().getFirst().loadedFootprint(),
      new EventInstanceId(f.id));
  f.work=run(f,f.warning.capture(intent,context),f.warning,false);
  f.warnSent=now().value();f.phase=Phase.WARNING;
 }
 private void depart(Flight f){
  var provider=binding(f);
  f.work=run(f,capture(f,GoatVisitBinding.DEPART,provider),provider,false);
  f.phase=Phase.DEPARTING;
 }
 public String cancel(ServerPlayer operator){
  actor();if(!operator.hasPermissions(4))return "Goat LAB : OP4 requis.";
  if(flight==null)return "Goat LAB : aucune visite en cours.";
  if(flight.phase==Phase.RECOVER||flight.phase==Phase.DEPARTING||flight.phase==Phase.DONE)
   return "Goat LAB : récupération ou départ déjà engagé.";
  flight.stopRequested=true;
  return "Goat LAB : départ anticipé demandé (inversion J04 dès que possible).";
 }
 public boolean controlRecoveryReady(){actor();return !failed&&scanned&&!store.readOnlySafe();}
 /** Canonical admin ingress after its exact durable intent; completion observes this same physical flight. */
 public Optional<java.util.function.Supplier<AdminStatus>> requestStop(Optional<EventInstanceId> target){
  actor();var f=flight;if(f==null||target.filter(id->!id.value().equals(f.id)).isPresent())return Optional.empty();
  var event=f.id;f.stopRequested=true;
  return Optional.of(()->{actor();return ended.contains(event)?AdminStatus.SUCCEEDED:failed?AdminStatus.FAILED_SAFE:AdminStatus.PENDING;});
 }
 public void poll(){
  actor();if(failed)return;
  try {
   if(store.readOnlySafe())throw new IllegalStateException("goat WAL unavailable");
   if(!scanned){if(!sharedRecovery)scan();return;}
   if(flight==null){recover();return;}
   var f=flight;if(!loaded(f.position))return;
   switch(f.phase){
    case INITIAL->{
     if(!durable(f.ack))return;
     if(selectedExpired(f)){done(f,"SELECTED_MAX_DURATION_BEFORE_WARN");return;}
     if(f.stopRequested){done(f,"STOP_BEFORE_WARN");return;}
     warn(f);
    }
    case WARNING->{
     if(committed(f.work)){
      if(f.work.noSend||!f.warning.available()){done(f,"SESSION_RENEWAL_BEFORE_SEND_NO_REPLAY");return;}
      if(selectedExpired(f)){done(f,"SELECTED_MAX_DURATION_BEFORE_SPAWN");return;}
      if(f.stopRequested){done(f,"STOP_BEFORE_SPAWN");return;}
      if(f.warning.received()){
       f.warnAt=now().value();f.phase=Phase.LEAD;
       System.out.println("DIRECTOR_LAB_GOAT_J06_WARN_RECEIVED id="+f.id);
      }else if(now().value()-f.warnSent>=1200)done(f,"WARN_TIMEOUT");
     }
    }
    case LEAD->{
     if(selectedExpired(f)){done(f,"SELECTED_MAX_DURATION_BEFORE_SPAWN");return;}
     if(f.stopRequested){done(f,"STOP_BEFORE_SPAWN");return;}
     if(now().value()-f.warnAt>=100){
      var p=server.getPlayerList().getPlayer(f.player);
      if(!witness(f)||!campLive(f)){
        done(f,"PLAYER_DISCONNECTED");return;
      }
      if(f.selection!=null){
       try{f.selection.refresh();}catch(SelectedEventStart.ContextUnavailable notFresh){
        if(now().value()-f.warnAt>=1000)done(f,"SELECTED_CONTEXT_EXPIRED_BEFORE_SPAWN");
        return; // No PREPARE/physical write while the real input is unavailable.
       }catch(IllegalStateException refused){done(f,"SELECTED_CONTEXT_REFUSED_BEFORE_SPAWN");return;}
      }
      var provider=binding(f);EffectPreparation captured;
      try{captured=capture(f,GoatVisitBinding.VISIT,provider);}
      catch(SelectedEventStart.ContextUnavailable notFresh){
       if(now().value()-f.warnAt>=1000)done(f,"SELECTED_CONTEXT_EXPIRED_BEFORE_SPAWN");
       return;
      }
      DecisionInput selectedInput=null;
      if(f.selection!=null){
       try{selectedInput=f.selection.refresh();}catch(SelectedEventStart.ContextUnavailable notFresh){
        if(now().value()-f.warnAt>=1000)done(f,"SELECTED_CONTEXT_EXPIRED_BEFORE_SPAWN");
        return;
       }catch(IllegalStateException refused){done(f,"SELECTED_CONTEXT_REFUSED_BEFORE_SPAWN");return;}
      }
      // EventRunner.submit, native apply and RESULT handling stay outside the transient-context catches.
      f.work=run(f,captured,provider,true,selectedInput);f.impact=f.work;
      f.phase=Phase.SPAWNING;
     }
    }
    case SPAWNING->{if(committed(f.work)){
     f.spawned=true;spawnedKnown.add(f.id);f.warning.stop();f.expires=now().value()+(f.stopRequested?0:Math.min(400,selectedRemaining(f)));f.phase=Phase.LIVE;
     System.out.println("DIRECTOR_LAB_GOAT_REAL_SPAWN id="+f.id+" uuid="+f.goat);
    }}
    case LIVE->{if(f.stopRequested||selectedExpired(f)||now().value()>=f.expires)depart(f);}
    case DEPARTING->{if(committed(f.work)){
     done(f,"EXACT_OWNER_REMOVED");
     System.out.println("DIRECTOR_LAB_GOAT_REAL_DEPART id="+f.id+" uuid="+f.goat);
    }}
    case RECOVER->{
     if(f.slot.phase()==EntityPhase.REMOVED||f.slot.disposition()==OwnershipDisposition.RELEASED){
      if(level(f.position).getEntity(f.goat)!=null)throw new IllegalStateException("released original goat UUID physically present; reconcile before END");
      done(f,"ALREADY_RELEASED");return;
     }
     var entity=level(f.position).getEntity(f.goat);
     if(entity==null){
      if(f.slot.phase()==EntityPhase.RESERVED&&!spawnPrepared.contains(f.id))done(f,"PRESPAWN_NO_REPLAY");
      return; // Unloaded or ambiguous entity never proves a completed cleanup.
     }
     if(!(entity instanceof Goat)||!entity.isAlive()||
        !EntityDirectorAttachment.read(entity).equals(Optional.of(f.slot)))
      throw new IllegalStateException("goat recovery requires original attached live entity");
     depart(f);
     System.out.println("DIRECTOR_LAB_GOAT_RECOVERY_DEPART_NO_RESPAWN "+f.id);
    }
    case DONE->{if(durable(f.ack)){
     release(f.impact);ended.add(f.id);active.remove(f.id);flight=null;
     System.out.println("DIRECTOR_LAB_GOAT_CLEANUP_DURABLE "+f.id);
    }}
   }
  }catch(RuntimeException invalid){
   failed=true;System.out.println("DIRECTOR_LAB_GOAT_RECOVERY_REQUIRED "+invalid);invalid.printStackTrace();
  }
 }
 private void scan(){
  if(pending==null){
   pending=store.read(new StoreQuery(world,Set.of(),new Sequence(0),cursor,128)).toCompletableFuture();
   return;
  }
  if(!pending.isDone())return;
  var read=pending.getNow(null);pending=null;
  if(read.status()==ReadStatus.STALE){
   cursor=Optional.empty();active.clear();ended.clear();slots.clear();spawnPrepared.clear();spawnedKnown.clear();reservationFloor=0;return;
  }
  if(read.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("goat WAL scan unavailable");
  var page=read.page().orElseThrow();
  acceptRecoveryRows(page.records(),!page.hasMore());
  cursor=page.nextCursor();
 }
 private boolean sharedRecovery;
 public void useSharedRecoveryScan(){actor();sharedRecovery=true;resetRecoveryScan();}
 public void resetRecoveryScan(){actor();scanned=false;pending=null;cursor=Optional.empty();active.clear();ended.clear();slots.clear();spawnPrepared.clear();spawnedKnown.clear();reservationFloor=0;}
 public void acceptRecoveryRows(List<DurableRecord> rows,boolean last){actor();
  for(var row:rows){
   if(row instanceof DurablePrepare prep){
    reservationFloor=Math.max(reservationFloor,prep.value().reservationGeneration().value());
    for(var op:prep.value().slice().operations())
     if(op.primitive().equals(GoatVisitBinding.VISIT))spawnPrepared.add(op.instanceId().value());
   }
   if(row instanceof DurableAggregate agg && agg.state() instanceof EntityOwnershipAggregate state){
    if(state.value().slotId().equals("goat-client-lab")&&state.value().phase()==EntityPhase.PRESENT)spawnedKnown.add(state.value().instanceId().value());
    var old=slots.get(state.value().entityUuid());
    if(old==null||state.value().header().revision().compareTo(old.header().revision())>0)
     slots.put(state.value().entityUuid(),state.value());
   }
   if(row instanceof DurableFact fact && fact.value().eventId().isPresent()){
    var id=fact.value().eventId().orElseThrow().value();
    if(fact.value().factType().equals(START))active.put(id,fact.value());
    else if(fact.value().factType().equals(END))ended.add(id);
   }
  }

  if(last){
   ended.forEach(active::remove);
   scanned=true;System.out.println("DIRECTOR_LAB_GOAT_RECOVERY_SCAN pending="+active.size());
  }

 }

 private static String text(DirectorFact f,String k){return ((JsonValue.Text)f.payload().get(k)).value();}
 private void recover(){
  if(active.isEmpty())return;
  if(active.size()>1)throw new IllegalStateException("multiple pending goat events, manual reconcile");
  var entry=active.entrySet().iterator().next();var fact=entry.getValue();
  var initial=SLOT.decode(Base64.getDecoder().decode(text(fact,"slot")));
  var f=new Flight();f.id=entry.getKey();f.goat=initial.entityUuid();
  f.player=UUID.fromString(text(fact,"player"));
  f.position=new BlockPosition(new DimensionId(text(fact,"dimension")),
     Integer.parseInt(text(fact,"x")),Integer.parseInt(text(fact,"y")),Integer.parseInt(text(fact,"z")));
  if(!loaded(f.position))return;
  f.slot=Objects.requireNonNull(slots.get(f.goat),"entity WAL state missing");f.spawned=spawnedKnown.contains(f.id);
  if(fact.payload().get("selected_plan") instanceof JsonValue.Text selected){
   f.selectedPlan=new ModelCodec<>(ExecutionPlan.class).decode(Base64.getDecoder().decode(selected.value()));
   f.selectedDecision=new DecisionId(UUID.fromString(text(fact,"selected_decision")));
   f.selectedDeadline=fact.payload().get("selected_deadline") instanceof JsonValue.Text deadline?Long.parseLong(deadline.value()):Long.MIN_VALUE;
   f.selectedAnchor=new ModelCodec<>(AnchorRecord.class).decode(Base64.getDecoder().decode(text(fact,"selected_anchor")));
   if(fact.payload().get("parent_chain") instanceof JsonValue.Text parent)
    f.parentChain=Optional.of(new ChainId(UUID.fromString(parent.value())));
   f.coldIdentity=NativeCatalogueColdCompletion.restore(fact).orElse(null);
   if(f.coldIdentity!=null&&(!f.coldIdentity.plan().equals(f.selectedPlan)||!f.coldIdentity.decision().equals(f.selectedDecision)||!f.coldIdentity.parentChain().equals(f.parentChain)))
    throw new IllegalStateException("selected goat cold pin mismatch");
   if(!f.selectedPlan.effects().getFirst().instanceId().value().equals(f.id)
       ||!f.selectedPlan.definition().id().equals(new DefinitionId("proutlost_director:candidate/goat_guest")))
    throw new IllegalStateException("selected goat WAL lineage mismatch");
  }
  if(!initial.instanceId().equals(new EventInstanceId(f.id))||
     !f.slot.instanceId().equals(initial.instanceId())||
     !f.slot.ownerToken().equals(initial.ownerToken())||
     !f.slot.entityUuid().equals(initial.entityUuid()))
   throw new IllegalStateException("goat J04 recovery lineage mismatch");
  f.phase=Phase.RECOVER;flight=f;
 }
 public boolean recovering(){actor();return !scanned&&!failed;}
 public String status(){actor();return failed?"GOAT_RECOVERY_REQUIRED":!scanned?"GOAT_RECOVERING":
     flight==null?"GOAT_IDLE":"GOAT_"+flight.phase+" id="+flight.id;}
 @Override public void close(){actor();closed=true;if(flight!=null&&flight.warning!=null)flight.warning.stop();}
}
