package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.ownership.*;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Explicit OP4 contained FIRE LAB. Natural wildfire risk is not asserted.
 * Every wave is separately forced; restart may extinguish only, never replay old damage. */
public final class DirectorNativeFireDisasterGameplay implements AutoCloseable {
 private static final PrimitiveId FIRE=ControlledFireWaveBinding.FIRE,CLEAN=ControlledFireWaveBinding.CLEANUP,WARNING=new PrimitiveId("proutlost:warning");
 private static final FactType START=new FactType("proutlost_director:lab_fire_disaster_start"),END=new FactType("proutlost_director:lab_fire_disaster_end");
 private static final FactType WAVE=new FactType("proutlost_director:controlled_fire_wave"),PHYSICS=new FactType("proutlost_director:fire_disaster_physics"),COMPLETED=new FactType(DirectorLabFireDisasterCatalog.COMPLETED.value());
 private static final ModelCodec<OwnershipRecord> OWNER=new ModelCodec<>(OwnershipRecord.class);
 private static final ModelCodec<BlockPosition> POSITION=new ModelCodec<>(BlockPosition.class);
 private static final ModelCodec<Footprint> FOOTPRINT=new ModelCodec<>(Footprint.class);
 private static final ModelCodec<RouteSegment> EXIT=new ModelCodec<>(RouteSegment.class);
 private static final ModelCodec<EventDefinition> DEFINITION=new ModelCodec<>(EventDefinition.class);
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
 private final Map<UUID,Map<FactId,DirectorFact>> facts=new HashMap<>();
 private final Set<UUID> ends=new HashSet<>();
 private final Set<OperationToken> knownDurablePrepares=new HashSet<>();
 private final Map<TransactionId,Tx> safeAborts=new HashMap<>();
 private CompletableFuture<StoreReadResult> scanning;private Optional<String> cursor=Optional.empty();
 private long floor,clockFloor,offset;private boolean scanned,shared,closed,failed;private Flight flight;
 private enum Phase {INITIAL,WARN_ACK,MARKERS,PROOF_ACK,LEAD,IMPACT_ACK,WAVE,MUTATION_READ,MUTATION_ACK,JOIN_ACK,HOLD,
  STABILIZE_ACK,CLEANUP,CLEANUP_ACK,OWNER_READ,RELEASE_ACK,CLEAN_JOIN_ACK,RESOLVE_ACK,END_ACK,RECOVERY_BLOBS,REPAIR_ACK,CONFLICT}
 private static final class Tx {DurableBatch batch;StoreSession session;CompletableFuture<CommitAck> ack;}
 private static final class Execution {
  EventRunner runner;TransactionId tx;PrepareRecord prepare;PreparedEffect plan;BlobBundle blobs;
  LiveValidationSnapshot live;ResourceArbiter arbiter;ReservationToken token;AdmissionService admission;DecisionId decision;Guarded guard;
  boolean retain,released,done,aborted;SliceResult actual,terminalOverride;
 }
 private static final class Marker {
  PlayerId player;String corner;PerceptionTransform transform;NativeWarningBinding binding;EffectIntent intent;Execution execution;
 }
 private static final class Flight {
  UUID id,player;DisasterId disaster;DefinitionId definition;List<BlockPosition> cells;Footprint footprint;List<RouteSegment> exits;BlobContent mask;
  Map<BlockPosition,Long> seed=new HashMap<>();
  Map<ResourceKey,OwnershipRecord> initial=new HashMap<>(),releaseBefore=new HashMap<>(),releaseAfter=new HashMap<>();
  Map<MutationId,MutationRecord> mutations=new LinkedHashMap<>();Map<FactId,DirectorFact> consequences=new LinkedHashMap<>();
  Set<PlayerId> audience;Epoch audienceRevision=new Epoch(1);CatalogSnapshot catalog;ControlledFire args;DamageProfile damage;double probability;
  ControlledFireWaveBinding binding;List<Marker> markers=new ArrayList<>();DisasterInstance initialState;
  Phase phase=Phase.INITIAL;Tx tx;Execution work,impact;boolean stop,recovery,possibleImpact,originSubmitted,afterCleanup,conflict,ignited;
  long markerDeadline,expires,committedCursor,recoveryCursor;int markerIndex;CompletableFuture<StoreReadResult> mutationRead,ownerRead;StoreQuery mutationQuery,ownerQuery;
  ArrayDeque<PrepareRecord> recoveryQueue=new ArrayDeque<>();PrepareRecord recoveryPrepare;CompletableFuture<BlobReadResult> blobRead;
  List<DurableRecord> repairs=new ArrayList<>();
 }
 /** Forward guard distinguishes proof failure before physical invocation from an uncertain partial write. */
 private final class Guarded implements ServerEffectPort.Binding {
  private final Flight f;private final ControlledFireWaveBinding delegate;boolean invoked;
  Guarded(Flight f){this.f=f;delegate=f.binding;}
  public ProviderMode mode(){return delegate.mode();}public Set<PrimitiveId> supportedPrimitives(){return delegate.supportedPrimitives();}
  public Set<CapabilityId> capabilities(){return delegate.capabilities();}
  public EffectPreparation capture(EffectIntent i,PlanningSnapshot p){return delegate.capture(i,p);}
  public boolean revalidate(PreparedEffect p,BlobBundle b){return impactSafe(f)&&delegate.revalidate(p,b);}
  public SliceResult apply(PreparedEffect p,BlobBundle b){if(!revalidate(p,b))throw new IllegalStateException("fire warning/context changed");invoked=true;return delegate.apply(p,b);}
  public ReconcileResult reconcile(RecoveryProbe p){return delegate.reconcile(p);}
 }
 public DirectorNativeFireDisasterGameplay(MinecraftServer server,WorldId world,JournalStore store,
   ServerOwnershipAttachments attached,Map<ResourceKey,OwnershipRecord> owners){
  this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.store=Objects.requireNonNull(store);
  this.attached=Objects.requireNonNull(attached);this.owners=Objects.requireNonNull(owners);
  phases=new DurableDisasterRunner(store,4,d->new ScopeRef(ScopeKind.EVENT,d.disasterId().value()));actor();
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("fire disaster actor");}
 private Tick now(){return new Tick(Math.addExact(server.overworld().getGameTime(),offset));}
 private Tick blockNow(){return new Tick(server.overworld().getGameTime());}
 private static BlockPos pos(BlockPosition p){return new BlockPos(p.x(),p.y(),p.z());}
 private static ResourceKey key(BlockPosition p){return new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z());}
 private ServerLevel level(Flight f){for(var l:server.getAllLevels())if(l.dimension().location().toString().equals(f.footprint.dimension().value()))return l;throw new IllegalStateException("fire dimension unavailable");}
 private boolean loaded(Flight f){var l=level(f);return f.footprint.loadedChunks().stream().allMatch(c->l.getChunkSource().getChunkNow(c.x(),c.z())!=null);}
 private Fingerprint hash(Flight f,BlockPosition p){return BlockTransformBinding.stateBlob(level(f).getBlockState(pos(p))).reference().hash();}
 private void publish(Flight f,BlockPosition p,OwnershipRecord o){owners.put(key(p),o);attached.write(level(f),pos(p),o);}
 private static String encode(byte[] b){return Base64.getEncoder().encodeToString(b);}
 private static String text(DirectorFact f,String k){return ((JsonValue.Text)f.payload().get(k)).value();}
 private static byte[] decode(DirectorFact f,String k){return Base64.getDecoder().decode(text(f,k));}
 private static JsonValue.Text text(Object v){return new JsonValue.Text(v.toString());}
 private static Map<String,JsonValue> object(byte[] b){return ((JsonValue.ObjectValue)CanonicalJsonV1.decode(b)).values();}
 private static String text(Map<String,JsonValue> m,String k){return ((JsonValue.Text)m.get(k)).value();}
 private static List<JsonValue> positions(List<BlockPosition> cells){return cells.stream().map(p->(JsonValue)text(encode(POSITION.encode(p)))).toList();}
 private static List<BlockPosition> positions(DirectorFact f,String k){return ((JsonValue.Array)f.payload().get(k)).values().stream().map(v->POSITION.decode(Base64.getDecoder().decode(((JsonValue.Text)v).value()))).toList();}
 private Tx append(List<DurableRecord> rows,List<BlobContent> blobs){
  var tx=new Tx();tx.session=store.session();tx.batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),tx.session.storeGeneration(),Map.of(),rows,blobs);
  tx.ack=store.append(tx.batch).toCompletableFuture();return tx;
 }
 private Tx append(List<DurableRecord> rows){return append(rows,List.of());}
 private boolean durable(Tx tx){
  if(tx==null||!tx.ack.isDone())return false;var ack=tx.ack.getNow(null);
  if(ack==null||ack.ackStatus()!=AckStatus.DURABLE||!store.session().equals(tx.session)||!ack.transactionId().equals(tx.batch.transactionId())
   ||!ack.transactionHash().equals(tx.batch.transactionHash())||!ack.storeGeneration().equals(tx.session.storeGeneration())||ack.durableSequence().value()<=0
   ||ack.permitAttempt().isPresent()||!ack.forcedBlobs().equals(DurableBlobReferences.collect(tx.batch.records())))throw new IllegalStateException("fire metadata ACK mismatch");
  return true;
 }
 private DirectorFact fact(FactType type,Flight f,Map<String,JsonValue> payload){
  return new DirectorFact(new FactId(UUID.randomUUID()),type,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),Optional.of(new EventInstanceId(f.id)),EvidenceStatus.CONFIRMED,payload,true,Optional.empty());
 }
 private EventDefinition definition(Flight f){return Objects.requireNonNull(f.catalog.events().get(f.definition));}
 private Warning warning(Flight f){return (Warning)definition(f).steps().stream().filter(s->s.args() instanceof Warning).findFirst().orElseThrow().args();}
 private void catalogue(Flight f){
  var c=new DirectorLabFireDisasterCatalog(DirectorLabObservationProfile.load(),f.exits,f.mask.reference());
  f.catalog=c.snapshot();f.definition=c.definitionId();f.damage=c.damageProfile();f.probability=c.ignitionProbability();
  f.args=(ControlledFire)definition(f).steps().stream().filter(s->s.args() instanceof ControlledFire).findFirst().orElseThrow().args();
  if(!f.args.propagationMask().equals(f.mask.reference())||f.args.energy().value()!=400)throw new IllegalStateException("exact bounded fire catalogue required");
 }
 private OperationToken originOperation(Flight f){return new OperationToken(UUID.nameUUIDFromBytes(("fire-origin-operation/"+f.id).getBytes(StandardCharsets.UTF_8)));}
 private MutationId engineId(Flight f){return new MutationId(UUID.nameUUIDFromBytes(("fire-engine/"+f.id).getBytes(StandardCharsets.UTF_8)));}
 private MutationId cellId(Flight f,BlockPosition p){return new MutationId(UUID.nameUUIDFromBytes(("fire-cell/"+f.id+"/"+p.dimension().value()+"/"+p.x()+","+p.y()+","+p.z()).getBytes(StandardCharsets.UTF_8)));}
 private DirectorFact dossier(Flight f){
  var p=new TreeMap<String,JsonValue>();p.put("player",text(f.player));p.put("disaster",text(f.disaster.value()));p.put("definition",text(f.definition.value()));
  p.put("definition_hash",text(BlobContent.hash(DEFINITION.encode(definition(f))).value()));p.put("cells",new JsonValue.Array(positions(f.cells)));
  p.put("footprint",text(encode(FOOTPRINT.encode(f.footprint))));
  p.put("owners",new JsonValue.Array(f.cells.stream().map(c->(JsonValue)text(encode(OWNER.encode(f.initial.get(key(c)))))).toList()));
  p.put("exits",new JsonValue.Array(f.exits.stream().map(e->(JsonValue)text(encode(EXIT.encode(e)))).toList()));
  p.put("tick",text(now().value()));return fact(START,f,p);
 }
 private Set<PlayerId> audience(Flight f){
  var found=new HashSet<PlayerId>();var operator=server.getPlayerList().getPlayer(f.player);
  if(operator!=null&&operator.isAlive()&&operator.serverLevel()==level(f))found.add(new PlayerId(f.player));
  var b=f.footprint.bounds().getFirst();var aabb=new AABB(b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive());
  for(var p:server.getPlayerList().getPlayers())if(p.isAlive()&&p.serverLevel()==level(f)&&p.getBoundingBox().intersects(aabb))found.add(new PlayerId(p.getUUID()));
  return Set.copyOf(found);
 }
 private boolean playerReady(Flight f){var p=server.getPlayerList().getPlayer(f.player);var c=f.cells.getFirst();return p!=null&&p.isAlive()&&p.hasPermissions(4)&&p.serverLevel()==level(f)&&p.distanceToSqr(c.x()+.5,c.y(),c.z()+.5)<=256;}
 private boolean exitsReady(Flight f){
  var l=level(f);for(var r:f.exits){int dx=Integer.compare(r.to().x()-r.from().x(),0),dz=Integer.compare(r.to().z()-r.from().z(),0);
   for(int i=0;i<=2;i++){var p=new BlockPos(r.from().x()+dx*i,r.from().y(),r.from().z()+dz*i);
    if(l.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)==null||!l.getBlockState(p.below()).isSolidRender(l,p.below())||!l.getFluidState(p).isEmpty()
     ||!l.noCollision(new AABB(p.getX()+.2,p.getY(),p.getZ()+.2,p.getX()+.8,p.getY()+1.8,p.getZ()+.8)))return false;
   }
  }return true;
 }
 private boolean markersLive(Flight f){return !f.markers.isEmpty()&&f.markers.stream().allMatch(m->m.binding.receivedAndLive());}
 private boolean contextSafe(Flight f){return !f.stop&&loaded(f)&&playerReady(f)&&exitsReady(f)&&audience(f).equals(f.audience)&&markersLive(f);}
 private boolean impactSafe(Flight f){return !f.recovery&&!store.readOnlySafe()&&phases.view(f.disaster).stage()==DisasterStage.IMPACT&&contextSafe(f)&&(f.expires==0||now().value()<f.expires);}
 private void bindFire(Flight f){
  var original=new HashMap<BlockPosition,OwnershipRecord>();f.cells.forEach(p->original.put(p,f.initial.get(key(p))));
  f.binding=new ControlledFireWaveBinding(server,world,new EventInstanceId(f.id),f.args.recipe(),f.mask,f.seed,
   DirectorControlledFireBlocks.CONTROLLED_FIRE::get,()->new OwnershipView(owners),(p,o)->publish(f,p,o),this::now,
   f.damage,f.audience,()->impactSafe(f),f.probability,original);
 }
 public String begin(ServerPlayer player){
  actor();if(!scanned||failed||store.readOnlySafe()||flight!=null||!starts.isEmpty())return "FIRE LAB en attente/recuperation.";
  if(!player.hasPermissions(4)||!player.isAlive())return "OP4 vivant requis.";
  var layout=NativeFireDisasterLayout.survey(player);var f=new Flight();f.id=UUID.randomUUID();f.player=player.getUUID();f.disaster=new DisasterId(UUID.randomUUID());
  f.cells=layout.cells();f.footprint=layout.footprint();f.exits=layout.exits();f.mask=BlobContent.capture(ControlledFireWaveBinding.MASK_CODEC,1,FOOTPRINT.encode(f.footprint));catalogue(f);
  f.audience=audience(f);if(f.audience.isEmpty()||f.audience.size()>4)throw new IllegalArgumentException("one to four actual affected clients required");
  Epoch epoch=new Epoch(1);var oldRows=new HashMap<BlockPosition,OwnershipRecord>();
  for(var p:f.cells){var old=attached.read(level(f),pos(p)).orElse(null);if(old!=null&&old.disposition()!=OwnershipDisposition.RELEASED)throw new IllegalArgumentException("active/player fire cell provenance");if(old!=null){oldRows.put(p,old);if(old.epoch().compareTo(epoch)>=0)epoch=old.epoch().plus(1);}}
  var token=new OwnerToken(UUID.randomUUID());
  for(var p:f.cells){var old=oldRows.get(p);f.initial.put(key(p),new OwnershipRecord(new AggregateHeader(1,old==null?UUID.randomUUID():old.header().id(),old==null?new Revision(1):old.header().revision().plus(1),new Sequence(0),blockNow()),
   key(p),Set.of(token),epoch,OwnershipDisposition.DIRECTOR_ACTIVE,cellId(f,p),hash(f,p),old==null?new Epoch(0):old.playerOverrideEpoch(),Optional.empty()));f.seed.put(p,p.equals(f.cells.getFirst())?400L:0L);}
  var empty=new WarningEvidence(f.audience,now(),LEAD,BlobContent.hash(new byte[0]),f.audienceRevision,false,false);
  f.initialState=new DisasterInstance(new AggregateHeader(1,f.disaster.value(),new Revision(1),new Sequence(0),now()),f.disaster,new EventInstanceId(f.id),f.definition,DisasterStage.WATCH,
   Map.of(new ResourceId("proutlost_director:manual_lab_request"),new UnitValue(1)),new UnitValue(1),f.footprint,empty,f.audience,f.exits,f.mask.reference(),Set.of(),"WATCH_NO_FIRE",Set.of());
  for(var recipient:f.audience)if(!fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,recipient,4))
   throw new SelectedEventStart.ContextUnavailable("warning tranche session capacity/handshake pending");
  buildMarkers(f);var start=dossier(f);var rows=new ArrayList<DurableRecord>();f.initial.values().forEach(o->rows.add(new DurableOwnership(o)));rows.add(new DurableFact(start));rows.add(new DurableAggregate(new DisasterAggregate(f.initialState)));
  f.tx=append(rows,List.of(f.mask));starts.put(f.id,start);flight=f;
  return "FIRE LAB manuel : WARN reel 4 coins/20s, feu physique dirige borne, extinction proprietaire. Aucun risque naturel revendique.";
 }
 private PlanningSnapshot snapshot(Flight f){
  var cells=new HashMap<BlockPosition,TerrainCellSnapshot>();for(var p:f.cells){var o=Objects.requireNonNull(owners.get(key(p)));cells.put(p,new TerrainCellSnapshot(p,hash(f,p),BlockTransformBinding.STATE_CODEC,Set.of(),Set.of(),false,true,o.epoch(),o.playerOverrideEpoch()));}
  if(!loaded(f))throw new IllegalStateException("fire envelope unloaded");
  var terrain=new TerrainSnapshot(f.footprint,cells,List.of(),Map.of(),new Revision(1));
  var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(terrain),Map.of());
  return new PlanningSnapshot(new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),now(),Map.of(),context,Set.of(),f.footprint.loadedChunks(),HealthMode.NORMAL),
   new OwnershipView(owners),new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));
 }
 private ResourceArbiter arbiter(){return new ResourceArbiter(world,new Epoch(Math.max(floor,store.session().bootEpoch().value())),1000,4,new ResourceArbiter.Compatibility(){
  public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId r,ResourceClaim a,ResourceClaim b){return false;}
 });}
 private Execution run(Flight f,EffectPreparation captured,ServerEffectPort.Binding binding,boolean impact){
  var p=captured.prepared().orElseThrow();var i=p.intent();var e=new Execution();e.plan=p;e.blobs=captured.blobs();e.retain=impact&&f.impact==null;e.arbiter=arbiter();
  if(binding instanceof Guarded guard)e.guard=guard;
  var scope=new ScopeRef(ScopeKind.WORLD,world.value());var epochs=new HashMap<ResourceKey,Epoch>();p.claims().forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
  if(impact){
   var def=definition(f);var roles=new RoleAssignments(Map.of("witness",List.of(new PlayerId(f.player))));
   var ref=new DefinitionRef(def.id(),def.contentRevision(),BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(def)));
   var intents=new ArrayList<EffectIntent>();f.markers.forEach(m->intents.add(m.intent));intents.add(i);
   var execution=new ExecutionPlan(new CandidateId(UUID.randomUUID()),ref,intents,roles,new ScopeSet(world,Set.of(scope,new ScopeRef(ScopeKind.PLAYER,f.player))),p.preconditions());
   var request=new AdmissionRequest(new DecisionId(UUID.randomUUID()),p.preconditions(),p.claims(),roles,execution);
   var validator=new AdmissionService.HardValidator(){
    public ProviderMode mode(){return ProviderMode.TEST_ONLY;}
    public AdmissionService.Assessment assess(AdmissionRequest proposed,Tick tick){
     boolean valid=proposed.equals(request)&&phases.view(f.disaster).stage()==DisasterStage.IMPACT&&contextSafe(f)
       &&i.typedArgs().equals(f.args)&&i.expectedFootprint().cells().equals(new HashSet<>(f.cells))&&binding.revalidate(p,captured.blobs());
     return new AdmissionService.Assessment(new ValidationResult(valid,valid?List.of():List.of(ReasonCode.STALE_CONTEXT),p.preconditions()),p.claims(),
       new ResourceArbiter.Rank(false,false,false,Priority.DANGER,0,1,42),Map.of(scope,def.budget()));
    }
   };
   e.admission=new AdmissionService(()->f.catalog,validator,e.arbiter,this::now,new DurationTicks(1200),4,ProviderMode.TEST_ONLY);e.decision=request.proposalId();
   if(e.admission.admit(request).status()!=AdmissionStatus.DEFERRED)throw new IllegalStateException("manual disaster J03 queue rejected");
   var answer=e.admission.resolveWindow(epochs,Map.of(scope,CAP)).get(e.decision);
   if(answer==null||answer.status()!=AdmissionStatus.ACCEPTED)throw new IllegalStateException("manual disaster J03 denied "+answer);
   e.token=answer.token().orElseThrow();
   System.out.println("DIRECTOR_FIRE_DISASTER_J02_J03_MANUAL_ACCEPTED "+f.id+" automatic_risk=false");
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
    impact?Set.of(NoninvertibleEffect.DAMAGE):Set.of(),List.of(),p.durableBlobs());
  e.prepare=prepare;e.tx=prepare.transactionId();prepares.put(i.operationToken(),prepare);e.runner=new EventRunner(authority,store,port,e.arbiter,4);
  var batch=new DurableBatch(e.tx,store.session().storeGeneration(),prepare.readSet().revisions(),List.of(new DurablePrepare(prepare)),captured.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
  e.runner.submit(batch,p.expectedPost(),e.live,actual->{
   e.actual=actual;var rows=new ArrayList<DurableRecord>();rows.add(new DurableResult(new ResultRecord(e.tx,i.operationToken(),actual,Set.of(),List.of(),List.of())));
   if(i.typedArgs() instanceof ControlledFire){
    for(var row:f.binding.resultRecords()){
     rows.add(row);if(row instanceof DurableFact fact){f.consequences.put(fact.value().factId(),fact.value());if(fact.value().factType().equals(WAVE)){f.committedCursor=Math.max(f.committedCursor,Long.parseLong(text(fact.value(),"burn_cursor")));if(!((JsonValue.Bool)fact.value().payload().get("cleanup")).value()&&Long.parseLong(text(fact.value(),"native_block_writes"))>0)f.ignited=true;}}
    }
    for(var mutation:waveMutations(f,e.prepare,captured.blobs(),MutationState.VERIFIED)){
     f.mutations.put(mutation.mutationId(),mutation);rows.add(new DurableMutation(mutation));
    }
   }
   return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,List.of());
  });return e;
 }
 private BlobRef engineState(EffectBlobs refs,BlobBundle blobs,String stage){
  var matches=refs.snapshots().stream().filter(r->r.codecId().equals(ControlledFireWaveBinding.STATE_CODEC))
   .filter(r->text(object(blobs.contents().stream().filter(b->b.reference().equals(r)).findFirst().orElseThrow().bytes()),"stage").equals(stage)).toList();
  if(matches.size()!=1)throw new IllegalStateException("exact fire engine "+stage+" state required");return matches.getFirst();
 }
 private MutationRecord mutation(Flight f,PrepareRecord p,MutationId id,Footprint footprint,BlobRef pre,BlobRef post,MutationState state,boolean engine){
  var old=f.mutations.get(id);var revision=old==null?new Revision(1):old.header().revision().plus(1);var effects=new HashSet<NoninvertibleEffect>();
  if(engine){effects.addAll(p.noninvertibleEffects());if(old!=null)effects.addAll(old.noninvertibleEffects());}
  var original=f.initial.get(key(f.cells.getFirst()));
  return new MutationRecord(new AggregateHeader(1,id.value(),revision,old==null?new Sequence(0):old.header().lastSequence(),now()),id,new EventInstanceId(f.id),
   p.slice().sliceId(),state,p.slice().operations().getFirst().primitive(),1,footprint,engine||old==null?pre:old.preStateRef(),post,original.ownerTokens().iterator().next(),original.epoch(),p.readSet(),
   effects,List.of(),Optional.empty(),new Sequence(0));
 }
 private List<MutationRecord> waveMutations(Flight f,PrepareRecord p,BlobBundle blobs,MutationState state){
  var refs=p.durableBlobs();var records=new ArrayList<MutationRecord>();
  records.add(mutation(f,p,engineId(f),p.slice().footprint(),engineState(refs,blobs,"before"),engineState(refs,blobs,"after"),state,true));
  for(var cell:f.cells){
   var pre=refs.preStates().get(key(cell));var post=refs.expectedPostStates().get(key(cell));if(pre==null||post==null||pre.hash().equals(post.hash()))continue;
   var one=new Footprint(cell.dimension(),Set.of(new ChunkRef(cell.dimension(),cell.x()>>4,cell.z()>>4)),Set.of(cell),List.of());
   records.add(mutation(f,p,f.initial.get(key(cell)).creationMutationId(),one,pre,post,p.slice().operations().getFirst().primitive().equals(CLEAN)&&state==MutationState.VERIFIED?MutationState.ROLLED_BACK:state,false));
  }
  return List.copyOf(records);
 }
 private void release(Execution e){
  if(e==null||e.released)return;e.arbiter.release(e.token);if(e.admission!=null)e.admission.released(e.decision,e.token);e.released=true;
 }
 private boolean applied(Flight f,Execution e){
  if(e.done)return true;
  if(e.aborted){if(!durable(safeAborts.get(e.tx)))return false;results.put(e.prepare.operationToken(),new ResultRecord(e.tx,e.prepare.operationToken(),e.terminalOverride,Set.of(),List.of(),List.of()));e.done=true;if(e.plan.intent().typedArgs() instanceof ControlledFire)f.binding.rejectedBeforeApply(e.prepare.operationToken());release(e);return true;}
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
  if(e.plan.intent().typedArgs() instanceof ControlledFire)f.binding.committed(e.prepare.operationToken());
  e.done=true;results.put(e.prepare.operationToken(),new ResultRecord(e.tx,e.prepare.operationToken(),Objects.requireNonNull(e.actual),Set.of(),List.of(),List.of()));
  if(!e.retain)release(e);return true;
 }
 private void buildMarkers(Flight f){
  var b=f.footprint.bounds().getFirst();var w=warning(f);
  for(var player:f.audience)for(var corner:List.of("NW","NE","SE","SW")){
   var m=new Marker();m.player=player;m.corner=corner;boolean east=corner.equals("NE")||corner.equals("SE"),south=corner.equals("SW")||corner.equals("SE");
   m.transform=new PerceptionTransform(east?b.maxXExclusive()-.25:b.minX()+.25,b.minY()+.8,south?b.maxZExclusive()-.25:b.minZ()+.25,0,0,2);
   m.binding=new NativeWarningBinding(server,player,m.transform,1200,w.profile(),this::now);
   m.intent=new EffectIntent(new OperationToken(UUID.randomUUID()),WARNING,1,w,f.footprint,new EventInstanceId(f.id));f.markers.add(m);
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
  var rows=new ArrayList<DurableRecord>();
  for(var restored:read.page().orElseThrow().aggregates()){
   if(!(restored.state() instanceof MutationAggregate aggregate))throw new IllegalStateException("foreign physical mutation");
   var old=aggregate.value();if(!old.instanceId().value().equals(f.id)||!f.mutations.containsKey(old.mutationId()))throw new IllegalStateException("wrong physical lineage");
   if(old.durableSeq().value()>0){f.mutations.put(old.mutationId(),old);continue;}
   var h=old.header();var state=old.state()==MutationState.VERIFIED?MutationState.COMMITTED:old.state();
   var committed=new MutationRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),restored.committedSequence(),now()),
    old.mutationId(),old.instanceId(),old.sliceId(),state,old.primitiveId(),old.primitiveVersion(),old.footprint(),old.preStateRef(),old.postStateRef(),old.ownerToken(),old.ownerEpoch(),old.readSetVersions(),old.noninvertibleEffects(),old.reconciliationEvidence(),old.compensationRef(),restored.committedSequence());
   rows.add(new DurableMutation(committed));f.mutations.put(committed.mutationId(),committed);
  }
  if(read.page().orElseThrow().aggregates().size()!=f.mutations.size())throw new IllegalStateException("missing actual fire mutation");
  if(rows.isEmpty()){join(f);return true;}
  f.tx=append(rows);f.phase=Phase.MUTATION_ACK;return true;
 }
 private void join(Flight f){
  var status=f.conflict?"CONFLICT_ISOLATED_UNRESOLVED":f.afterCleanup?"OWNED_FIRE_EXTINGUISHED_DAMAGE_NOT_REVERSED":"FIRE_WAVE_PHYSICALLY_COMMITTED";
  phases.publishPhysicsReferences(f.disaster,new TransactionId(UUID.randomUUID()),f.mutations.values(),List.copyOf(f.consequences.values()),status,now());
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
 private boolean startWave(Flight f,boolean cleanup){
  var operation=!cleanup&&!f.originSubmitted?originOperation(f):new OperationToken(UUID.randomUUID());
  var intent=new EffectIntent(operation,cleanup?CLEAN:FIRE,1,f.args,f.footprint,new EventInstanceId(f.id));
  var captured=f.binding.capture(intent,snapshot(f));
  if(!cleanup&&!f.possibleImpact){
   var fireHash=BlockTransformBinding.stateBlob(DirectorControlledFireBlocks.CONTROLLED_FIRE.get().defaultBlockState()).reference().hash();
   if(captured.prepared().orElseThrow().expectedPost().values().stream().noneMatch(fireHash::equals)){f.binding.rejectedBeforeApply(operation);return false;}
  }
  var binding=cleanup?(ServerEffectPort.Binding)f.binding:new Guarded(f);
  f.work=run(f,captured,binding,!cleanup);
  if(!cleanup){f.originSubmitted=true;f.possibleImpact=true;if(f.impact==null)f.impact=f.work;}
  f.phase=cleanup?Phase.CLEANUP_ACK:Phase.WAVE;return true;
 }
 private void cleanup(Flight f){
  if(!f.possibleImpact){releaseMetadata(f);return;}
  boolean eligible=false;
  for(var p:f.cells){var current=attached.read(level(f),pos(p)).orElseThrow();owners.put(key(p),current);
   if(lineage(current,f.initial.get(key(p)))&&current.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE){
    var state=level(f).getBlockState(pos(p));if(state.is(Blocks.AIR)||state.is(DirectorControlledFireBlocks.CONTROLLED_FIRE.get()))eligible=true;
   }
  }
  if(!eligible){releaseMetadata(f);return;}
  startWave(f,true);
 }
 /** State-only reconciliation of an old actual cell mutation, not a new physical slice. */
 private MutationRecord observedCellState(MutationRecord old,MutationState state,OwnershipRecord owner,Fingerprint actual){
  var h=old.header();var evidence=new ArrayList<>(old.reconciliationEvidence());
  evidence.add(new RecoveryEvidence(owner.resourceKey(),EvidenceStatus.CONFIRMED,Optional.of(actual),
   owner.ownerTokens().size()==1?Optional.of(owner.ownerTokens().iterator().next()):Optional.empty(),owner.epoch(),owner.playerOverrideEpoch(),false));
  return new MutationRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),h.lastSequence(),now()),old.mutationId(),old.instanceId(),old.sliceId(),
   state,old.primitiveId(),old.primitiveVersion(),old.footprint(),old.preStateRef(),old.postStateRef(),old.ownerToken(),old.ownerEpoch(),old.readSetVersions(),old.noninvertibleEffects(),
   evidence,old.compensationRef(),new Sequence(0));
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
   OwnershipRecord next=current;
   if(lineage(current,initial)&&current.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE){
    if(level(f).getBlockState(pos(p)).is(Blocks.AIR)){next=released(current);}
    else{next=OwnershipRules.cede(current,OwnershipRules.ExternalChange.UNKNOWN,blockNow());f.conflict=true;}
   }else if(current.disposition()==OwnershipDisposition.CONFLICT_ISOLATED)f.conflict=true;
   else if(current.disposition()!=OwnershipDisposition.RELEASED&&current.disposition()!=OwnershipDisposition.YIELDED_TO_PLAYER)f.conflict=true;
   if(!next.equals(current)){f.releaseBefore.put(key(p),current);f.releaseAfter.put(key(p),next);rows.add(new DurableOwnership(next));}
   var mutation=f.mutations.get(initial.creationMutationId());MutationState target=null;
   if(mutation!=null){
    if(next.disposition()==OwnershipDisposition.YIELDED_TO_PLAYER)target=MutationState.YIELDED;
    else if(next.disposition()==OwnershipDisposition.CONFLICT_ISOLATED)target=MutationState.CONFLICT_ISOLATED;
    else if(lineage(next,initial)&&actual.equals(mutation.preStateRef().hash()))target=MutationState.ROLLED_BACK;
    if(target!=null&&mutation.state()!=target){var reconciled=observedCellState(mutation,target,next,actual);f.mutations.put(reconciled.mutationId(),reconciled);rows.add(new DurableMutation(reconciled));}
   }
  }
  var observed=fact(PHYSICS,f,Map.of("direction",text("CLEANUP_OBSERVED"),"tick",text(now().value()),"conflict",new JsonValue.Bool(f.conflict),
    "damage_reversed",new JsonValue.Bool(false),"manual_risk",new JsonValue.Bool(true)));
  f.consequences.put(observed.factId(),observed);rows.add(new DurableFact(observed));
  if(!f.conflict&&f.ignited&&f.consequences.values().stream().noneMatch(x->x.factType().equals(COMPLETED))){
   var complete=fact(COMPLETED,f,Map.of("manual_lab",new JsonValue.Bool(true),"physical_fire_observed",new JsonValue.Bool(true),
    "owned_fire_remaining",new JsonValue.Bool(false),"damage_reversed",new JsonValue.Bool(false),"recovery_cleanup",new JsonValue.Bool(f.recovery),"tick",text(now().value())));
   f.consequences.put(complete.factId(),complete);rows.add(new DurableFact(complete));
  }
  f.tx=append(rows);f.phase=Phase.RELEASE_ACK;
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
  rows.add(new DurableFact(fact(END,f,Map.of("disaster",text(f.disaster.value()),"stage",text(phases.view(f.disaster).stage()),"damage_replayed",new JsonValue.Bool(false)))));
  f.tx=append(rows);f.phase=Phase.END_ACK;
 }
 public String cancel(ServerPlayer player){actor();if(!player.hasPermissions(4))return "OP4 requis.";if(flight==null)return "Aucun FIRE LAB actif.";flight.stop=true;return "STABILIZE FIRE demande, aucune nouvelle blessure.";}
 public void poll(){
  actor();if(failed)return;
  try{
   if(store.readOnlySafe())throw new IllegalStateException("fire WAL unavailable; native damage/propagation remain disabled");
   if(!scanned){if(!shared)scan();return;}if(flight==null){recover();return;}
   var f=flight;if(!loaded(f))return;
   switch(f.phase){
    case INITIAL->{
     if(!durable(f.tx))return;for(var p:f.cells)publish(f,p,f.initial.get(key(p)));phases.restore(f.initialState);bindFire(f);
     if(f.stop||!playerReady(f)||!exitsReady(f)||!audience(f).equals(f.audience)){f.phase=Phase.CLEANUP;return;}
     phases.propose(f.disaster,new TransactionId(UUID.randomUUID()),new UnitValue(1),conditions(f,true,false,false));f.phase=Phase.WARN_ACK;
    }
    case WARN_ACK->{
     if(!coreCommitted(f)||now().compareTo(phases.warnEnteredAt(f.disaster).orElseThrow())<=0)return;
     if(f.stop||!playerReady(f)||!audience(f).equals(f.audience)){stabilize(f);return;}
     f.markerDeadline=now().value()+1200;f.phase=Phase.MARKERS;
    }
    case MARKERS->{
     if(f.markerIndex<f.markers.size()){
      var m=f.markers.get(f.markerIndex);
      if(m.execution==null){
       if(f.stop||!playerReady(f)||!audience(f).equals(f.audience)){stabilize(f);return;}
       EffectPreparation captured;
       try{captured=m.binding.capture(m.intent,snapshot(f));}
       catch(RuntimeException staleCapture){f.stop=true;stabilize(f);return;}
       m.execution=run(f,captured,m.binding,false);return;
      }
      if(!applied(f,m.execution))return;f.markerIndex++;return;
     }
     if(f.stop||!playerReady(f)||!audience(f).equals(f.audience)||now().value()>=f.markerDeadline){stabilize(f);return;}
     if(!markersLive(f))return;
     var proof=NativeDisasterWarningProof.capture(server,phases.view(f.disaster),phases.warnEnteredAt(f.disaster).orElseThrow(),f.audience,f.audienceRevision,LEAD,now(),receiptMarkers(f));
     phases.publishWarning(f.disaster,new TransactionId(UUID.randomUUID()),proof.evidence(),proof.proof(),now());f.phase=Phase.PROOF_ACK;
    }
    case PROOF_ACK->{if(coreCommitted(f))f.phase=Phase.LEAD;}
    case LEAD->{
     if(!contextSafe(f)){stabilize(f);return;}
     if(now().value()-phases.view(f.disaster).warningEvidence().deployedTick().value()<LEAD.value())return;
     for(var p:f.cells){var current=attached.read(level(f),pos(p)).orElseThrow();owners.put(key(p),current);
      if(!current.equals(f.initial.get(key(p)))||!level(f).getBlockState(pos(p)).is(Blocks.AIR)){f.stop=true;stabilize(f);return;}
     }
     phases.propose(f.disaster,new TransactionId(UUID.randomUUID()),new UnitValue(1),conditions(f,true,false,false));f.phase=Phase.IMPACT_ACK;
    }
    case IMPACT_ACK->{
     if(!coreCommitted(f))return;if(!contextSafe(f)){stabilize(f);return;}
     f.expires=Math.addExact(now().value(),400);
     if(!startWave(f,false)){f.stop=true;stabilize(f);}
    }
    case WAVE->{
     if(!applied(f,f.work))return;
     if(f.work.aborted){stabilize(f);return;}
     readMutations(f,false);
     System.out.println("DIRECTOR_FIRE_DISASTER_WAVE_COMMITTED event="+f.id+" cursor="+f.committedCursor+" mutation_refs="+f.mutations.size());
    }
    case MUTATION_READ->finalizeMutations(f);
    case MUTATION_ACK->{if(durable(f.tx))join(f);}
    case JOIN_ACK->{if(coreCommitted(f))f.phase=Phase.HOLD;}
    case HOLD->{
     if(!contextSafe(f)||f.binding.requiresWarningRefresh()||now().value()>=f.expires||f.binding.extinguished()){stabilize(f);return;}
     if(f.binding.needsWave()&&!startWave(f,false)){f.stop=true;stabilize(f);}
    }
    case STABILIZE_ACK->{if(coreCommitted(f))f.phase=Phase.CLEANUP;}
    case CLEANUP->cleanup(f);
    case CLEANUP_ACK->{if(applied(f,f.work))releaseMetadata(f);}
    case OWNER_READ->releaseMetadata(f);
    case RELEASE_ACK->{if(durable(f.tx)){publishReleased(f);readMutations(f,true);}}
    case CLEAN_JOIN_ACK->{
     if(!coreCommitted(f))return;
     if(f.conflict){f.phase=Phase.CONFLICT;System.out.println("DIRECTOR_FIRE_DISASTER_CONFLICT_UNRESOLVED "+f.id);return;}
     var stage=phases.view(f.disaster).stage();
     if(stage==DisasterStage.WATCH||stage==DisasterStage.WARN)phases.abortNoImpact(f.disaster,new TransactionId(UUID.randomUUID()),"NO_FIRE_OWNERSHIP_RELEASED",now());
     else if(stage==DisasterStage.STABILIZE)phases.propose(f.disaster,new TransactionId(UUID.randomUUID()),new UnitValue(1),conditions(f,false,true,true));
     else if(stage==DisasterStage.RESOLVED||stage==DisasterStage.ABORTED_SAFE){end(f);return;}
     else throw new IllegalStateException("fire cleanup before durable STABILIZE");f.phase=Phase.RESOLVE_ACK;
    }
    case RESOLVE_ACK->{if(coreCommitted(f))end(f);}
    case END_ACK->{
     if(!durable(f.tx))return;for(var m:f.markers){m.binding.stop();release(m.execution);}release(f.impact);release(f.work);f.binding.close();
     phases.retire(f.disaster);starts.remove(f.id);ends.add(f.id);prepares.values().removeIf(p->p.reservation().instanceId().value().equals(f.id));
     System.out.println("DIRECTOR_FIRE_DISASTER_RESOLVED_DURABLE "+f.id);flight=null;
    }
    case RECOVERY_BLOBS->recoverBudgets(f);
    case REPAIR_ACK->{if(durable(f.tx))stabilize(f);}
    case CONFLICT->{/* Actual external blocks/tombstones remain isolated, never overwritten. */}
   }
  }catch(RuntimeException failure){failed=true;System.out.println("DIRECTOR_FIRE_DISASTER_RECOVERY_REQUIRED "+failure);failure.printStackTrace();}
 }
 private void scan(){
  if(scanning==null){scanning=store.read(new StoreQuery(world,Set.of(),new Sequence(0),cursor,128)).toCompletableFuture();return;}
  if(!scanning.isDone())return;var read=scanning.getNow(null);scanning=null;
  if(read==null||read.status()==ReadStatus.STALE){resetRecoveryScan();return;}
  if(read.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("fire recovery scan "+read.status());
  var page=read.page().orElseThrow();acceptRecoveryRows(page.records(),!page.hasMore());cursor=page.nextCursor();
 }
 public void useSharedRecoveryScan(){actor();shared=true;resetRecoveryScan();}
 public void resetRecoveryScan(){
  actor();if(flight!=null)throw new IllegalStateException("active fire during recovery scan");
  scanned=false;scanning=null;cursor=Optional.empty();starts.clear();ends.clear();restoredDisasters.clear();warnEntries.clear();journalOwners.clear();journalMutations.clear();
  prepares.clear();results.clear();facts.clear();knownDurablePrepares.clear();safeAborts.clear();floor=0;clockFloor=0;offset=0;
 }
 public void acceptRecoveryRows(List<DurableRecord> records,boolean last){
  actor();for(var row:records){
   if(row instanceof DurableAggregate a){
    clockFloor=Math.max(clockFloor,a.state().header().updatedTick().value());
    if(a.state() instanceof DisasterAggregate d){var old=restoredDisasters.get(d.value().disasterId());if(old==null||old.header().revision().compareTo(d.header().revision())<0)restoredDisasters.put(d.value().disasterId(),d.value());}
    if(a.state() instanceof MutationAggregate m)journalMutations.put(m.value().mutationId(),m.value());
   }
   if(row instanceof DurableMutation m){journalMutations.put(m.value().mutationId(),m.value());clockFloor=Math.max(clockFloor,m.value().header().updatedTick().value());}
   if(row instanceof DurableOwnership o)journalOwners.put(o.value().resourceKey(),o.value());
   if(row instanceof DurablePrepare d){var p=d.value();floor=Math.max(floor,p.reservationGeneration().value());prepares.put(p.operationToken(),p);knownDurablePrepares.add(p.operationToken());}
   if(row instanceof DurableResult r)results.put(r.value().operationToken(),r.value());
   if(row instanceof DurableFact d&&d.value().eventId().isPresent()){
    var fact=d.value();var id=fact.eventId().orElseThrow().value();
    if(fact.factType().equals(START))starts.put(id,fact);else if(fact.factType().equals(END))ends.add(id);
    else if(fact.factType().equals(WAVE)||fact.factType().equals(PHYSICS)||fact.factType().equals(COMPLETED))facts.computeIfAbsent(id,k->new LinkedHashMap<>()).put(fact.factId(),fact);
    else if(fact.factType().equals(DurableDisasterRunner.WARN_ENTRY))warnEntries.put(new DisasterId(UUID.fromString(text(fact,"disaster_id"))),new Tick(Long.parseLong(text(fact,"entered_tick"))));
    if(fact.payload().get("tick") instanceof JsonValue.Text tick)clockFloor=Math.max(clockFloor,Long.parseLong(tick.value()));
   }
  }
  if(last){
   for(var id:ends)if(prepares.values().stream().noneMatch(p->p.reservation().instanceId().value().equals(id)&&!results.containsKey(p.operationToken())))starts.remove(id);
   offset=Math.max(0,clockFloor-server.overworld().getGameTime());scanned=true;
   System.out.println("DIRECTOR_FIRE_DISASTER_RECOVERY_SCAN pending="+starts.size());
  }
 }
 private boolean nativeState(Flight f,BlockPosition p){var state=level(f).getBlockState(pos(p));return state.is(Blocks.AIR)||state.is(DirectorControlledFireBlocks.CONTROLLED_FIRE.get());}
 private void recover(){
  if(starts.isEmpty())return;var entry=starts.entrySet().iterator().next();var saved=entry.getValue();var f=new Flight();f.id=entry.getKey();f.player=UUID.fromString(text(saved,"player"));
  f.disaster=new DisasterId(UUID.fromString(text(saved,"disaster")));f.cells=positions(saved,"cells");f.footprint=FOOTPRINT.decode(decode(saved,"footprint"));
  f.mask=BlobContent.capture(ControlledFireWaveBinding.MASK_CODEC,1,FOOTPRINT.encode(f.footprint));
  f.exits=((JsonValue.Array)saved.payload().get("exits")).values().stream().map(v->EXIT.decode(Base64.getDecoder().decode(((JsonValue.Text)v).value()))).toList();
  for(var value:((JsonValue.Array)saved.payload().get("owners")).values()){var o=OWNER.decode(Base64.getDecoder().decode(((JsonValue.Text)value).value()));f.initial.put(o.resourceKey(),o);}
  catalogue(f);if(!f.definition.value().equals(text(saved,"definition"))||!BlobContent.hash(DEFINITION.encode(definition(f))).value().equals(text(saved,"definition_hash")))throw new IllegalStateException("fire dossier/catalogue identity changed");
  if(!loaded(f))return;var state=Objects.requireNonNull(restoredDisasters.get(f.disaster),"fire disaster aggregate missing");
  if(!state.eventInstanceId().value().equals(f.id)||!state.recipeId().equals(f.definition)||!state.envelope().equals(f.footprint)||!state.propagationMaskRef().equals(f.mask.reference()))throw new IllegalStateException("fire aggregate/dossier mismatch");
  f.audience=state.affectedAudience();f.audienceRevision=state.warningEvidence().audienceRevision();f.recovery=true;f.stop=true;
  for(var m:journalMutations.values())if(m.instanceId().value().equals(f.id))f.mutations.put(m.mutationId(),m);
  f.consequences.putAll(facts.getOrDefault(f.id,Map.of()));
  f.ignited=f.consequences.values().stream().anyMatch(x->x.factType().equals(WAVE)&&!((JsonValue.Bool)x.payload().get("cleanup")).value()&&Long.parseLong(text(x,"native_block_writes"))>0);
  for(var p:f.cells){
   f.seed.put(p,p.equals(f.cells.getFirst())?400L:0L);var k=key(p);var initial=Objects.requireNonNull(f.initial.get(k));
   var persisted=Objects.requireNonNull(journalOwners.get(k),"fire WAL owner missing");var local=attached.read(level(f),pos(p)).orElse(null);var actual=hash(f,p);
   if(local==null&&persisted.equals(initial)&&level(f).getBlockState(pos(p)).is(Blocks.AIR)){local=initial;publish(f,p,local);}
   if(local==null)throw new IllegalStateException("fire ownership missing in saved world");
   if(!local.equals(persisted)){
    boolean next=local.header().id().equals(persisted.header().id())&&local.resourceKey().equals(k)&&local.ownerTokens().equals(persisted.ownerTokens())&&local.header().revision().value()==persisted.header().revision().value()+1;
    boolean owned=next&&lineage(local,initial)&&local.expectedPostHash().equals(actual)&&nativeState(f,p);
    boolean yielded=next&&Set.of(OwnershipDisposition.YIELDED_TO_PLAYER,OwnershipDisposition.CONFLICT_ISOLATED).contains(local.disposition())&&local.epoch().compareTo(persisted.epoch())>=0&&local.playerOverrideEpoch().compareTo(persisted.playerOverrideEpoch())>=0;
    if(!owned&&!yielded)throw new IllegalStateException("WAL/world fire owner mismatch; never overwrite");
    f.repairs.add(new DurableOwnership(local));
   }
   owners.put(k,local);
  }
  var entryTick=warnEntries.get(f.disaster);if(entryTick==null)phases.restore(state);else phases.restore(state,entryTick);
  bindFire(f);
  var latest=f.consequences.values().stream().filter(x->x.factType().equals(WAVE)).max(Comparator.comparingLong(x->Long.parseLong(text(x,"burn_cursor")))).orElse(null);
  if(latest!=null){f.committedCursor=Long.parseLong(text(latest,"burn_cursor"));f.binding.restoreCommittedState(decode(latest,"state_base64"),decode(latest,"hit_ledger_base64"));}
  f.recoveryCursor=f.committedCursor;
  for(var p:prepares.values())if(p.reservation().instanceId().value().equals(f.id)&&p.slice().operations().size()==1){
   var primitive=p.slice().operations().getFirst().primitive();if(primitive.equals(FIRE)||primitive.equals(CLEAN)){f.recoveryQueue.add(p);if(primitive.equals(FIRE)){f.originSubmitted=true;f.possibleImpact=true;}}
  }
  flight=f;f.phase=Phase.RECOVERY_BLOBS;
  System.out.println("DIRECTOR_FIRE_DISASTER_RECOVERY_CLEANUP_ONLY "+f.id+" damage_replay=false");
 }
 private boolean postObserved(Flight f,ResourceKey k,BlobRef before,BlobRef after){
  if(before.hash().equals(after.hash()))return false;
  var p=f.cells.stream().filter(c->key(c).equals(k)).findFirst().orElseThrow();var local=owners.get(k);
  return local!=null&&lineage(local,f.initial.get(k))&&local.expectedPostHash().equals(after.hash())&&hash(f,p).equals(after.hash());
 }
 private void recoverBudgets(Flight f){
  if(f.recoveryPrepare==null){
   if(f.recoveryQueue.isEmpty()){
    if(f.repairs.isEmpty())stabilize(f);else{f.tx=append(List.copyOf(f.repairs));f.phase=Phase.REPAIR_ACK;}return;
   }
   f.recoveryPrepare=f.recoveryQueue.removeFirst();var refs=DurableBlobReferences.collect(f.recoveryPrepare);
   f.blobRead=store.readBlobs(new BlobReadRequest(world,store.session().storeGeneration(),refs)).toCompletableFuture();return;
  }
  if(!f.blobRead.isDone())return;var read=f.blobRead.getNow(null);var p=f.recoveryPrepare;
  if(read==null||read.status()!=AckStatus.DURABLE||!read.errors().isEmpty())throw new IllegalStateException("original fire PREPARE blobs unavailable");
  var blobs=new BlobBundle(read.contents());f.binding.recoverCleanupBudget(p,blobs);
  var after=engineState(p.durableBlobs(),blobs,"after");
  var content=blobs.contents().stream().filter(b->b.reference().equals(after)).findFirst().orElseThrow();
  var budget=object(content.bytes());long burn=Long.parseLong(text(budget,"cursor"));
  clockFloor=Math.max(clockFloor,Long.parseLong(text(budget,"tick")));offset=Math.max(offset,Math.max(0,clockFloor-server.overworld().getGameTime()));
  var result=results.get(p.operationToken());boolean provenNoApply=result!=null&&result.result().outcome()==Outcome.ABORTED_SAFE;
  if(burn>f.recoveryCursor&&!provenNoApply){
   boolean observed=p.durableBlobs().preStates().entrySet().stream().anyMatch(e->postObserved(f,e.getKey(),e.getValue(),p.durableBlobs().expectedPostStates().get(e.getKey())));
   var classification=observed?MutationState.APPLIED_UNCONFIRMED:MutationState.PREPARED_DURABLE;
   if(observed&&p.slice().operations().getFirst().primitive().equals(FIRE))f.ignited=true;
   for(var row:waveMutations(f,p,blobs,classification)){
    MutationRecord actual=row;
    if(!row.mutationId().equals(engineId(f))){
     var resource=row.footprint().cells().iterator().next();boolean cellPost=postObserved(f,key(resource),p.durableBlobs().preStates().get(key(resource)),p.durableBlobs().expectedPostStates().get(key(resource)));
     if(!cellPost&&classification!=MutationState.PREPARED_DURABLE)actual=mutation(f,p,row.mutationId(),row.footprint(),row.preStateRef(),row.postStateRef(),MutationState.PREPARED_DURABLE,false);
    }
    f.mutations.put(actual.mutationId(),actual);f.repairs.add(new DurableMutation(actual));
   }
   var observedFact=fact(PHYSICS,f,Map.of("direction",text("RECOVERY_OBSERVED"),"operation",text(p.operationToken().value()),"tick",text(now().value()),
    "classification",text(classification),"damage_replayed",new JsonValue.Bool(false)));
   f.consequences.put(observedFact.factId(),observedFact);f.repairs.add(new DurableFact(observedFact));f.recoveryCursor=burn;
  }
  f.recoveryPrepare=null;f.blobRead=null;
 }
 public boolean recovering(){actor();return !scanned&&!failed;}
 public boolean busy(){actor();return !scanned||failed||flight!=null||!starts.isEmpty();}
 public String status(){actor();return failed?"FIRE_DISASTER_RECOVERY_REQUIRED":!scanned?"FIRE_DISASTER_RECOVERING":flight==null?"FIRE_DISASTER_IDLE":"FIRE_DISASTER "+flight.phase+" "+flight.id;}
 @Override public void close(){actor();if(flight!=null){for(var m:flight.markers)m.binding.stop();if(flight.binding!=null)flight.binding.close();}closed=true;}
}
