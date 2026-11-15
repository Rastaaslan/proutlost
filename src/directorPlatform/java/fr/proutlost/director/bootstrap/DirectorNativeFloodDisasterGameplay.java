package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.ownership.*;
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
import net.minecraft.world.phys.AABB;

/** Explicit OP4 TEST_ONLY terrain disasters. Natural risk eligibility is never asserted.
 * No forward effect or damage is replayed after restart. Healthy cleanup requires fresh J04.
 */
public final class DirectorNativeFloodDisasterGameplay implements AutoCloseable {
 private static final PrimitiveId PHYSICAL=ControlledWaterBinding.ID,WARNING=new PrimitiveId("proutlost:warning");
 private static final CapabilityId FLUID=ControlledWaterBinding.CAP;
 private static final FactType START=new FactType("proutlost_director:lab_flood_disaster_start"),END=new FactType("proutlost_director:lab_flood_disaster_end");
 private static final FactType PHYSICS=new FactType("proutlost_director:flood_disaster_physics");
 private static final ModelCodec<PreparedEffect> PLAN=new ModelCodec<>(PreparedEffect.class);
 private static final ModelCodec<OwnershipRecord> OWNER=new ModelCodec<>(OwnershipRecord.class);
 private static final ModelCodec<BlockPosition> POSITION=new ModelCodec<>(BlockPosition.class);
 private static final ModelCodec<Footprint> FOOTPRINT=new ModelCodec<>(Footprint.class);
 private static final ModelCodec<RouteSegment> EXIT=new ModelCodec<>(RouteSegment.class);
 private static final ModelCodec<SliceResult> RESULT=new ModelCodec<>(SliceResult.class);
 private static final BudgetVector CAP=new BudgetVector(100,100,100,100,100,2048,4194304,100,1048576,100);
 private static final DurationTicks LEAD=new DurationTicks(400);
 private final MinecraftServer server;private final WorldId world;private final JournalStore store;
 private final ServerOwnershipAttachments attached;private final Map<ResourceKey,OwnershipRecord> owners;
 private final DurableDisasterRunner phases;
 private final Map<UUID,DirectorFact> starts=new LinkedHashMap<>();
 private final Map<DisasterId,DisasterInstance> restoredDisasters=new HashMap<>();
 private final Map<DisasterId,Tick> warnEntries=new HashMap<>();
 private final Map<ResourceKey,OwnershipRecord> journalOwners=new HashMap<>();
 private final Map<MutationId,MutationRecord> journalMutations=new HashMap<>();
 private final Map<OperationToken,PrepareRecord> prepares=new LinkedHashMap<>();
 private final Map<OperationToken,ResultRecord> results=new HashMap<>();
 private final Map<UUID,List<DirectorFact>> consequences=new HashMap<>();
 private final Set<UUID> ends=new HashSet<>();
 private CompletableFuture<StoreReadResult> scanning;private Optional<String> cursor=Optional.empty();
 private long floor,clockFloor,offset;private boolean scanned,shared,closed,failed;private Flight flight;
 private enum Phase { INITIAL,WARN_ACK,MARKERS,PROOF_ACK,LEAD,SEAL,IMPACT_ACK,IMPACT,
   MUTATION_READ,MUTATION_ACK,JOIN_ACK,PULSE_WAIT,PULSE,HOLD,STABILIZE_ACK,CLEANUP,INVERSE,
   OWNER_READ,RELEASE_ACK,CLEAN_JOIN_ACK,RESOLVE_ACK,END_ACK,REPAIR_ACK,RECOVERY_BLOBS,CONFLICT }
 private static final class Tx {DurableBatch batch;StoreSession session;CompletableFuture<CommitAck> ack;}
 private static final class Execution {
  EventRunner runner;TransactionId tx;PrepareRecord prepare;PreparedEffect plan;
  LiveValidationSnapshot live;ResourceArbiter arbiter;ReservationToken token;
  AdmissionService admission;DecisionId decision;Guarded guard;
  boolean retain,released,done,aborted;SliceResult actual,terminalOverride;
 }
 private static final class Marker {
  PlayerId player;String corner;PerceptionTransform transform;NativeWarningBinding binding;
  EffectIntent intent;Execution execution;
 }
 private static final class Flight {
  UUID id,player;DisasterId disaster;DisasterRunner.Family family;DefinitionId definition,pulseDefinition;
  List<BlockPosition> sources,destinations,cells;Footprint footprint,envelope;List<RouteSegment> exits;
  Map<ResourceKey,OwnershipRecord> initial=new HashMap<>(),releaseBefore=new HashMap<>(),releaseAfter=new HashMap<>();
  Map<MutationId,MutationRecord> mutations=new LinkedHashMap<>();
  Set<BlockPosition> selected;Set<PlayerId> audience;Epoch audienceRevision=new Epoch(1);
  CatalogSnapshot catalog;PreparedEffect forward;List<Marker> markers=new ArrayList<>();
  List<DirectorFact> consequences=new ArrayList<>();DisasterInstance initialState;
  Phase phase=Phase.INITIAL;Tx tx;Execution work,impact;boolean stop,recovery,forwardSucceeded,afterCleanup,conflict,pulsed,drained;
  BlobContent mask;BlobRef maskRef;Set<BlobRef> recoveryRefs=Set.of();long units,pulseAt;PreparedEffect importedPlan;BlobBundle importedBlobs;CompletableFuture<BlobReadResult> blobRead;
  ResourceId recipe=ControlledWaterBinding.IMPORT;
  final List<Execution> held=new ArrayList<>();
  ControlledWaterBinding preparedImpact;EffectPreparation capturedImpact;
  long markerDeadline,expires,appliedAt=-1;int markerIndex;CompletableFuture<StoreReadResult> mutationRead,ownerRead;StoreQuery mutationQuery,ownerQuery;
 }
 /** Guard is checked again inside the ServerEffectPort's synchronous physical boundary. */
 private final class Guarded implements ServerEffectPort.Binding {
  private final Flight flight;private final ControlledWaterBinding delegate;boolean invoked;
  Guarded(Flight flight,ControlledWaterBinding delegate){this.flight=flight;this.delegate=delegate;}
  public ProviderMode mode(){return delegate.mode();}
  public Set<PrimitiveId> supportedPrimitives(){return delegate.supportedPrimitives();}
  public Set<CapabilityId> capabilities(){return delegate.capabilities();}
  public EffectPreparation capture(EffectIntent i,PlanningSnapshot p){return delegate.capture(i,p);}
  public boolean revalidate(PreparedEffect p,BlobBundle b){return contextSafe(flight)&&delegate.revalidate(p,b);}
  public SliceResult apply(PreparedEffect p,BlobBundle b){
   if(!revalidate(p,b))throw new IllegalStateException("disaster live warning/audience/exit changed");
   invoked=true;return delegate.apply(p,b);
  }
  public ReconcileResult reconcile(RecoveryProbe p){return delegate.reconcile(p);}
 }
 public DirectorNativeFloodDisasterGameplay(MinecraftServer server,WorldId world,JournalStore store,
     ServerOwnershipAttachments attached,Map<ResourceKey,OwnershipRecord> owners){
  this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.store=Objects.requireNonNull(store);
  this.attached=Objects.requireNonNull(attached);this.owners=Objects.requireNonNull(owners);
  phases=new DurableDisasterRunner(store,4,d->new ScopeRef(ScopeKind.EVENT,d.disasterId().value()));actor();
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("terrain disaster actor");}
 private Tick now(){return new Tick(Math.addExact(server.overworld().getGameTime(),offset));}
 // Shared native block hooks use Minecraft gameTime; ownership timestamps must match that clock.
 private Tick blockNow(){return new Tick(server.overworld().getGameTime());}
 private static BlockPos pos(BlockPosition p){return new BlockPos(p.x(),p.y(),p.z());}
 private static ResourceKey key(BlockPosition p){return new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z());}
 private ServerLevel level(Flight f){for(var l:server.getAllLevels())if(l.dimension().location().toString().equals(f.footprint.dimension().value()))return l;throw new IllegalStateException("disaster dimension unavailable");}
 private boolean loaded(Flight f){var l=level(f);return f.envelope.loadedChunks().stream().allMatch(c->l.getChunkSource().getChunkNow(c.x(),c.z())!=null);}
 private Fingerprint hash(Flight f,BlockPosition p){return BlockTransformBinding.stateBlob(level(f).getBlockState(pos(p))).reference().hash();}
 private void publish(Flight f,BlockPosition p,OwnershipRecord o){owners.put(key(p),o);attached.write(level(f),pos(p),o);}
 private static String encode(byte[] b){return Base64.getEncoder().encodeToString(b);}
 private static String text(DirectorFact f,String k){return ((JsonValue.Text)f.payload().get(k)).value();}
 private static byte[] decode(DirectorFact f,String k){return Base64.getDecoder().decode(text(f,k));}
 private static JsonValue.Text text(Object value){return new JsonValue.Text(value.toString());}
 private static List<JsonValue> positions(List<BlockPosition> cells){return cells.stream().map(p->(JsonValue)new JsonValue.Text(encode(POSITION.encode(p)))).toList();}
 private static List<BlockPosition> positions(DirectorFact f,String k){return ((JsonValue.Array)f.payload().get(k)).values().stream().map(v->POSITION.decode(Base64.getDecoder().decode(((JsonValue.Text)v).value()))).toList();}
 private Tx append(List<DurableRecord> rows,List<BlobContent> blobs){
  var tx=new Tx();tx.session=store.session();tx.batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),tx.session.storeGeneration(),Map.of(),rows,blobs);
  tx.ack=store.append(tx.batch).toCompletableFuture();return tx;
 }
 private Tx append(List<DurableRecord> rows){return append(rows,List.of());}
 private boolean durable(Tx tx){
  if(tx==null||!tx.ack.isDone())return false;
  var ack=tx.ack.getNow(null);
  if(ack==null||ack.ackStatus()!=AckStatus.DURABLE||!store.session().equals(tx.session)
    ||!ack.transactionId().equals(tx.batch.transactionId())||!ack.transactionHash().equals(tx.batch.transactionHash())
    ||!ack.storeGeneration().equals(tx.session.storeGeneration())||ack.durableSequence().value()<=0
    ||ack.permitAttempt().isPresent()||!ack.forcedBlobs().equals(DurableBlobReferences.collect(tx.batch.records())))
   throw new IllegalStateException("terrain disaster J04 metadata ACK mismatch");
  return true;
 }
 private DirectorFact fact(FactType type,Flight f,Map<String,JsonValue> payload){
  return new DirectorFact(new FactId(UUID.randomUUID()),type,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),
   Optional.of(new EventInstanceId(f.id)),EvidenceStatus.CONFIRMED,payload,true,Optional.empty());
 }
 private DirectorFact dossier(Flight f){
  var values=new TreeMap<String,JsonValue>();values.put("player",text(f.player));values.put("disaster",text(f.disaster.value()));
  values.put("family",text(f.family));values.put("definition",text(f.definition.value()));values.put("plan",text(encode(PLAN.encode(f.forward))));
  values.put("sources",new JsonValue.Array(positions(f.sources)));values.put("destinations",new JsonValue.Array(positions(f.destinations)));
  values.put("footprint",text(encode(FOOTPRINT.encode(f.footprint))));values.put("warning_envelope",text(encode(FOOTPRINT.encode(f.envelope))));
  values.put("owners",new JsonValue.Array(f.cells.stream().map(p->(JsonValue)text(encode(OWNER.encode(f.initial.get(key(p)))))).toList()));
  values.put("exits",new JsonValue.Array(f.exits.stream().map(e->(JsonValue)text(encode(EXIT.encode(e)))).toList()));
  values.put("tick",text(now().value()));return fact(START,f,values);
 }
 private EventDefinition definition(Flight f){return Objects.requireNonNull(f.catalog.events().get(f.definition));}
 private Warning warning(Flight f){return (Warning)definition(f).steps().stream().filter(s->s.args() instanceof Warning).findFirst().orElseThrow().args();}
 private ControlledWater impact(Flight f){return new ControlledWater(ControlledWaterBinding.IMPORT,f.maskRef,f.units,8);}
 private static boolean twoLayer(Flight f){return f.cells.stream().mapToInt(BlockPosition::y).distinct().count()==2;}
 private void catalogue(Flight f){var c=new DirectorLabFloodDisasterCatalog(DirectorLabObservationProfile.load(),f.exits,f.maskRef,f.units);f.catalog=c.snapshot();f.definition=c.definitionId();f.pulseDefinition=c.pulseDefinitionId();}
 private Set<PlayerId> audience(Flight f){
  var set=new HashSet<PlayerId>();var operator=server.getPlayerList().getPlayer(f.player);
  if(operator!=null&&operator.isAlive()&&operator.serverLevel()==level(f))set.add(new PlayerId(f.player));
  var b=f.envelope.bounds().getFirst();var box=new AABB(b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive());
  for(var p:server.getPlayerList().getPlayers())if(p.isAlive()&&p.serverLevel()==level(f)&&p.getBoundingBox().intersects(box))set.add(new PlayerId(p.getUUID()));
  return Set.copyOf(set);
 }
 private boolean playerReady(Flight f){var p=server.getPlayerList().getPlayer(f.player);return p!=null&&p.isAlive()&&p.hasPermissions(4)&&p.serverLevel()==level(f)&&p.distanceToSqr(f.cells.getFirst().x()+.5,f.cells.getFirst().y(),f.cells.getFirst().z()+.5)<=256;}
 private boolean exitsReady(Flight f){
  var l=level(f);
  for(var r:f.exits){int dx=Integer.compare(r.to().x()-r.from().x(),0),dz=Integer.compare(r.to().z()-r.from().z(),0);
   for(int i=0;i<=2;i++){var p=new BlockPos(r.from().x()+dx*i,r.from().y(),r.from().z()+dz*i);
    if(l.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)==null||!l.getBlockState(p.below()).isSolidRender(l,p.below())
      ||!l.getFluidState(p).isEmpty()||!l.noCollision(new AABB(p.getX()+.2,p.getY(),p.getZ()+.2,p.getX()+.8,p.getY()+1.8,p.getZ()+.8)))return false;
   }
  }return true;
 }
 private boolean markersLive(Flight f){return !f.markers.isEmpty()&&f.markers.stream().allMatch(m->m.binding.receivedAndLive());}
 private boolean contextSafe(Flight f){return !f.stop&&loaded(f)&&playerReady(f)&&exitsReady(f)&&audience(f).equals(f.audience)&&markersLive(f);}
 public String begin(ServerPlayer player){
  actor();if(!scanned||failed||store.readOnlySafe()||flight!=null||!starts.isEmpty())return "FLOOD LAB en attente/recuperation.";
  if(!player.hasPermissions(4)||!player.isAlive())return "OP4 vivant requis.";
  var layout=NativeFloodDisasterLayout.survey(player);var f=new Flight();f.id=UUID.randomUUID();f.player=player.getUUID();f.disaster=new DisasterId(UUID.randomUUID());
  f.family=DisasterRunner.Family.FLOOD;f.mask=layout.mask();f.maskRef=f.mask.reference();f.units=layout.observedUnits();f.cells=layout.cells();f.sources=f.cells;f.destinations=List.of();f.footprint=layout.footprint();f.envelope=layout.warningEnvelope();f.exits=layout.exits();f.selected=new HashSet<>(f.cells);
  catalogue(f);f.audience=audience(f);if(f.audience.isEmpty()||f.audience.size()>4)throw new IllegalArgumentException("FLOOD LAB requires one to four actual affected clients");
  for(var p:f.cells){
   var old=attached.read(level(f),pos(p)).orElse(null);if(old!=null&&old.disposition()!=OwnershipDisposition.RELEASED)throw new IllegalArgumentException("active/player provenance in actual basin");
   f.initial.put(key(p),new OwnershipRecord(new AggregateHeader(1,old==null?UUID.randomUUID():old.header().id(),old==null?new Revision(1):old.header().revision().plus(1),new Sequence(0),blockNow()),
    key(p),Set.of(new OwnerToken(UUID.randomUUID())),old==null?new Epoch(1):old.epoch().plus(1),OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),hash(f,p),old==null?new Epoch(0):old.playerOverrideEpoch(),Optional.empty()));
  }
  var captured=capture(f,provider(f));f.forward=captured.prepared().orElseThrow();f.importedPlan=f.forward;f.importedBlobs=captured.blobs();
  var warning=new WarningEvidence(f.audience,now(),LEAD,BlobContent.hash(new byte[0]),f.audienceRevision,false,false);
  f.initialState=new DisasterInstance(new AggregateHeader(1,f.disaster.value(),new Revision(1),new Sequence(0),now()),f.disaster,new EventInstanceId(f.id),f.definition,DisasterStage.WATCH,
   Map.of(new ResourceId("proutlost_director:manual_finite_water_pulse"),new UnitValue(1)),new UnitValue(1),f.envelope,warning,f.audience,f.exits,f.maskRef,Set.of(),"WATCH_REAL_NATIVE_RESERVOIR",Set.of());
  for(var recipient:f.audience)if(!fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,recipient,4))
   throw new SelectedEventStart.ContextUnavailable("warning tranche session capacity/handshake pending");
  buildMarkers(f);var start=dossier(f);var rows=new ArrayList<DurableRecord>();f.initial.values().forEach(o->rows.add(new DurableOwnership(o)));
  rows.add(new DurableFact(start));rows.add(new DurableAggregate(new DisasterAggregate(f.initialState)));f.tx=append(rows,List.of(f.mask));starts.put(f.id,start);flight=f;
  return twoLayer(f)?"FLOOD LAB immersion : quatre sources reelles32 sur deux niveaux, WARN20s puis maintien20s; restauration exacte si credit intact.":
   "FLOOD LAB manuel : reservoir reel26, WARN20s puis UNE impulsion conservatrice1unite; restauration exacte si credit intact.";
 }
 private ControlledWaterBinding provider(Flight f){return new ControlledWaterBinding(d->level(f),()->new OwnershipView(owners),(p,o)->publish(f,p,o),this::blockNow,attached);}
 private PlanningSnapshot snapshot(Flight f){
  var view=new HashMap<>(owners);if(f.phase==Phase.INITIAL)view.putAll(f.initial);
  return ControlledWaterPlanning.snapshot(server,world,Objects.requireNonNull(f.mask),view,now());
 }
 private EffectPreparation capture(Flight f,ControlledWaterBinding binding){
  var args=new ControlledWater(f.recipe,f.maskRef,f.recipe.equals(ControlledWaterBinding.IMPORT)?f.units:f.recipe.equals(ControlledWaterBinding.PULSE)?1:0,8);
  var event=new EventInstanceId(f.id);var operation=f.recipe.equals(ControlledWaterBinding.PULSE)?ControlledWaterBinding.pulseToken(event):new OperationToken(UUID.randomUUID());
  var intent=new EffectIntent(operation,PHYSICAL,1,args,f.footprint,event);
  if(f.recipe.equals(ControlledWaterBinding.PULSE))binding.registerPulse(intent,Objects.requireNonNull(f.importedPlan),Objects.requireNonNull(f.importedBlobs));
  if(f.recipe.equals(ControlledWaterBinding.RESTORE))binding.registerRestore(intent,Objects.requireNonNull(f.importedPlan),Objects.requireNonNull(f.importedBlobs));
  return binding.capture(intent,snapshot(f));
 }
 private ResourceArbiter arbiter(){return new ResourceArbiter(world,new Epoch(Math.max(floor,store.session().bootEpoch().value())),1000,4,new ResourceArbiter.Compatibility(){
  public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId r,ResourceClaim a,ResourceClaim b){return false;}
 });}
 private final Set<OperationToken> knownDurablePrepares=new HashSet<>();
 private final Map<TransactionId,Tx> safeAborts=new HashMap<>();
 private Execution run(Flight f,EffectPreparation captured,ServerEffectPort.Binding binding,boolean impact){
  var p=captured.prepared().orElseThrow();var i=p.intent();var e=new Execution();e.plan=p;e.retain=impact;e.arbiter=arbiter();
  if(binding instanceof Guarded guard)e.guard=guard;
  var scope=new ScopeRef(ScopeKind.WORLD,world.value());var epochs=new HashMap<ResourceKey,Epoch>();p.claims().forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
  if(impact){
   var def=Objects.requireNonNull(f.catalog.events().get(((ControlledWater)i.typedArgs()).recipe().equals(ControlledWaterBinding.PULSE)?f.pulseDefinition:f.definition));var roles=new RoleAssignments(Map.of("witness",List.of(new PlayerId(f.player))));
   var ref=new DefinitionRef(def.id(),def.contentRevision(),BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(def)));
   var intents=new ArrayList<EffectIntent>();f.markers.forEach(m->intents.add(m.intent));intents.add(i);
   var execution=new ExecutionPlan(new CandidateId(UUID.randomUUID()),ref,intents,roles,new ScopeSet(world,Set.of(scope,new ScopeRef(ScopeKind.PLAYER,f.player))),p.preconditions());
   var request=new AdmissionRequest(new DecisionId(UUID.randomUUID()),p.preconditions(),p.claims(),roles,execution);
   var validator=new AdmissionService.HardValidator(){
    public ProviderMode mode(){return ProviderMode.TEST_ONLY;}
    public AdmissionService.Assessment assess(AdmissionRequest proposed,Tick tick){
     boolean valid=proposed.equals(request)&&phases.view(f.disaster).stage()==DisasterStage.IMPACT&&contextSafe(f)
       &&def.steps().stream().anyMatch(step->step.primitive().equals(PHYSICAL)&&step.args().equals(i.typedArgs()))&&i.expectedFootprint().equals(f.footprint)&&binding.revalidate(p,captured.blobs());
     return new AdmissionService.Assessment(new ValidationResult(valid,valid?List.of():List.of(ReasonCode.STALE_CONTEXT),p.preconditions()),p.claims(),
       new ResourceArbiter.Rank(false,false,false,Priority.DANGER,0,1,42),Map.of(scope,def.budget()));
    }
   };
   e.admission=new AdmissionService(()->f.catalog,validator,e.arbiter,this::now,new DurationTicks(1200),4,ProviderMode.TEST_ONLY);e.decision=request.proposalId();
   if(e.admission.admit(request).status()!=AdmissionStatus.DEFERRED)throw new IllegalStateException("manual disaster J03 queue rejected");
   var answer=e.admission.resolveWindow(epochs,Map.of(scope,CAP)).get(e.decision);
   if(answer==null||answer.status()!=AdmissionStatus.ACCEPTED)throw new IllegalStateException("manual disaster J03 denied "+answer);
   e.token=answer.token().orElseThrow();
   System.out.println("DIRECTOR_FLOOD_DISASTER_J02_J03_MANUAL_ACCEPTED "+f.id+" family="+f.family+" definition="+def.id().value()+" automatic_risk=false");
  }else{
   var c=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),i.instanceId(),new ResourceArbiter.Rank(false,false,false,Priority.DANGER,0,1,42),now().plus(1200),p.claims(),Map.of(scope,p.budgetEstimate()));
   e.token=e.arbiter.arbitrate(List.of(c),now(),epochs,Map.of(scope,CAP)).getFirst().accepted().orElseThrow();
  }
  floor=Math.max(floor,e.token.generation().value());
  var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),i.operationToken(),i.expectedFootprint(),p.preconditions(),List.of(i),p.budgetEstimate());
  e.live=new LiveValidationSnapshot(p.preconditions(),i.expectedFootprint().loadedChunks(),HealthMode.NORMAL,now(),e.token.generation());
  var authority=new CommitPermit.Authority(store,4);
  var port=new ServerEffectPort(authority,x->{throw new IllegalStateException("already captured");},x->e.live,Map.of(i.primitive(),binding),binding.capabilities(),4,
    x->new StabilizationLiveSnapshot(store.session(),e.live,new OwnershipView(owners)),m->{},(m,r)->{});
  port.hydrate(p,captured.blobs());
  var prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),i.operationToken(),e.token.generation(),p.preconditions(),e.token.expiryTick(),slice,e.token,captured.validation(),List.of(),
    Set.of(),List.of(),p.durableBlobs());
  e.prepare=prepare;e.tx=prepare.transactionId();prepares.put(i.operationToken(),prepare);e.runner=new EventRunner(authority,store,port,e.arbiter,4);
  var batch=new DurableBatch(e.tx,store.session().storeGeneration(),prepare.readSet().revisions(),List.of(new DurablePrepare(prepare)),captured.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
  e.runner.submit(batch,p.expectedPost(),e.live,actual->{
   e.actual=actual;if(impact)f.appliedAt=now().value();var rows=new ArrayList<DurableRecord>();rows.add(new DurableResult(new ResultRecord(e.tx,i.operationToken(),actual,Set.of(),List.of(),List.of())));
   if(i.primitive().equals(PHYSICAL)){
    for(var cell:p.intent().expectedFootprint().cells()){
     var k=key(cell);if(!p.expectedPost().containsKey(k))continue;rows.add(new DurableOwnership(Objects.requireNonNull(owners.get(k))));
     var mutation=physicalMutation(f,e,cell,impact||((ControlledWater)i.typedArgs()).recipe().equals(ControlledWaterBinding.DRAIN)?MutationState.VERIFIED:MutationState.ROLLED_BACK);
     f.mutations.put(mutation.mutationId(),mutation);rows.add(new DurableMutation(mutation));
    }
    var data=new TreeMap<String,JsonValue>();data.put("direction",text(impact?"IMPACT":"INVERSE"));
    data.put("prepare",text(e.tx.value()));data.put("operation",text(i.operationToken().value()));data.put("slice",text(slice.sliceId().value()));
    data.put("result",text(encode(RESULT.encode(actual))));data.put("tick",text(now().value()));
    var water=binding instanceof Guarded guard?guard.delegate:(ControlledWaterBinding)binding;
    data.put("volume",new JsonValue.ObjectValue(water.volumeEvidence()));data.put("recipe",text(((ControlledWater)i.typedArgs()).recipe().value()));
    data.put("forced_finite_pulse",new JsonValue.Bool(((ControlledWater)i.typedArgs()).recipe().equals(ControlledWaterBinding.PULSE)));
    var observed=fact(PHYSICS,f,data);f.consequences.add(observed);rows.add(new DurableFact(observed));
   }
   return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,List.of());
  });f.held.add(e);return e;
 }
 private MutationRecord physicalMutation(Flight f,Execution e,BlockPosition cell,MutationState state){
  var k=key(cell);var initial=f.initial.get(k);var id=initial.creationMutationId();var old=f.mutations.get(id);var p=e.plan;
  // Stable logical cell mutation: keep the actual origin PRE; update to the true current POST.
  // The real per-wave slice/result remains inline in the immutable physics fact and complete WAL history.
  var before=old==null?Objects.requireNonNull(p.durableBlobs().preStates().get(k)):old.preStateRef();
  var read=old==null?p.preconditions():old.readSetVersions();var slice=old==null?e.prepare.slice().sliceId():old.sliceId();
  return new MutationRecord(new AggregateHeader(1,id.value(),old==null?new Revision(1):old.header().revision().plus(1),old==null?new Sequence(0):old.header().lastSequence(),now()),
   id,new EventInstanceId(f.id),slice,state,PHYSICAL,1,new Footprint(cell.dimension(),Set.of(new ChunkRef(cell.dimension(),cell.x()>>4,cell.z()>>4)),Set.of(cell),List.of()),
   before,Objects.requireNonNull(p.durableBlobs().expectedPostStates().get(k)),initial.ownerTokens().iterator().next(),initial.epoch(),read,Set.of(),List.of(),Optional.empty(),new Sequence(0));
 }
 private void release(Execution e){
  if(e==null||e.released)return;e.arbiter.release(e.token);if(e.admission!=null)e.admission.released(e.decision,e.token);e.released=true;
 }
 private boolean applied(Flight f,Execution e){
  if(e.done)return true;
  if(e.aborted){if(!durable(safeAborts.get(e.tx)))return false;results.put(e.prepare.operationToken(),new ResultRecord(e.tx,e.prepare.operationToken(),e.terminalOverride,Set.of(),List.of(),List.of()));e.done=true;release(e);return true;}
  e.live=new LiveValidationSnapshot(e.live.readSet(),e.live.loadedChunks(),e.live.health(),now(),e.token.generation());
  var view=e.runner.poll(e.tx,e.live);
  if(view.stage()==EventRunner.Stage.REJECTED)throw new IllegalStateException("disaster PREPARE rejected; durable existence not guessed "+view.failure());
  if(view.stage()!=EventRunner.Stage.PREPARING)knownDurablePrepares.add(e.prepare.operationToken());
  if(view.stage()==EventRunner.Stage.RECOVERY_REQUIRED){
   if(e.guard!=null&&!e.guard.invoked){
    var safe=new SliceResult(Outcome.ABORTED_SAFE,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
    safeAborts.put(e.tx,append(List.of(new DurableResult(new ResultRecord(e.tx,e.prepare.operationToken(),safe,Set.of(),List.of(),List.of())))));
    e.terminalOverride=safe;e.aborted=true;f.stop=true;return false;
   }
   if(e.plan.intent().primitive().equals(WARNING)&&view.result().isEmpty()){
    var unknown=new SliceResult(Outcome.DELIVERY_UNCERTAIN,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
    safeAborts.put(e.tx,append(List.of(new DurableResult(new ResultRecord(e.tx,e.prepare.operationToken(),unknown,Set.of(),List.of(),List.of())))));
    e.terminalOverride=unknown;e.aborted=true;f.stop=true;return false;
   }
   throw new IllegalStateException("physical disaster reconciliation required "+view.failure());
  }
  if(view.stage()!=EventRunner.Stage.COMMITTED)return false;
  e.done=true;results.put(e.prepare.operationToken(),new ResultRecord(e.tx,e.prepare.operationToken(),Objects.requireNonNull(e.actual),Set.of(),List.of(),List.of()));
  if(!e.retain)release(e);return true;
 }
 private void buildMarkers(Flight f){
  var b=f.envelope.bounds().getFirst();var w=warning(f);
  for(var player:f.audience)for(var corner:List.of("NW","NE","SE","SW")){
   var m=new Marker();m.player=player;m.corner=corner;boolean east=corner.equals("NE")||corner.equals("SE"),south=corner.equals("SW")||corner.equals("SE");
   m.transform=new PerceptionTransform(east?b.maxXExclusive()-.25:b.minX()+.25,b.minY()+.8,south?b.maxZExclusive()-.25:b.minZ()+.25,0,0,2);
   m.binding=new NativeWarningBinding(server,player,m.transform,1200,w.profile(),this::now);
   m.intent=new EffectIntent(new OperationToken(UUID.randomUUID()),WARNING,1,w,f.envelope,new EventInstanceId(f.id));f.markers.add(m);
  }
  f.markerDeadline=now().value()+1200;
 }
 private List<NativeDisasterWarningProof.Marker> receiptMarkers(Flight f){
  return f.markers.stream().map(m->new NativeDisasterWarningProof.Marker(m.player,m.corner,warning(f).profile(),m.transform,
    m.binding.emittedHeader().orElseThrow(),m.binding.sentAt().orElseThrow())).toList();
 }
 private boolean coreCommitted(Flight f){
  var state=phases.poll(f.disaster);if(state.status()==DurableDisasterRunner.Status.RECOVERY_REQUIRED)throw new IllegalStateException("disaster phase WAL uncertain");
  return state.status()==DurableDisasterRunner.Status.COMMITTED;
 }
 private DisasterRunner.Conditions conditions(Flight f,boolean safe,boolean complete,boolean stabilized){
  return new DisasterRunner.Conditions(now(),HealthMode.NORMAL,f.audience,f.audienceRevision,safe,complete,stabilized);
 }
 private void stabilize(Flight f){
  var state=phases.view(f.disaster);
  if(state.stage()==DisasterStage.STABILIZE){f.phase=Phase.CLEANUP;return;}
  if(state.stage()==DisasterStage.WATCH){f.phase=Phase.CLEANUP;return;}
  if(state.stage()==DisasterStage.RESOLVED||state.stage()==DisasterStage.ABORTED_SAFE){end(f);return;}
  phases.propose(f.disaster,new TransactionId(UUID.randomUUID()),new UnitValue(1),conditions(f,false,true,false));f.phase=Phase.STABILIZE_ACK;
 }
 private void readMutations(Flight f,boolean afterCleanup){
  f.afterCleanup=afterCleanup;var keys=new HashSet<AggregateKey>();
  for(var id:f.mutations.keySet())keys.add(new AggregateKey(AggregateKind.MUTATION,id.value()));
  if(keys.isEmpty()){join(f);return;}
  f.mutationQuery=new StoreQuery(world,keys,new Sequence(0),Optional.empty(),keys.size());f.mutationRead=store.read(f.mutationQuery).toCompletableFuture();f.phase=Phase.MUTATION_READ;
 }
 private boolean finalizeMutations(Flight f){
  if(!f.mutationRead.isDone())return false;var read=f.mutationRead.getNow(null);
  if(read==null)throw new IllegalStateException("physical mutation read unavailable");
  ReadContracts.validate(read,f.mutationQuery,store.session());
  if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().hasMore())throw new IllegalStateException("physical mutation commit sequence unavailable");
  var rows=new ArrayList<DurableRecord>();int observed=0;
  for(var restored:read.page().orElseThrow().aggregates()){
   observed++;
   if(!(restored.state() instanceof MutationAggregate aggregate))throw new IllegalStateException("foreign physical mutation");
   var old=aggregate.value();if(!old.instanceId().value().equals(f.id)||!f.mutations.containsKey(old.mutationId()))throw new IllegalStateException("wrong physical lineage");
   if(old.durableSeq().value()>0&&old.state()!=MutationState.VERIFIED){f.mutations.put(old.mutationId(),old);continue;}
   var h=old.header();var state=old.state()==MutationState.VERIFIED?MutationState.COMMITTED:old.state();
   var committed=new MutationRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),restored.committedSequence(),now()),
    old.mutationId(),old.instanceId(),old.sliceId(),state,old.primitiveId(),old.primitiveVersion(),old.footprint(),old.preStateRef(),old.postStateRef(),old.ownerToken(),old.ownerEpoch(),old.readSetVersions(),old.noninvertibleEffects(),old.reconciliationEvidence(),old.compensationRef(),restored.committedSequence());
   rows.add(new DurableMutation(committed));f.mutations.put(committed.mutationId(),committed);
  }
  if(observed!=f.mutations.size())throw new IllegalStateException("missing actual physical mutation");
  if(rows.isEmpty()){join(f);return true;}
  f.tx=append(rows);f.phase=Phase.MUTATION_ACK;return true;
 }
 private void join(Flight f){
  var status=f.conflict?"FLOOD_CONFLICT_UNRESOLVED":f.afterCleanup?(f.mutations.isEmpty()?"NO_IMPORT_OWNERSHIP_RELEASED":f.drained?"OWNED_VOLUME_DRAINED_NO_SOURCE_RECREATED":"ORIGINAL_NATIVE_LEVELS_RESTORED"):f.pulsed?"ONE_CONSERVATIVE_PULSE_COMMITTED":f.forwardSucceeded?"NATIVE_RESERVOIR_IMPORTED_NO_PULSE_YET":"NO_PHYSICAL_IMPORT";
  phases.publishPhysicsReferences(f.disaster,new TransactionId(UUID.randomUUID()),f.mutations.values(),f.consequences,status,now());
  f.phase=f.afterCleanup?Phase.CLEAN_JOIN_ACK:Phase.JOIN_ACK;
 }

 private boolean lineage(OwnershipRecord a,OwnershipRecord b){
  return a.header().id().equals(b.header().id())&&a.resourceKey().equals(b.resourceKey())&&a.ownerTokens().equals(b.ownerTokens())
   &&a.epoch().equals(b.epoch())&&a.playerOverrideEpoch().equals(b.playerOverrideEpoch())&&a.creationMutationId().equals(b.creationMutationId());
 }
 private OwnershipRecord released(OwnershipRecord o){
  var h=o.header();return new OwnershipRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),h.lastSequence(),blockNow()),o.resourceKey(),o.ownerTokens(),o.epoch(),
   OwnershipDisposition.RELEASED,o.creationMutationId(),o.expectedPostHash(),o.playerOverrideEpoch(),o.exceptionContractId());
 }
 private MutationRecord mutationState(MutationRecord old,MutationState state){
  var h=old.header();return new MutationRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),h.lastSequence(),now()),
   old.mutationId(),old.instanceId(),old.sliceId(),state,old.primitiveId(),old.primitiveVersion(),old.footprint(),old.preStateRef(),old.postStateRef(),
   old.ownerToken(),old.ownerEpoch(),old.readSetVersions(),old.noninvertibleEffects(),old.reconciliationEvidence(),old.compensationRef(),new Sequence(0));
 }
 private boolean originalPresent(Flight f){
  return f.cells.stream().allMatch(p->hash(f,p).equals(f.forward.preconditions().fingerprints().get(key(p))));
 }
 private void cleanup(Flight f){
  for(var p:f.cells)attached.read(level(f),pos(p)).ifPresent(o->owners.put(key(p),o));
  if(originalPresent(f)){releaseMetadata(f);return;}
  if(f.importedPlan==null||f.importedBlobs==null)throw new IllegalStateException("owned water cleanup requires actual original import PREPARE bytes");
  var binding=provider(f);EffectPreparation captured;
  try{
   f.recipe=ControlledWaterBinding.RESTORE;captured=capture(f,binding);
  }catch(ControlledWaterBinding.RestoreUnavailable|IllegalArgumentException unavailable){
   f.drained=true;f.recipe=ControlledWaterBinding.DRAIN;
   var evidence=fact(PHYSICS,f,Map.of("direction",text("RESTORE_REFUSED_NO_CREDIT_MINTED"),"reason",text(unavailable.getMessage()),"tick",text(now().value())));
   f.consequences.add(evidence);
   try{captured=capture(f,binding);}catch(ControlledWaterBinding.NoPhysicalChange empty){releaseMetadata(f);return;}
  }catch(ControlledWaterBinding.NoPhysicalChange already){
   releaseMetadata(f);return;
  }
  f.work=run(f,captured,binding,false);f.phase=Phase.INVERSE;
 }
 private void releaseMetadata(Flight f){
  if(f.ownerRead==null){
   var keys=new HashSet<AggregateKey>();for(var o:f.initial.values())keys.add(new OwnershipAggregate(o).key());
   f.ownerQuery=new StoreQuery(world,keys,new Sequence(0),Optional.empty(),keys.size());
   f.ownerRead=store.read(f.ownerQuery).toCompletableFuture();f.phase=Phase.OWNER_READ;return;
  }
  if(!f.ownerRead.isDone())return;var read=f.ownerRead.getNow(null);ReadContracts.validate(read,f.ownerQuery,store.session());
  if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().hasMore())throw new IllegalStateException("cleanup durable owner read unavailable");
  var durableOwners=new HashMap<ResourceKey,OwnershipRecord>();
  for(var aggregate:read.page().orElseThrow().aggregates())if(aggregate.state() instanceof OwnershipAggregate o)durableOwners.put(o.value().resourceKey(),o.value());
  if(durableOwners.size()!=f.cells.size())throw new IllegalStateException("cleanup durable owner rows incomplete");
  var rows=new ArrayList<DurableRecord>();f.releaseBefore.clear();f.releaseAfter.clear();f.conflict=false;
  for(var p:f.cells){
   var current=attached.read(level(f),pos(p)).orElseThrow();var initial=f.initial.get(key(p));var actual=hash(f,p);
   var persisted=Objects.requireNonNull(durableOwners.get(key(p)));
   if(!persisted.equals(current)){
    if(!persisted.header().id().equals(current.header().id())||current.header().revision().value()!=persisted.header().revision().value()+1
      ||!current.resourceKey().equals(persisted.resourceKey())||current.epoch().compareTo(persisted.epoch())<0
      ||current.playerOverrideEpoch().compareTo(persisted.playerOverrideEpoch())<0)throw new IllegalStateException("cleanup local/WAL ownership discontinuity");
    rows.add(new DurableOwnership(current)); // Actual local tombstone/post, then optional release, in one ordered batch.
   }
   OwnershipRecord next=current;MutationState target=null;
   if(lineage(current,initial)&&current.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE){
    if(actual.equals(f.forward.preconditions().fingerprints().get(key(p)))){next=released(current);target=MutationState.ROLLED_BACK;}
    else if(f.drained&&level(f).getBlockState(pos(p)).isAir()){next=released(current);target=MutationState.COMMITTED;}
    else{next=OwnershipRules.cede(current,OwnershipRules.ExternalChange.UNKNOWN,blockNow());target=MutationState.CONFLICT_ISOLATED;f.conflict=true;}
   }else if(current.disposition()==OwnershipDisposition.YIELDED_TO_PLAYER)target=MutationState.YIELDED;
   else if(current.disposition()==OwnershipDisposition.CONFLICT_ISOLATED){target=MutationState.CONFLICT_ISOLATED;f.conflict=true;}
   else if(current.disposition()!=OwnershipDisposition.RELEASED){f.conflict=true;target=MutationState.CONFLICT_ISOLATED;}
   if(!next.equals(current)){f.releaseBefore.put(key(p),current);f.releaseAfter.put(key(p),next);rows.add(new DurableOwnership(next));}
   var mutation=f.mutations.get(initial.creationMutationId());
   if(mutation!=null&&target!=null&&mutation.state()!=target){
    var updated=mutationState(mutation,target);f.mutations.put(updated.mutationId(),updated);rows.add(new DurableMutation(updated));
   }
  }
  var observed=fact(PHYSICS,f,Map.of("direction",text("CLEANUP_OBSERVED"),"tick",text(now().value()),"conflict",new JsonValue.Bool(f.conflict),
    "new_volume_minted",new JsonValue.Bool(false),"native_original_present",new JsonValue.Bool(originalPresent(f)),"drained_owned_remaining",new JsonValue.Bool(f.drained),"manual_force",new JsonValue.Bool(true)));
  f.consequences.add(observed);rows.add(new DurableFact(observed));f.tx=append(rows);f.phase=Phase.RELEASE_ACK;
 }
 private void publishReleased(Flight f){
  for(var p:f.cells){var k=key(p);var next=f.releaseAfter.get(k);if(next==null)continue;
   var current=attached.read(level(f),pos(p)).orElseThrow();
   if(current.equals(f.releaseBefore.get(k)))publish(f,p,next);
   else if(!current.equals(next))throw new IllegalStateException("cleanup ownership changed while metadata persisted");
   journalOwners.put(k,next);
  }
 }
 private void end(Flight f){
  var rows=new ArrayList<DurableRecord>();
  for(var p:prepares.values())if(p.reservation().instanceId().value().equals(f.id)&&knownDurablePrepares.contains(p.operationToken())&&!results.containsKey(p.operationToken()))
   rows.add(new DurableResult(new ResultRecord(p.transactionId(),p.operationToken(),new SliceResult(Outcome.DELIVERY_UNCERTAIN,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of()),Set.of(),List.of(),List.of())));
  if(f.pulsed)rows.add(new DurableFact(fact(new FactType("proutlost_director:lab_flood_pulse_observed"),f,Map.of("units",text(1),"initial_native_units",text(f.units),"cleanup",text(f.drained?"OWNED_DRAIN":"RESTORE_NATIVE"),"tick",text(now().value())))));
  rows.add(new DurableFact(fact(END,f,Map.of("disaster",text(f.disaster.value()),"stage",text(phases.view(f.disaster).stage()),"pulse_replayed",new JsonValue.Bool(false),"pulse_committed",new JsonValue.Bool(f.pulsed)))));
  f.tx=append(rows);f.phase=Phase.END_ACK;
 }
 public String cancel(ServerPlayer player){actor();if(!player.hasPermissions(4))return "OP4 requis.";if(flight==null)return "Aucune catastrophe LAB active.";flight.stop=true;return "STABILIZE demande; aucune nouvelle tentative de degats.";}
 public void poll(){
  actor();if(failed)return;
  try{
   if(store.readOnlySafe())throw new IllegalStateException("terrain disaster WAL unavailable; no emergency authority invented");
   if(!scanned){if(!shared)scan();return;}
   if(flight==null){recover();return;}var f=flight;if(!loaded(f))return;
   switch(f.phase){
    case INITIAL->{
     if(!durable(f.tx))return;
     for(var p:f.cells)publish(f,p,f.initial.get(key(p)));
     phases.restore(f.initialState);
     if(f.stop||!playerReady(f)||!exitsReady(f)||!audience(f).equals(f.audience)){f.phase=Phase.CLEANUP;return;}
     phases.propose(f.disaster,new TransactionId(UUID.randomUUID()),new UnitValue(1),conditions(f,true,false,false));f.phase=Phase.WARN_ACK;
    }
    case WARN_ACK->{
     if(!coreCommitted(f))return;
     if(now().compareTo(phases.warnEnteredAt(f.disaster).orElseThrow())<=0)return;
     if(f.stop||!playerReady(f)||!audience(f).equals(f.audience)){stabilize(f);return;}
     if(f.markers.isEmpty()&&f.audience.stream().anyMatch(p->!fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,p,4))){f.stop=true;stabilize(f);return;}
     if(f.markers.isEmpty())buildMarkers(f);f.markerDeadline=now().value()+1200;f.phase=Phase.MARKERS;
    }
    case MARKERS->{
     if(f.markerIndex<f.markers.size()){
      var m=f.markers.get(f.markerIndex);
      if(m.execution==null){
       if(f.stop||!playerReady(f)||!audience(f).equals(f.audience)){stabilize(f);return;}
       EffectPreparation captured;
       try{captured=m.binding.capture(m.intent,snapshot(f));}
       catch(RuntimeException staleCapture){f.stop=true;stabilize(f);return;} // No PREPARE was submitted.
       m.execution=run(f,captured,m.binding,false);return;
      }
      if(!applied(f,m.execution))return;f.markerIndex++;return;
     }
     if(f.stop||!playerReady(f)||!audience(f).equals(f.audience)||now().value()>=f.markerDeadline){stabilize(f);return;}
     if(!markersLive(f))return;
     var captured=NativeDisasterWarningProof.capture(server,phases.view(f.disaster),phases.warnEnteredAt(f.disaster).orElseThrow(),f.audience,f.audienceRevision,LEAD,now(),receiptMarkers(f));
     phases.publishWarning(f.disaster,new TransactionId(UUID.randomUUID()),captured.evidence(),captured.proof(),now());f.phase=Phase.PROOF_ACK;
    }
    case PROOF_ACK->{if(coreCommitted(f))f.phase=Phase.LEAD;}
    case LEAD->{
     if(!contextSafe(f)){stabilize(f);return;}
     var state=phases.view(f.disaster);if(now().value()-state.warningEvidence().deployedTick().value()<LEAD.value())return;
     for(var p:f.cells){var current=attached.read(level(f),pos(p)).orElseThrow();owners.put(key(p),current);
      if(!current.equals(f.initial.get(key(p)))||!hash(f,p).equals(f.forward.preconditions().fingerprints().get(key(p)))){f.stop=true;stabilize(f);return;}
     }
     f.recipe=ControlledWaterBinding.IMPORT;var p=provider(f);EffectPreparation actual;
     try{actual=capture(f,p);}catch(RuntimeException changedBasin){f.stop=true;stabilize(f);return;} // No PREPARE submitted.
     f.preparedImpact=p;f.capturedImpact=actual;f.forward=actual.prepared().orElseThrow();f.importedPlan=f.forward;f.importedBlobs=actual.blobs();
     var sealed=dossier(f);f.tx=append(List.of(new DurableFact(sealed)));starts.put(f.id,sealed);f.phase=Phase.SEAL;
    }
    case SEAL->{
     if(!durable(f.tx))return;if(!contextSafe(f)){stabilize(f);return;}
     phases.propose(f.disaster,new TransactionId(UUID.randomUUID()),new UnitValue(1),conditions(f,true,false,false));f.phase=Phase.IMPACT_ACK;
    }
    case IMPACT_ACK->{
     if(!coreCommitted(f))return;
     if(!contextSafe(f)){stabilize(f);return;}
     var guarded=new Guarded(f,f.preparedImpact);f.work=run(f,f.capturedImpact,guarded,true);f.impact=f.work;f.phase=Phase.IMPACT;
    }
    case IMPACT->{
     if(!applied(f,f.work))return;f.forwardSucceeded=!f.work.aborted;
     if(!f.forwardSucceeded){stabilize(f);return;}
     readMutations(f,false);
     System.out.println("DIRECTOR_FLOOD_DISASTER_REAL_IMPORT_COMMITTED "+f.id+" cells="+f.cells.size()+" units="+f.units);
    }
    case MUTATION_READ->finalizeMutations(f);
    case MUTATION_ACK->{if(durable(f.tx))join(f);}
    case JOIN_ACK->{if(coreCommitted(f)){
     if(twoLayer(f)){
      if(f.appliedAt<0)throw new IllegalStateException("deep import apply tick missing");
      f.expires=Math.addExact(f.appliedAt,400);f.phase=Phase.HOLD;
     }else if(f.pulsed)f.phase=Phase.HOLD;
     else{f.pulseAt=now().value()+10;f.phase=Phase.PULSE_WAIT;}
    }}
    case PULSE_WAIT->{
     if(!contextSafe(f)){stabilize(f);return;}if(now().value()<f.pulseAt)return;
     f.recipe=ControlledWaterBinding.PULSE;var water=provider(f);EffectPreparation captured;
     try{captured=capture(f,water);}catch(RuntimeException changedCredit){f.stop=true;stabilize(f);return;} // Player withdrawal/changed basin is cleanup, not another pulse.
     f.work=run(f,captured,new Guarded(f,water),true);f.phase=Phase.PULSE;
    }
    case PULSE->{
     if(!applied(f,f.work))return;if(f.work.aborted){stabilize(f);return;}
     f.pulsed=true;if(f.appliedAt<0)throw new IllegalStateException("pulse apply tick missing");
     f.expires=Math.addExact(f.appliedAt,400);readMutations(f,false);
     System.out.println("DIRECTOR_FLOOD_DISASTER_ONE_CONSERVATIVE_PULSE_COMMITTED "+f.id+" units=1 total_credit="+f.units);
    }
    case HOLD->{if(!contextSafe(f)||now().value()>=f.expires)stabilize(f);}
    case STABILIZE_ACK->{if(coreCommitted(f))f.phase=Phase.CLEANUP;}
    case CLEANUP->cleanup(f);
    case INVERSE->{if(applied(f,f.work))releaseMetadata(f);}
    case OWNER_READ->releaseMetadata(f);
    case RELEASE_ACK->{if(durable(f.tx)){publishReleased(f);readMutations(f,true);}}
    case CLEAN_JOIN_ACK->{
     if(!coreCommitted(f))return;
     if(f.conflict){f.phase=Phase.CONFLICT;System.out.println("DIRECTOR_FLOOD_DISASTER_CONFLICT_UNRESOLVED "+f.id);return;}
     var stage=phases.view(f.disaster).stage();
     if(stage==DisasterStage.WATCH||stage==DisasterStage.WARN)phases.abortNoImpact(f.disaster,new TransactionId(UUID.randomUUID()),"NO_IMPACT_OWNERSHIP_RELEASED",now());
     else if(stage==DisasterStage.STABILIZE)phases.propose(f.disaster,new TransactionId(UUID.randomUUID()),new UnitValue(1),conditions(f,false,true,true));
     else if(stage==DisasterStage.RESOLVED||stage==DisasterStage.ABORTED_SAFE){end(f);return;}
     else throw new IllegalStateException("cleanup before durable STABILIZE");
     f.phase=Phase.RESOLVE_ACK;
    }
    case RESOLVE_ACK->{if(coreCommitted(f))end(f);}
    case END_ACK->{
     if(!durable(f.tx))return;for(var m:f.markers){m.binding.stop();release(m.execution);}release(f.impact);release(f.work);
     for(var execution:f.held)release(execution);
     phases.retire(f.disaster);starts.remove(f.id);ends.add(f.id);prepares.values().removeIf(p->p.reservation().instanceId().value().equals(f.id));
     System.out.println("DIRECTOR_FLOOD_DISASTER_RESOLVED_DURABLE "+f.id+" "+f.family);flight=null;
    }
    case REPAIR_ACK->{if(durable(f.tx))stabilize(f);}
    case RECOVERY_BLOBS->{
     if(!f.blobRead.isDone())return;var read=f.blobRead.getNow(null);
     if(read==null||read.status()!=AckStatus.DURABLE)throw new IllegalStateException("actual import cleanup bytes unavailable");
     var content=new HashMap<BlobRef,BlobContent>();for(var b:read.contents())content.put(b.reference(),b);
     if(!content.keySet().equals(f.recoveryRefs))throw new IllegalStateException("water cleanup blob reference mismatch");
     f.mask=Objects.requireNonNull(content.get(f.maskRef));
     if(f.importedPlan!=null)f.importedBlobs=new BlobBundle(CommitPermit.Authority.references(f.importedPlan.durableBlobs()).stream().map(r->Objects.requireNonNull(content.get(r))).toList());
     finishRecovery(f);
    }
    case CONFLICT->{/* Real isolated conflict remains visible in STABILIZE. No overwrite or false RESOLVED. */}
   }
  }catch(RuntimeException failure){failed=true;System.out.println("DIRECTOR_FLOOD_DISASTER_RECOVERY_REQUIRED "+failure);failure.printStackTrace();}
 }
 private void scan(){
  if(scanning==null){scanning=store.read(new StoreQuery(world,Set.of(),new Sequence(0),cursor,128)).toCompletableFuture();return;}
  if(!scanning.isDone())return;var read=scanning.getNow(null);scanning=null;
  if(read==null||read.status()==ReadStatus.STALE){resetRecoveryScan();return;}
  if(read.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("terrain disaster scan "+read.status());
  var page=read.page().orElseThrow();acceptRecoveryRows(page.records(),!page.hasMore());cursor=page.nextCursor();
 }
 public void useSharedRecoveryScan(){actor();shared=true;resetRecoveryScan();}
 public void resetRecoveryScan(){
  actor();scanned=false;scanning=null;cursor=Optional.empty();starts.clear();ends.clear();restoredDisasters.clear();warnEntries.clear();journalOwners.clear();journalMutations.clear();
  prepares.clear();results.clear();consequences.clear();knownDurablePrepares.clear();safeAborts.clear();floor=0;clockFloor=0;offset=0;
 }
 public void acceptRecoveryRows(List<DurableRecord> records,boolean last){
  actor();
  for(var row:records){
   if(row instanceof DurableAggregate a){
    clockFloor=Math.max(clockFloor,a.state().header().updatedTick().value());
    if(a.state() instanceof DisasterAggregate d){var old=restoredDisasters.get(d.value().disasterId());if(old==null||old.header().revision().compareTo(d.header().revision())<0)restoredDisasters.put(d.value().disasterId(),d.value());}
    if(a.state() instanceof MutationAggregate m)journalMutations.put(m.value().mutationId(),m.value());
   }
   if(row instanceof DurableMutation m){journalMutations.put(m.value().mutationId(),m.value());clockFloor=Math.max(clockFloor,m.value().header().updatedTick().value());}
   if(row instanceof DurableOwnership o){journalOwners.put(o.value().resourceKey(),o.value());clockFloor=Math.max(clockFloor,o.value().header().updatedTick().value());}
   if(row instanceof DurablePrepare d){var p=d.value();floor=Math.max(floor,p.reservationGeneration().value());prepares.put(p.operationToken(),p);knownDurablePrepares.add(p.operationToken());}
   if(row instanceof DurableResult r)results.put(r.value().operationToken(),r.value());
   if(row instanceof DurableFact d&&d.value().eventId().isPresent()){
    var fact=d.value();var id=fact.eventId().orElseThrow().value();
    if(fact.factType().equals(START))starts.put(id,fact);else if(fact.factType().equals(END))ends.add(id);
    else if(fact.factType().equals(PHYSICS)){var facts=consequences.computeIfAbsent(id,k->new ArrayList<>());if(facts.stream().noneMatch(old->old.factId().equals(fact.factId())))facts.add(fact);}
    else if(fact.factType().equals(DurableDisasterRunner.WARN_ENTRY))warnEntries.put(new DisasterId(UUID.fromString(text(fact,"disaster_id"))),new Tick(Long.parseLong(text(fact,"entered_tick"))));
    if(Set.of(START,PHYSICS).contains(fact.factType())&&fact.payload().get("tick") instanceof JsonValue.Text t)clockFloor=Math.max(clockFloor,Long.parseLong(t.value()));
   }
  }
  if(last){
   for(var id:ends)if(prepares.values().stream().noneMatch(p->p.reservation().instanceId().value().equals(id)&&!results.containsKey(p.operationToken())))starts.remove(id);
   prepares.values().removeIf(p->!starts.containsKey(p.reservation().instanceId().value()));
   knownDurablePrepares.retainAll(prepares.keySet());results.keySet().retainAll(prepares.keySet());
   journalMutations.values().removeIf(m->!starts.containsKey(m.instanceId().value()));consequences.keySet().retainAll(starts.keySet());
   offset=Math.max(0,clockFloor-server.overworld().getGameTime());scanned=true;
   System.out.println("DIRECTOR_FLOOD_DISASTER_RECOVERY_SCAN pending="+starts.size());
  }
 }
 private void recover(){
  if(starts.isEmpty())return;var entry=starts.entrySet().iterator().next();var saved=entry.getValue();var f=new Flight();f.id=entry.getKey();f.player=UUID.fromString(text(saved,"player"));
  f.disaster=new DisasterId(UUID.fromString(text(saved,"disaster")));f.family=DisasterRunner.Family.FLOOD;
  f.sources=positions(saved,"sources");f.destinations=positions(saved,"destinations");var all=new ArrayList<>(f.sources);all.addAll(f.destinations);f.cells=List.copyOf(all);
  f.footprint=FOOTPRINT.decode(decode(saved,"footprint"));f.envelope=FOOTPRINT.decode(decode(saved,"warning_envelope"));f.forward=PLAN.decode(decode(saved,"plan"));f.selected=new HashSet<>(f.cells);
  if(!(f.forward.intent().typedArgs() instanceof ControlledWater imported)||!imported.recipe().equals(ControlledWaterBinding.IMPORT))throw new IllegalStateException("original real water import missing");
  f.maskRef=imported.propagationMask();f.units=imported.transferUnits();
  f.exits=((JsonValue.Array)saved.payload().get("exits")).values().stream().map(v->EXIT.decode(Base64.getDecoder().decode(((JsonValue.Text)v).value()))).toList();
  for(var value:((JsonValue.Array)saved.payload().get("owners")).values()){var owner=OWNER.decode(Base64.getDecoder().decode(((JsonValue.Text)value).value()));f.initial.put(owner.resourceKey(),owner);}
  catalogue(f);if(!f.definition.value().equals(text(saved,"definition"))||!f.forward.intent().instanceId().value().equals(f.id)||!f.forward.intent().expectedFootprint().equals(f.footprint))throw new IllegalStateException("water disaster dossier identity");
  if(!loaded(f))return;
  var state=Objects.requireNonNull(restoredDisasters.get(f.disaster),"flood aggregate missing");
  if(!state.eventInstanceId().value().equals(f.id)||!state.recipeId().equals(f.definition)||!state.envelope().equals(f.envelope)||!state.propagationMaskRef().equals(f.maskRef))throw new IllegalStateException("flood aggregate/dossier mismatch");
  f.audience=state.affectedAudience();f.audienceRevision=state.warningEvidence().audienceRevision();f.recovery=true;f.stop=true;
  for(var m:journalMutations.values())if(m.instanceId().value().equals(f.id))f.mutations.put(m.mutationId(),m);
  f.consequences.addAll(consequences.getOrDefault(f.id,List.of()));
  var pulse=prepares.get(ControlledWaterBinding.pulseToken(new EventInstanceId(f.id)));var pulseResult=pulse==null?null:results.get(pulse.operationToken());
  if(pulseResult!=null){var actual=pulseResult.result();f.pulsed=pulseResult.prepareTransactionId().equals(pulse.transactionId())&&actual.outcome()==Outcome.SUCCEEDED&&actual.remainingWork()==0&&actual.conflictCells().isEmpty()&&actual.appliedOperationIds().equals(Set.of(pulse.operationToken()))&&pulse.durableBlobs().expectedPostStates().entrySet().stream().allMatch(e->e.getValue().hash().equals(actual.observedHashes().get(e.getKey())));}
  var importPrepare=prepares.get(f.forward.intent().operationToken());
  var refs=new HashSet<BlobRef>();refs.add(f.maskRef);
  if(importPrepare!=null){
   if(!importPrepare.durableBlobs().equals(f.forward.durableBlobs())||!importPrepare.readSet().equals(f.forward.preconditions())||!importPrepare.slice().operations().equals(List.of(f.forward.intent())))throw new IllegalStateException("water dossier does not match actual import PREPARE");
   f.importedPlan=f.forward;refs.addAll(CommitPermit.Authority.references(f.forward.durableBlobs()));
  }else if(!originalPresent(f))throw new IllegalStateException("changed basin without actual original import PREPARE");
  var warningEntry=warnEntries.get(f.disaster);if(warningEntry==null)phases.restore(state);else phases.restore(state,warningEntry);
  f.recoveryRefs=Set.copyOf(refs);f.blobRead=store.readBlobs(new BlobReadRequest(world,store.session().storeGeneration(),refs)).toCompletableFuture();
  flight=f;f.phase=Phase.RECOVERY_BLOBS;
 }
 private void finishRecovery(Flight f){
  var repairs=new ArrayList<DurableRecord>();
  for(var p:f.cells){
   var k=key(p);var initial=Objects.requireNonNull(f.initial.get(k));var persisted=Objects.requireNonNull(journalOwners.get(k),"water owner absent from WAL");var actual=hash(f,p);
   var local=attached.read(level(f),pos(p)).orElse(null);
   if(local==null&&persisted.equals(initial)&&actual.equals(f.forward.preconditions().fingerprints().get(k))){local=initial;publish(f,p,local);}
   if(local==null)throw new IllegalStateException("water owner missing in saved world");
   if(!local.equals(persisted)){
    boolean next=local.header().id().equals(persisted.header().id())&&local.resourceKey().equals(k)&&local.ownerTokens().equals(persisted.ownerTokens())&&local.header().revision().value()==persisted.header().revision().value()+1;
    boolean owned=next&&lineage(local,initial)&&local.expectedPostHash().equals(actual)
      &&(DirectorControlledWater.volume(level(f).getBlockState(pos(p)))>=0||actual.equals(f.forward.preconditions().fingerprints().get(k)));
    boolean yielded=next&&Set.of(OwnershipDisposition.YIELDED_TO_PLAYER,OwnershipDisposition.CONFLICT_ISOLATED).contains(local.disposition())&&local.epoch().value()>=persisted.epoch().value()&&local.playerOverrideEpoch().value()>=persisted.playerOverrideEpoch().value();
    if(!owned&&!yielded)throw new IllegalStateException("saved water/WAL ownership mismatch; never mint credit");
    repairs.add(new DurableOwnership(local));
   }
   owners.put(k,local);
   if(actual.equals(f.forward.expectedPost().get(k))&&lineage(local,initial)&&!f.mutations.containsKey(initial.creationMutationId())){
    var prepare=Objects.requireNonNull(prepares.get(f.forward.intent().operationToken()),"actual import PREPARE missing");
    var e=new Execution();e.prepare=prepare;e.plan=f.forward;
    var recovered=physicalMutation(f,e,p,MutationState.APPLIED_UNCONFIRMED);f.mutations.put(recovered.mutationId(),recovered);repairs.add(new DurableMutation(recovered));
   }
  }
  if(repairs.isEmpty())stabilize(f);else{f.tx=append(repairs);f.phase=Phase.REPAIR_ACK;}
  System.out.println("DIRECTOR_FLOOD_DISASTER_RECOVERY_CLEANUP_ONLY "+f.id+" pulse_replay=false");
 }
 public boolean recovering(){actor();return !scanned&&!failed;}
 public boolean busy(){actor();return !scanned||failed||flight!=null||!starts.isEmpty();}
 public String status(){actor();return failed?"FLOOD_DISASTER_RECOVERY_REQUIRED":!scanned?"FLOOD_DISASTER_RECOVERING":flight==null?"FLOOD_DISASTER_IDLE":"FLOOD_DISASTER "+flight.family+" "+flight.phase+" "+flight.id;}
 @Override public void close(){actor();if(flight!=null)for(var m:flight.markers)m.binding.stop();closed=true;}
}
