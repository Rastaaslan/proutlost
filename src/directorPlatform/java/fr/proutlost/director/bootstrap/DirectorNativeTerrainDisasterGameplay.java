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
public final class DirectorNativeTerrainDisasterGameplay implements AutoCloseable {
 private final DirectorRuntimeContent content;
 private static final PrimitiveId PHYSICAL=new PrimitiveId("proutlost:landslide"),WARNING=new PrimitiveId("proutlost:warning");
 private static final CapabilityId TERRAIN=new CapabilityId("mutation.terrain_v1");
 private static final FactType START=new FactType("proutlost_director:lab_terrain_disaster_start"),END=new FactType("proutlost_director:lab_terrain_disaster_end");
 private static final FactType PHYSICS=new FactType("proutlost_director:terrain_disaster_physics");
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
 private final Map<UUID,EventInstance> recoveredCatalogueHeads=new HashMap<>();
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
   MUTATION_READ,MUTATION_ACK,JOIN_ACK,HOLD,STABILIZE_ACK,CLEANUP,INVERSE,
   OWNER_READ,RELEASE_ACK,CLEAN_JOIN_ACK,RESOLVE_ACK,END_ACK,REPAIR_ACK,CONFLICT }
 private static final class Tx {DurableBatch batch;StoreSession session;CompletableFuture<CommitAck> ack;}
 private static final class Execution {
  EventRunner runner;TransactionId tx;PrepareRecord prepare;PreparedEffect plan;
  LiveValidationSnapshot live;ResourceArbiter arbiter;ReservationToken token;
  AdmissionService admission;DecisionId decision;Guarded guard;
  boolean retain,released,done,aborted;SliceResult actual,terminalOverride;DecisionInput selectedInput;EventRunner.Stage lastStage=EventRunner.Stage.PREPARING;
 }
 private static final class Marker {
  PlayerId player;String corner;PerceptionTransform transform;NativeWarningBinding binding;
  EffectIntent intent;Execution execution;
 }
 private static final class Flight {
  UUID id,player;DisasterId disaster;DisasterRunner.Family family;DefinitionId definition;
  SelectedEventStart selection;ExecutionPlan selectedPlan;DecisionId selectedDecision;EventDefinition selectedDefinition;
  Optional<ChainId> parentChain=Optional.empty();
  List<BlockPosition> sources,destinations,cells;Footprint footprint;List<RouteSegment> exits;
  Map<ResourceKey,OwnershipRecord> initial=new HashMap<>(),releaseBefore=new HashMap<>(),releaseAfter=new HashMap<>();
  Map<MutationId,MutationRecord> mutations=new LinkedHashMap<>();
  Set<BlockPosition> selected;Set<PlayerId> audience;Epoch audienceRevision=new Epoch(1);
  CatalogSnapshot catalog;PreparedEffect forward;List<Marker> markers=new ArrayList<>();
  List<DirectorFact> consequences=new ArrayList<>();DisasterInstance initialState;
  Phase phase=Phase.INITIAL;Tx tx;Execution work,impact;boolean stop,recovery,forwardSucceeded,afterCleanup,conflict;
  LandslideBinding preparedImpact;EffectPreparation capturedImpact;
  long markerDeadline,expires,appliedAt=-1;int markerIndex;CompletableFuture<StoreReadResult> mutationRead,ownerRead;StoreQuery mutationQuery,ownerQuery;
 }
 /** Guard is checked again inside the ServerEffectPort's synchronous physical boundary. */
 private final class Guarded implements ServerEffectPort.Binding {
  private final Flight flight;private final LandslideBinding delegate;boolean invoked;
  Guarded(Flight flight,LandslideBinding delegate){this.flight=flight;this.delegate=delegate;}
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
 public DirectorNativeTerrainDisasterGameplay(MinecraftServer server,WorldId world,JournalStore store,
     ServerOwnershipAttachments attached,Map<ResourceKey,OwnershipRecord> owners){
  this.content=null;
  this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.store=Objects.requireNonNull(store);
  this.attached=Objects.requireNonNull(attached);this.owners=Objects.requireNonNull(owners);
  phases=new DurableDisasterRunner(store,4,d->new ScopeRef(ScopeKind.EVENT,d.disasterId().value()));actor();
 }
 public DirectorNativeTerrainDisasterGameplay(MinecraftServer server,WorldId world,JournalStore store,
     ServerOwnershipAttachments attached,Map<ResourceKey,OwnershipRecord> owners,DirectorRuntimeContent content){
  this.content=Objects.requireNonNull(content);
  this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.store=Objects.requireNonNull(store);
  this.attached=Objects.requireNonNull(attached);this.owners=Objects.requireNonNull(owners);
  phases=new DurableDisasterRunner(store,4,d->new ScopeRef(ScopeKind.EVENT,d.disasterId().value()));actor();
 }
 private ProviderMode providerMode(){return content==null?ProviderMode.TEST_ONLY:content.mode();}
 private void requireLaboratory(){if(content!=null)content.requireLaboratory();}
 private void requireSelected(SelectedEventStart selected){
  Objects.requireNonNull(selected);
  if(selected.mode()!=providerMode()||(content!=null&&!selected.definition().equals(content.definition(selected.definition().id()))))
   throw new IllegalArgumentException("selected runtime content/mode mismatch");
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("terrain disaster actor");}
 private Tick now(){return new Tick(content!=null&&content.production()?fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server):Math.addExact(server.overworld().getGameTime(),offset));}
 // Production ownership shares the actor clock with its hooks; LAB keeps Minecraft gameTime.
 private Tick blockNow(){return content!=null&&content.production()?now():new Tick(server.overworld().getGameTime());}
 private static BlockPos pos(BlockPosition p){return new BlockPos(p.x(),p.y(),p.z());}
 private static ResourceKey key(BlockPosition p){return new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z());}
 private ServerLevel level(Flight f){for(var l:server.getAllLevels())if(l.dimension().location().toString().equals(f.footprint.dimension().value()))return l;throw new IllegalStateException("disaster dimension unavailable");}
 private boolean loaded(Flight f){var l=level(f);return f.footprint.loadedChunks().stream().allMatch(c->l.getChunkSource().getChunkNow(c.x(),c.z())!=null);}
 private Fingerprint hash(Flight f,BlockPosition p){return BlockTransformBinding.stateBlob(level(f).getBlockState(pos(p))).reference().hash();}
 private void publish(Flight f,BlockPosition p,OwnershipRecord o){owners.put(key(p),o);attached.write(level(f),pos(p),o);}
 private static String encode(byte[] b){return Base64.getEncoder().encodeToString(b);}
 private static String text(DirectorFact f,String k){return ((JsonValue.Text)f.payload().get(k)).value();}
 private static byte[] decode(DirectorFact f,String k){return NativeDossierJson.decode(f.payload().get(k));}
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
  values.put("footprint",text(encode(FOOTPRINT.encode(f.footprint))));
  values.put("owners",new JsonValue.Array(f.cells.stream().map(p->(JsonValue)text(encode(OWNER.encode(f.initial.get(key(p)))))).toList()));
  values.put("exits",new JsonValue.Array(f.exits.stream().map(e->(JsonValue)text(encode(EXIT.encode(e)))).toList()));
  if(f.selectedPlan!=null){
   values.put("selected_plan",NativeDossierJson.encode(new ModelCodec<>(ExecutionPlan.class).encode(f.selectedPlan)));
   values.put("selected_decision",text(f.selectedDecision.value()));
   values.put("selected_definition",NativeDossierJson.encode(new ModelCodec<>(EventDefinition.class).encode(f.selectedDefinition)));
   f.parentChain.ifPresent(id->values.put("parent_chain",text(id.value())));
  }
  values.put("tick",text(now().value()));return fact(START,f,values);
 }
 private EventDefinition definition(Flight f){return f.selectedDefinition!=null?f.selectedDefinition:Objects.requireNonNull(f.catalog.events().get(f.definition));}
 private Warning warning(Flight f){return (Warning)definition(f).steps().stream().filter(s->s.args() instanceof Warning).findFirst().orElseThrow().args();}
 private Landslide impact(Flight f){return (Landslide)definition(f).steps().stream().filter(s->s.args() instanceof Landslide).findFirst().orElseThrow().args();}
 private void catalogue(Flight f){
  if(content!=null&&content.production()){
   if(!content.acceptsHistoricalDefinition(f.selectedDefinition))
    throw new IllegalStateException("historical TEST_ONLY disaster requires laboratory recovery");
   f.catalog=content.catalog();f.definition=f.selectedDefinition.id();return;
  }
  var c=new DirectorLabTerrainDisasterCatalog(DirectorLabObservationProfile.load(),f.family,f.exits);f.catalog=c.snapshot();f.definition=c.definitionId();
 }
 private Set<PlayerId> audience(Flight f){
  var set=new HashSet<PlayerId>();var operator=server.getPlayerList().getPlayer(f.player);
  if(operator!=null&&operator.isAlive()&&operator.serverLevel()==level(f))set.add(new PlayerId(f.player));
  if(f.selectedPlan!=null)for(var id:f.selectedPlan.roles().roles().values().stream().flatMap(Collection::stream).distinct().toList()){
   var player=server.getPlayerList().getPlayer(id.value());if(player!=null&&player.isAlive()&&player.serverLevel()==level(f))set.add(id);
  }
  var b=f.footprint.bounds().getFirst();var box=new AABB(b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive());
  for(var p:server.getPlayerList().getPlayers())if(p.isAlive()&&p.serverLevel()==level(f)&&p.getBoundingBox().intersects(box))set.add(new PlayerId(p.getUUID()));
  return Set.copyOf(set);
 }
 private boolean playerReady(Flight f){
  var p=server.getPlayerList().getPlayer(f.player);
  if(p==null||!p.isAlive()||(f.selectedPlan==null&&!p.hasPermissions(4))||p.serverLevel()!=level(f)
      ||p.distanceToSqr(f.cells.getFirst().x()+.5,f.cells.getFirst().y(),f.cells.getFirst().z()+.5)>256)return false;
  if(f.selectedPlan!=null)for(var id:f.selectedPlan.roles().roles().values().stream().flatMap(Collection::stream).distinct().toList()){
   var player=server.getPlayerList().getPlayer(id.value());
   if(player==null||!player.isAlive()||player.serverLevel()!=level(f))return false;
  }
  return true;
 }
 private UnitValue threshold(Flight f){
  return f.selectedPlan!=null&&definition(f).eligibility() instanceof RiskMinimum risk?risk.minimum():new UnitValue(1);
 }
 private boolean selectedControl(Flight f){
  if(f.selectedPlan==null)return true;
  var session=store.session();var query=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.WORLD,world.value())),
      new Sequence(0),Optional.empty(),1);
  var read=store.view(query);ReadContracts.validate(read,query,session);
  if(read.status()!=ReadStatus.AVAILABLE||!session.equals(store.session()))return false;
  return read.page().orElseThrow().aggregates().stream().anyMatch(a->a.state() instanceof WorldAggregate w
      &&w.value().runControl()==RunControl.RUNNING&&w.value().healthMode()==HealthMode.NORMAL);
 }
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
 private boolean contextSafe(Flight f){return !f.stop&&selectedControl(f)&&loaded(f)&&playerReady(f)&&exitsReady(f)&&audience(f).equals(f.audience)&&markersLive(f);}
 public String begin(ServerPlayer player,DisasterRunner.Family family){requireLaboratory();
  actor();if(!scanned||failed||store.readOnlySafe()||flight!=null||!starts.isEmpty())return "Catastrophe LAB en attente/recuperation.";
  if(!player.hasPermissions(4)||!player.isAlive())return "OP4 vivant requis.";
  var layout=NativeTerrainDisasterLayout.survey(player,family);var f=new Flight();f.id=UUID.randomUUID();f.player=player.getUUID();f.disaster=new DisasterId(UUID.randomUUID());
  f.family=family;f.sources=layout.sources();f.destinations=layout.destinations();f.cells=layout.cells();f.footprint=layout.footprint();f.exits=layout.exits();f.selected=new HashSet<>(f.cells);
  catalogue(f);f.audience=audience(f);if(f.audience.isEmpty()||f.audience.size()>4)throw new IllegalArgumentException("LAB supports one to four actual affected clients");
  for(var p:f.cells){
   var old=attached.read(level(f),pos(p)).orElse(null);if(old!=null&&old.disposition()!=OwnershipDisposition.RELEASED)throw new IllegalArgumentException("active/player provenance in disaster cells");
   f.initial.put(key(p),new OwnershipRecord(new AggregateHeader(1,old==null?UUID.randomUUID():old.header().id(),old==null?new Revision(1):old.header().revision().plus(1),new Sequence(0),blockNow()),
     key(p),Set.of(new OwnerToken(UUID.randomUUID())),old==null?new Epoch(1):old.epoch().plus(1),OwnershipDisposition.DIRECTOR_ACTIVE,
     new MutationId(UUID.randomUUID()),hash(f,p),old==null?new Epoch(0):old.playerOverrideEpoch(),Optional.empty()));
  }
  var b=provider(f,false);var captured=capture(f,b);f.forward=captured.prepared().orElseThrow();
  var mask=BlobContent.capture(new ResourceId("proutlost_director:terrain_mask_v1"),1,FOOTPRINT.encode(f.footprint));
  var unreadable=new WarningEvidence(f.audience,now(),LEAD,BlobContent.hash(new byte[0]),f.audienceRevision,false,false);
  f.initialState=new DisasterInstance(new AggregateHeader(1,f.disaster.value(),new Revision(1),new Sequence(0),now()),f.disaster,new EventInstanceId(f.id),f.definition,DisasterStage.WATCH,
    Map.of(new ResourceId("proutlost_director:manual_lab_request"),new UnitValue(1)),new UnitValue(1),f.footprint,unreadable,f.audience,f.exits,mask.reference(),Set.of(),"WATCH_NO_PHYSICAL_EFFECT",Set.of());
  for(var recipient:f.audience)if(!fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,recipient,4))
   throw new SelectedEventStart.ContextUnavailable("warning tranche session capacity/handshake pending");
  buildMarkers(f); // Current real renderer/session preflight; no send occurs in WATCH.
  var start=dossier(f);var rows=new ArrayList<DurableRecord>();f.initial.values().forEach(o->rows.add(new DurableOwnership(o)));
  rows.add(new DurableFact(start));rows.add(new DurableAggregate(new DisasterAggregate(f.initialState)));
  f.tx=append(rows,List.of(mask));starts.put(f.id,start);flight=f;
  return "Catastrophe LAB manuelle "+family+" : perimetre J06 reel, WARN20s, impact, cleanup20s. Aucune eligibilite naturelle revendiquee.";
 }
 public DecisionInput enrich(DecisionInput input){actor();return NativeLandslideRiskObservation.enrich(server,input,providerMode());}
 public NativeTerrainDisasterLayout.Layout preview(Candidate candidate,DecisionInput input){
  actor();return NativeSelectedLandslideLayout.preview(server,candidate,input);
 }
 public boolean selectedAvailable(){actor();return scanned&&!failed&&!store.readOnlySafe()&&flight==null&&starts.isEmpty();}
 public Optional<UUID> activeEvent(){actor();return flight==null?Optional.empty():Optional.of(flight.id);}
 public String beginSelected(SelectedEventStart selected){requireSelected(selected);
  actor();Objects.requireNonNull(selected);
  if(!scanned||failed||store.readOnlySafe()||flight!=null||!starts.isEmpty())throw new IllegalStateException("terrain selected lane unavailable");
  var current=selected.refresh();var plan=selected.plan();
  if(!plan.scopes().worldId().equals(world)||!plan.definition().id().equals(new DefinitionId("proutlost_director:candidate/controlled_landslide"))
      ||plan.effects().size()!=2||selected.region().isEmpty())
   throw new IllegalArgumentException("exact regional landslide selection required");
  var layout=NativeSelectedLandslideLayout.selected(server,plan);
  var participants=selected.participants();if(participants.isEmpty()||participants.size()>6)throw new IllegalArgumentException("one to six actual selected witnesses required");
  var warning=plan.effects().stream().filter(i->i.primitive().equals(WARNING)).findFirst().orElseThrow();
  var impact=plan.effects().stream().filter(i->i.primitive().equals(PHYSICAL)).findFirst().orElseThrow();
  if(!warning.expectedFootprint().equals(layout.footprint())||!impact.expectedFootprint().equals(layout.footprint()))
   throw new IllegalArgumentException("selected warning/impact footprint differs");
  var region=current.context().context().regions().get(selected.region().orElseThrow());
  var risk=region==null?null:region.risks().get(new ResourceId("proutlost:landslide"));
  if(risk==null)throw new IllegalArgumentException("actual regional risk unknown");
  var f=new Flight();f.id=selected.eventId().value();f.player=plan.roles().roles().get("witness").getFirst().value();
  if(starts.containsKey(f.id)||ends.contains(f.id))throw new IllegalArgumentException("selected terrain event already consumed");
  f.disaster=new DisasterId(UUID.randomUUID());f.family=DisasterRunner.Family.LANDSLIDE;
  f.sources=layout.sources();f.destinations=layout.destinations();f.cells=layout.cells();f.footprint=layout.footprint();
  f.exits=layout.exits();f.selected=new HashSet<>(f.cells);f.selection=selected;f.selectedPlan=plan;
  f.selectedDecision=selected.proposal().proposalId();f.parentChain=selected.parentChain();f.selectedDefinition=selected.definition();
  f.catalog=current.catalog();f.definition=plan.definition().id();f.audience=audience(f);
  if(!f.audience.equals(participants))throw new IllegalArgumentException("selected roles must cover the whole actual affected audience");
  for(var p:f.cells){
   var old=attached.read(level(f),pos(p)).orElse(null);
   if(old!=null&&old.disposition()!=OwnershipDisposition.RELEASED)throw new IllegalArgumentException("selected terrain provenance unavailable");
   f.initial.put(key(p),new OwnershipRecord(new AggregateHeader(1,old==null?UUID.randomUUID():old.header().id(),
       old==null?new Revision(1):old.header().revision().plus(1),new Sequence(0),blockNow()),
       key(p),Set.of(new OwnerToken(UUID.randomUUID())),old==null?new Epoch(1):old.epoch().plus(1),
       OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),hash(f,p),
       old==null?new Epoch(0):old.playerOverrideEpoch(),Optional.empty()));
  }
  f.forward=capture(f,provider(f,false)).prepared().orElseThrow();
  var mask=BlobContent.capture(new ResourceId("proutlost_director:terrain_mask_v1"),1,FOOTPRINT.encode(f.footprint));
  var unreadable=new WarningEvidence(f.audience,now(),LEAD,BlobContent.hash(new byte[0]),f.audienceRevision,false,false);
  f.initialState=new DisasterInstance(new AggregateHeader(1,f.disaster.value(),new Revision(1),new Sequence(0),now()),
      f.disaster,new EventInstanceId(f.id),f.definition,DisasterStage.WATCH,providerMode()==ProviderMode.PRODUCTION?NativeLandslideRiskObservation.capturedFactors(current,region.region().regionId()).orElseThrow(()->new SelectedEventStart.ContextUnavailable("exact normalized WATCH factors unavailable")):region.risks(),risk,f.footprint,
      unreadable,f.audience,f.exits,mask.reference(),Set.of(),"WATCH_NO_PHYSICAL_EFFECT",Set.of());
  for(var recipient:f.audience)if(!fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,recipient,4))
   throw new SelectedEventStart.ContextUnavailable("warning tranche session capacity/handshake pending");
  buildMarkers(f);var start=dossier(f);var rows=new ArrayList<DurableRecord>();
  f.initial.values().forEach(owner->rows.add(new DurableOwnership(owner)));rows.add(new DurableFact(start));
  rows.add(new DurableAggregate(new DisasterAggregate(f.initialState)));f.tx=append(rows,List.of(mask));
  starts.put(f.id,start);flight=f;
  return "Landslide catalogue "+f.id+"; WARN reel, impactborne et inversejournalise.";
 }
 private LandslideBinding provider(Flight f,boolean inverse){
  var data=NativeTerrainDisasterLayout.recipe();var replacements=inverse?data.inverse():data.forward();
  var base=new BlockTransformBinding(d->level(f),()->new OwnershipView(owners),(p,o)->publish(f,p,o),this::blockNow,
    Map.of(impact(f).recipe(),new BlockTransformBinding.Recipe(replacements,false)),f.cells.size(),PHYSICAL,Set.of(TERRAIN));
  return new LandslideBinding(base,server,new PlayerId(f.player),!inverse);
 }
 private PlanningSnapshot snapshot(Flight f){
  var view=new HashMap<>(owners);if(f.phase==Phase.INITIAL)view.putAll(f.initial);
  var cells=new HashMap<BlockPosition,TerrainCellSnapshot>();
  for(var p:f.selected){var o=Objects.requireNonNull(view.get(key(p)));cells.put(p,new TerrainCellSnapshot(p,hash(f,p),BlockTransformBinding.STATE_CODEC,Set.of(),Set.of(),false,true,o.epoch(),o.playerOverrideEpoch()));}
  if(!loaded(f))throw new IllegalStateException("disaster envelope unloaded");
  var fp=new Footprint(f.footprint.dimension(),f.footprint.loadedChunks(),Set.copyOf(f.selected),f.footprint.bounds());
  var terrain=new TerrainSnapshot(fp,cells,List.of(),Map.of(),new Revision(1));
  var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(terrain),Map.of());
  if(f.selection!=null&&(f.phase==Phase.INITIAL||f.phase==Phase.MARKERS||f.phase==Phase.LEAD)){
   var fresh=f.selection.refresh();
   var selected=(f.phase==Phase.MARKERS||f.phase==Phase.LEAD)?SelectedNativeSnapshot.terrainOwned(fresh,terrain,f.initial,view):SelectedNativeSnapshot.terrain(fresh,terrain);
   var actualOwners=new HashMap<>(selected.ownership().records());
   for(var p:f.selected){var resource=key(p);actualOwners.put(resource,Objects.requireNonNull(view.get(resource)));}
   return new PlanningSnapshot(selected.snapshot(),new OwnershipView(actualOwners),selected.budgets(),selected.blobs(),selected.bindings());
  }
  return new PlanningSnapshot(new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),now(),Map.of(),context,Set.of(),fp.loadedChunks(),HealthMode.NORMAL),
    new OwnershipView(view),new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));
 }
 private EffectPreparation capture(Flight f,ServerEffectPort.Binding binding){
  var s=snapshot(f);var i=f.selectedPlan!=null&&(f.phase==Phase.INITIAL||f.phase==Phase.LEAD)?
   f.selectedPlan.effects().stream().filter(effect->effect.primitive().equals(PHYSICAL)).findFirst().orElseThrow():
   new EffectIntent(new OperationToken(UUID.randomUUID()),PHYSICAL,1,impact(f),s.snapshot().context().terrain().getFirst().loadedFootprint(),new EventInstanceId(f.id));
  return binding.capture(i,s);
 }

 private ResourceArbiter arbiter(){return new ResourceArbiter(world,new Epoch(Math.max(floor,store.session().bootEpoch().value())),1000,4,new ResourceArbiter.Compatibility(){
  public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId r,ResourceClaim a,ResourceClaim b){return false;}
 });}
 private final Set<OperationToken> knownDurablePrepares=new HashSet<>();
 private final Map<TransactionId,Tx> safeAborts=new HashMap<>();
 private Execution run(Flight f,EffectPreparation captured,ServerEffectPort.Binding binding,boolean impact){
  return run(f,captured,binding,impact,impact&&f.selection!=null?f.selection.refresh():null);
 }
 private Execution run(Flight f,EffectPreparation captured,ServerEffectPort.Binding binding,boolean impact,DecisionInput selectedInput){
  var p=captured.prepared().orElseThrow();var i=p.intent();var e=new Execution();e.plan=p;e.retain=impact;e.arbiter=arbiter();
  if(binding instanceof Guarded guard)e.guard=guard;
  if(impact&&f.selection!=null)e.selectedInput=Objects.requireNonNull(selectedInput);
  var scope=new ScopeRef(ScopeKind.WORLD,world.value());var epochs=new HashMap<ResourceKey,Epoch>();p.claims().forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
  if(impact){
   var def=definition(f);var roles=f.selectedPlan==null?new RoleAssignments(Map.of("witness",List.of(new PlayerId(f.player)))):f.selectedPlan.roles();
   var ref=new DefinitionRef(def.id(),def.contentRevision(),BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(def)));
   var intents=new ArrayList<EffectIntent>();f.markers.forEach(m->intents.add(m.intent));intents.add(i);
   var execution=new ExecutionPlan(f.selectedPlan==null?new CandidateId(UUID.randomUUID()):f.selectedPlan.candidateId(),ref,
       f.selectedPlan==null?intents:f.selectedPlan.effects(),roles,f.selectedPlan==null?new ScopeSet(world,Set.of(scope,new ScopeRef(ScopeKind.PLAYER,f.player))):f.selectedPlan.scopes(),p.preconditions());
   var request=new AdmissionRequest(f.selectedDecision==null?new DecisionId(UUID.randomUUID()):f.selectedDecision,p.preconditions(),p.claims(),roles,execution);
   var validator=new AdmissionService.HardValidator(){
    private boolean transferReported;
     public ProviderMode mode(){return providerMode();}
    public AdmissionService.Assessment assess(AdmissionRequest proposed,Tick tick){
      try{
       String failedPredicate=!proposed.equals(request)?"REQUEST_IDENTITY":
         phases.view(f.disaster).stage()!=DisasterStage.IMPACT?"DISASTER_STAGE":
         !contextSafe(f)?"LIVE_CONTEXT":!i.typedArgs().equals(impact(f))?"TYPED_ARGS":
         !i.expectedFootprint().cells().equals(new HashSet<>(f.cells))?"FOOTPRINT":
         !binding.revalidate(p,captured.blobs())?"PHYSICAL_PRECONDITIONS":"";
       var transfer=f.selection==null?null:SelectedEventAdmission.ownedBlockAllowance(f.selection,store,i,f.initial);
       var available=transfer==null?CAP:transfer.available();
       if(transfer!=null&&!transferReported){
        System.out.println("DIRECTOR_TERRAIN_J03_OWNED_BUDGET_TRANSFER event="+f.id+" decision="+request.proposalId()+" operation="+i.operationToken()+" credited_cells="+transfer.creditedCells()+" cut="+transfer.cut()+" unchanged_cost="+def.budget()+" projected_available="+transfer.projectedAvailable()+" transferred_available="+available+" prior_budget_fits="+BudgetMath.fits(def.budget(),transfer.projectedAvailable()));
        transferReported=true;
       }
       boolean budgetFits=f.selection==null||BudgetMath.fits(def.budget(),available);
       boolean valid=failedPredicate.isEmpty()&&budgetFits;
       if(!valid)System.out.println("DIRECTOR_TERRAIN_J03_ASSESSMENT_REFUSED event="+f.id+" decision="+request.proposalId()+" operation="+i.operationToken()+" tick="+tick+" phase="+f.phase+" disaster_stage="+phases.view(f.disaster).stage()+" failed_predicate="+failedPredicate+" budget_fits="+budgetFits+" cost="+def.budget()+" available="+available);
       return new AdmissionService.Assessment(new ValidationResult(valid,valid?List.of():List.of(budgetFits?ReasonCode.STALE_CONTEXT:ReasonCode.BUDGET_EXHAUSTED),p.preconditions()),p.claims(),
         new ResourceArbiter.Rank(false,false,false,Priority.DANGER,0,1,42),Map.of(scope,def.budget()));
      }catch(RuntimeException failedAssessment){
       String detail=String.valueOf(failedAssessment.getMessage()).replace('\n',' ').replace('\r',' ');
       System.out.println("DIRECTOR_TERRAIN_J03_ASSESSMENT_FAULT event="+f.id+" decision="+request.proposalId()+" operation="+i.operationToken()+" tick="+tick+" phase="+f.phase+" type="+failedAssessment.getClass().getSimpleName()+" detail="+detail.substring(0,Math.min(256,detail.length())));
       throw failedAssessment;
      }
    }
   };
   e.admission=new AdmissionService(()->f.catalog,validator,e.arbiter,this::now,new DurationTicks(1200),4,providerMode());e.decision=request.proposalId();
   var queued=content==null?e.admission.admit(request):content.admit(e.admission,request);
    if(queued.status()!=AdmissionStatus.DEFERRED)throw new IllegalStateException("terrain J03 queue rejected event="+f.id+" decision="+request.proposalId()+" operation="+i.operationToken()+" status="+queued.status()+" reasons="+queued.reasons()+" phase="+f.phase+" disaster_stage="+phases.view(f.disaster).stage());
   var answer=e.admission.resolveWindow(epochs,Map.of(scope,f.selection==null?CAP:SelectedEventAdmission.ownedBlockAllowance(f.selection,store,i,f.initial).available())).get(e.decision);
   if(answer==null||answer.status()!=AdmissionStatus.ACCEPTED)throw new IllegalStateException("manual disaster J03 denied "+answer);
   e.token=answer.token().orElseThrow();
   System.out.println("DIRECTOR_TERRAIN_DISASTER_J02_J03_ACCEPTED "+f.id+" family="+f.family+" admission_mode="+(f.selectedPlan==null?"MANUAL_LAB":"CATALOGUE_SELECTED"));
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
  var prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),i.operationToken(),e.token.generation(),p.preconditions(),e.token.expiryTick(),slice,e.token,captured.validation(),e.selectedInput==null?List.of():CatalogueEventLedger.reserve(new CatalogueEventLedger.Selection(f.selection.definition(),f.selectedPlan),e.selectedInput,e.token,
      new Tick(fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server))),
    impact?Set.of(NoninvertibleEffect.DAMAGE):Set.of(),List.of(),p.durableBlobs());
  e.prepare=prepare;e.tx=prepare.transactionId();prepares.put(i.operationToken(),prepare);e.runner=new EventRunner(authority,store,content==null?port:content.attach(p,captured.blobs(),port,f.selection!=null&&(impact||i.primitive().equals(WARNING))),e.arbiter,4);
  var batch=new DurableBatch(e.tx,store.session().storeGeneration(),prepare.readSet().revisions(),List.of(new DurablePrepare(prepare)),captured.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
  e.runner.submit(batch,p.expectedPost(),e.live,actual->{
   e.actual=actual;if(impact)f.appliedAt=now().value();var rows=new ArrayList<DurableRecord>();var ledgerBlobs=new ArrayList<BlobContent>();rows.add(new DurableResult(new ResultRecord(e.tx,i.operationToken(),actual,Set.of(),List.of(),List.of())));
   if(i.primitive().equals(PHYSICAL)){
    for(var cell:p.intent().expectedFootprint().cells()){
     var k=key(cell);rows.add(new DurableOwnership(Objects.requireNonNull(owners.get(k))));
     var mutation=physicalMutation(f,e,cell,impact?MutationState.VERIFIED:MutationState.ROLLED_BACK);
     f.mutations.put(mutation.mutationId(),mutation);rows.add(new DurableMutation(mutation));
    }
    var data=new TreeMap<String,JsonValue>();data.put("direction",text(impact?"IMPACT":"INVERSE"));
    data.put("prepare",text(e.tx.value()));data.put("operation",text(i.operationToken().value()));data.put("slice",text(slice.sliceId().value()));
    data.put("result",text(encode(RESULT.encode(actual))));data.put("tick",text(now().value()));
    if(impact&&binding instanceof Guarded guard){
     var players=new TreeMap<String,JsonValue>();guard.delegate.observedDamageFacts().forEach((id,v)->players.put(id.value().toString(),v));
     data.put("damage",new JsonValue.ObjectValue(players));
    }
    var observed=fact(PHYSICS,f,data);f.consequences.add(observed);rows.add(new DurableFact(observed));
   }
   if(e.selectedInput!=null){var ledger=CatalogueEventLedger.effect(new CatalogueEventLedger.Selection(f.selection.definition(),f.selectedPlan),e.selectedInput,prepare,actual,
       new Tick(fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server)));rows.addAll(ledger.records());ledgerBlobs.addAll(ledger.blobs());}
   return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,ledgerBlobs);
  });return e;
 }
 private MutationRecord physicalMutation(Flight f,Execution e,BlockPosition cell,MutationState state){
  var k=key(cell);var initial=f.initial.get(k);var id=initial.creationMutationId();var old=f.mutations.get(id);
  if(state==MutationState.ROLLED_BACK&&old!=null){
   var h=old.header();return new MutationRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),h.lastSequence(),now()),
     old.mutationId(),old.instanceId(),old.sliceId(),state,old.primitiveId(),old.primitiveVersion(),old.footprint(),old.preStateRef(),old.postStateRef(),old.ownerToken(),old.ownerEpoch(),old.readSetVersions(),old.noninvertibleEffects(),old.reconciliationEvidence(),old.compensationRef(),new Sequence(0));
  }
  var p=e.plan;
  return new MutationRecord(new AggregateHeader(1,id.value(),new Revision(1),new Sequence(0),now()),id,new EventInstanceId(f.id),e.prepare.slice().sliceId(),state,PHYSICAL,1,
    new Footprint(cell.dimension(),Set.of(new ChunkRef(cell.dimension(),cell.x()>>4,cell.z()>>4)),Set.of(cell),List.of()),
    Objects.requireNonNull(p.durableBlobs().preStates().get(k)),Objects.requireNonNull(p.durableBlobs().expectedPostStates().get(k)),
    initial.ownerTokens().iterator().next(),initial.epoch(),p.preconditions(),Set.of(NoninvertibleEffect.DAMAGE),List.of(),Optional.empty(),new Sequence(0));
 }
 private void release(Execution e){
  if(e==null||e.released)return;e.arbiter.release(e.token);if(e.admission!=null)e.admission.released(e.decision,e.token);e.released=true;
 }
 private boolean applied(Flight f,Execution e){
  if(e.done)return true;
  if(e.aborted){if(!durable(safeAborts.get(e.tx)))return false;results.put(e.prepare.operationToken(),new ResultRecord(e.tx,e.prepare.operationToken(),e.terminalOverride,Set.of(),List.of(),List.of()));e.done=true;release(e);return true;}
  if(e.selectedInput!=null&&e.lastStage==EventRunner.Stage.PREPARING){try{if(!f.selection.forwardContextAvailable())return false;}catch(IllegalStateException refused){f.stop=true;}}
  e.live=new LiveValidationSnapshot(e.live.readSet(),e.live.loadedChunks(),e.live.health(),now(),e.token.generation());
  var view=e.runner.poll(e.tx,e.live);e.lastStage=view.stage();
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
  var b=f.footprint.bounds().getFirst();var w=warning(f);
  for(var player:f.audience)for(var corner:List.of("NW","NE","SE","SW")){
   var m=new Marker();m.player=player;m.corner=corner;boolean east=corner.equals("NE")||corner.equals("SE"),south=corner.equals("SW")||corner.equals("SE");
   m.transform=new PerceptionTransform(east?b.maxXExclusive()-.25:b.minX()+.25,b.minY()+.8,south?b.maxZExclusive()-.25:b.minZ()+.25,0,0,2);
   m.binding=new NativeWarningBinding(server,player,m.transform,1200,w.profile(),this::now);
   OperationToken operation=new OperationToken(UUID.randomUUID());
   if(f.selectedPlan!=null){
    var original=f.selectedPlan.effects().stream().filter(i->i.primitive().equals(WARNING)).findFirst().orElseThrow();
    operation=f.markers.isEmpty()?original.operationToken():new OperationToken(UUID.nameUUIDFromBytes(
        ("selected-perimeter-v1\0"+original.operationToken().value()+"\0"+player.value()+"\0"+corner)
          .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
   }
   m.intent=new EffectIntent(operation,WARNING,1,w,f.footprint,new EventInstanceId(f.id));f.markers.add(m);
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
  phases.propose(f.disaster,new TransactionId(UUID.randomUUID()),threshold(f),conditions(f,false,true,false));f.phase=Phase.STABILIZE_ACK;
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
   var h=old.header();var state=old.state()==MutationState.VERIFIED?MutationState.COMMITTED:old.state();
   var committed=new MutationRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),restored.committedSequence(),now()),
    old.mutationId(),old.instanceId(),old.sliceId(),state,old.primitiveId(),old.primitiveVersion(),old.footprint(),old.preStateRef(),old.postStateRef(),old.ownerToken(),old.ownerEpoch(),old.readSetVersions(),old.noninvertibleEffects(),old.reconciliationEvidence(),old.compensationRef(),restored.committedSequence());
   rows.add(new DurableMutation(committed));f.mutations.put(committed.mutationId(),committed);
  }
  if(rows.size()!=f.mutations.size())throw new IllegalStateException("missing actual physical mutation");
  f.tx=append(rows);f.phase=Phase.MUTATION_ACK;return true;
 }
 private void join(Flight f){
  var status=f.conflict?"CONFLICT_ISOLATED_UNRESOLVED":f.afterCleanup?"TERRAIN_STABILIZED_DAMAGE_NOT_REVERSED":f.forwardSucceeded?"IMPACT_PHYSICALLY_COMMITTED":"NO_PHYSICAL_IMPACT";
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
 private void cleanup(Flight f){
  f.selected=new HashSet<>();
  for(var p:f.cells){
   var current=attached.read(level(f),pos(p)).orElseThrow(()->new IllegalStateException("cleanup local ownership unavailable"));owners.put(key(p),current);
   var initial=f.initial.get(key(p));var actual=hash(f,p);
   if(OwnershipRules.rollback(current,initial.ownerTokens().iterator().next(),initial.epoch(),initial.playerOverrideEpoch(),
      f.forward.expectedPost().get(key(p)),Optional.of(actual),true)==OwnershipRules.Rollback.ELIGIBLE)f.selected.add(p);
  }
  if(f.selected.isEmpty()){releaseMetadata(f);return;}
  for(var source:f.sources)if(f.selected.contains(source)&&!level(f).getEntities(null,new AABB(pos(source))).isEmpty())return;
  var inverse=provider(f,true);f.work=run(f,capture(f,inverse),inverse,false);f.phase=Phase.INVERSE;
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
    "damage_reversed",new JsonValue.Bool(false),"manual_risk",new JsonValue.Bool(f.selectedPlan==null)));
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
 private DurableFact recoveredSelectedCompletion(Flight f,Map<String,JsonValue> actualEvidence){
  var payload=new HashMap<>(actualEvidence);payload.put("definition",text(f.selectedPlan.definition().id().value()));
  payload.put("decision",text(f.selectedDecision.value()));payload.put("source",text("CATALOGUE_SELECTED"));
  f.parentChain.ifPresent(id->payload.put("parent_chain",text(id.value())));
  var id=new FactId(UUID.nameUUIDFromBytes(("catalogue-completion-v1\0"+f.id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  return new DurableFact(new DirectorFact(id,f.selectedDefinition.completion().factType(),
      f.selectedPlan.scopes().scopes().stream().sorted(Comparator.comparing(ScopeRef::toString)).toList(),
      Optional.of(new EventInstanceId(f.id)),EvidenceStatus.CONFIRMED,payload,true,Optional.empty()));
 }
 private boolean noPhysicalPrepare(UUID event){
  return prepares.values().stream().noneMatch(p->p.reservation().instanceId().value().equals(event)
    &&p.slice().operations().stream().anyMatch(i->i.primitive().equals(PHYSICAL)));
 }
 /** A terminal physical END cannot hide a still-open selected catalogue event.
  * Only the proven zero-impact case is repairable here; historical success is never inferred. */
 private boolean needsSelectedAbortRepair(UUID event){
  var saved=starts.get(event);var head=recoveredCatalogueHeads.get(event);
  if(saved==null||!saved.payload().containsKey("selected_plan")||head==null
    ||Set.of(EventState.SUCCEEDED,EventState.PARTIAL,EventState.ABORTED_SAFE,EventState.FAILED_SAFE).contains(head.state()))return false;
  var disaster=restoredDisasters.get(new DisasterId(UUID.fromString(text(saved,"disaster"))));
  return disaster!=null&&disaster.eventInstanceId().value().equals(event)
    &&Set.of(DisasterStage.RESOLVED,DisasterStage.ABORTED_SAFE).contains(disaster.stage())
    &&journalMutations.values().stream().noneMatch(m->m.instanceId().value().equals(event))&&noPhysicalPrepare(event);
 }
 private boolean recoveredNoImpact(Flight f){
  if(!f.recovery||f.selectedPlan==null||!f.mutations.isEmpty()||!noPhysicalPrepare(f.id)
    ||!Set.of(DisasterStage.RESOLVED,DisasterStage.ABORTED_SAFE).contains(phases.view(f.disaster).stage()))return false;
  if(f.consequences.stream().anyMatch(proof->proof.payload().get("direction") instanceof JsonValue.Text d&&d.value().equals("IMPACT")))return false;
  for(var p:f.cells){
   var initial=f.initial.get(key(p));var current=owners.get(key(p));var local=attached.read(level(f),pos(p));
   if(initial==null||current==null||local.filter(current::equals).isEmpty()||!lineage(current,initial)
     ||current.disposition()!=OwnershipDisposition.RELEASED
     ||!hash(f,p).equals(f.forward.preconditions().fingerprints().get(key(p)))
     ||!current.expectedPostHash().equals(hash(f,p)))return false;
  }
  return true;
 }
 private List<DurableRecord> recoveredSelectedAbort(Flight f){
  if(!recoveredNoImpact(f))throw new IllegalStateException("selected cold no-impact absence/released-owner proof unavailable");
  var payload=new TreeMap<String,JsonValue>();payload.put("definition",text(f.selectedPlan.definition().id().value()));
  payload.put("decision",text(f.selectedDecision.value()));payload.put("source",text("CATALOGUE_SELECTED"));
  payload.put("provider_mode",text(providerMode().name()));payload.put("reason",text("COLD_CLEANUP_WITHOUT_PHYSICAL_PREPARE"));
  payload.put("recovery_complete",new JsonValue.Bool(true));payload.put("no_physical_effect",new JsonValue.Bool(true));
  payload.put("damage_replayed",new JsonValue.Bool(false));payload.put("disaster",text(f.disaster.value()));
  payload.put("released_cells",new JsonValue.Array(f.cells.stream().map(p->(JsonValue)text(key(p).key())).toList()));
  f.parentChain.ifPresent(id->payload.put("parent_chain",text(id.value())));
  var id=new FactId(UUID.nameUUIDFromBytes(("catalogue-aborted-v1\0"+f.id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  var aborted=new DirectorFact(id,new FactType("proutlost_director:catalogue_event_aborted"),
    f.selectedPlan.scopes().scopes().stream().sorted(Comparator.comparing(ScopeRef::toString)).toList(),
    Optional.of(new EventInstanceId(f.id)),EvidenceStatus.CONFIRMED,payload,true,Optional.empty());
  return List.of(new DurableFact(aborted),NativeCatalogueFactOutbox.abort(aborted,f.selectedDecision,now()));
 }
 private void end(Flight f){
  var rows=new ArrayList<DurableRecord>();var completionBlobs=new ArrayList<BlobContent>();
  for(var p:prepares.values())if(p.reservation().instanceId().value().equals(f.id)&&knownDurablePrepares.contains(p.operationToken())&&!results.containsKey(p.operationToken()))
   rows.add(new DurableResult(new ResultRecord(p.transactionId(),p.operationToken(),new SliceResult(Outcome.DELIVERY_UNCERTAIN,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of()),Set.of(),List.of(),List.of())));
  if(f.selectedPlan!=null&&f.mutations.size()==f.cells.size()&&f.mutations.values().stream().allMatch(m->m.durableSeq().value()>0)
      &&(f.forwardSucceeded||f.consequences.stream().anyMatch(proof->proof.payload().get("direction") instanceof JsonValue.Text d
          &&d.value().equals("IMPACT")&&RESULT.decode(decode(proof,"result")).outcome()==Outcome.SUCCEEDED))){
   var evidence=new TreeMap<String,JsonValue>();evidence.put("disaster",text(f.disaster.value()));
   evidence.put("mutation_refs",new JsonValue.Array(f.mutations.keySet().stream().sorted().map(id->(JsonValue)text(id.value())).toList()));
   var states=new TreeMap<String,JsonValue>();f.mutations.forEach((id,m)->states.put(id.value().toString(),text(m.state())));
   evidence.put("mutation_states",new JsonValue.ObjectValue(states));evidence.put("damage_replayed",new JsonValue.Bool(false));
   evidence.put("original_warn_operation",text(f.selectedPlan.effects().stream().filter(i->i.primitive().equals(WARNING)).findFirst().orElseThrow().operationToken().value()));
   evidence.put("original_impact_operation",text(f.forward.intent().operationToken().value()));
   if(f.selection!=null){var contribution=f.selection.completionContribution(evidence);rows.addAll(contribution.records());completionBlobs.addAll(contribution.blobs());}else rows.add(recoveredSelectedCompletion(f,evidence));
  }else if(f.selection!=null)rows.addAll(f.selection.abortRecords("NO_CONFIRMED_COMPLETE_TERRAIN_RESULT",Map.of("disaster",text(f.disaster.value()),"recovery_complete",new JsonValue.Bool(true))));
  else if(f.selectedPlan!=null&&recoveredNoImpact(f))rows.addAll(recoveredSelectedAbort(f));
  rows.add(new DurableFact(fact(END,f,Map.of("disaster",text(f.disaster.value()),"stage",text(phases.view(f.disaster).stage()),"damage_replayed",new JsonValue.Bool(false)))));
  f.tx=append(rows,completionBlobs);f.phase=Phase.END_ACK;
 }
 public String cancel(ServerPlayer player){actor();if(!player.hasPermissions(4))return "OP4 requis.";if(flight==null)return "Aucune catastrophe LAB active.";flight.stop=true;return "STABILIZE demande; aucune nouvelle tentative de degats.";}
 public boolean controlRecoveryReady(){actor();return !failed&&scanned&&!store.readOnlySafe();}
 /** Canonical admin ingress after its exact durable intent; completion observes this same physical flight. */
 public Optional<java.util.function.Supplier<AdminStatus>> requestStop(Optional<EventInstanceId> target){
  actor();var f=flight;if(f==null||target.filter(id->!id.value().equals(f.id)).isPresent())return Optional.empty();
  var event=f.id;f.stop=true;
  return Optional.of(()->{actor();return ends.contains(event)?AdminStatus.SUCCEEDED:failed||(f.phase==Phase.CONFLICT)?AdminStatus.FAILED_SAFE:AdminStatus.PENDING;});
 }
 public Optional<java.util.function.Supplier<AdminStatus>> requestStabilize(DisasterId target){
  actor();return flight!=null&&flight.disaster.equals(target)?requestStop(Optional.of(new EventInstanceId(flight.id))):Optional.empty();
 }
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
     phases.propose(f.disaster,new TransactionId(UUID.randomUUID()),threshold(f),conditions(f,true,false,false));f.phase=Phase.WARN_ACK;
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
       if(now().value()>=f.markerDeadline){stabilize(f);return;}
       if(f.stop||!playerReady(f)||!audience(f).equals(f.audience)){stabilize(f);return;}
       if(f.selection!=null&&!f.selection.forwardContextAvailable())return;
       EffectPreparation captured;
       try{captured=m.binding.capture(m.intent,snapshot(f));}
       catch(SelectedEventStart.ContextUnavailable notReady){return;}
       catch(RuntimeException staleCapture){f.stop=true;System.out.println("DIRECTOR_TERRAIN_DISASTER_WARNING_CAPTURE_REFUSED_NO_PHYSICAL_PREPARE "+staleCapture.getClass().getSimpleName()+":"+staleCapture.getMessage());stabilize(f);return;} // No PREPARE was submitted.
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
     if(f.selection!=null&&!f.selection.forwardContextAvailable())return;
     var state=phases.view(f.disaster);if(now().value()-state.warningEvidence().deployedTick().value()<LEAD.value())return;
     for(var p:f.cells){var current=attached.read(level(f),pos(p)).orElseThrow();owners.put(key(p),current);
      if(!current.equals(f.initial.get(key(p)))||!hash(f,p).equals(f.forward.preconditions().fingerprints().get(key(p)))){f.stop=true;stabilize(f);return;}
     }
     f.selected=new HashSet<>(f.cells);var p=provider(f,false);f.preparedImpact=p;
     try{f.capturedImpact=capture(f,p);}
     catch(SelectedEventStart.ContextUnavailable notReady){return;}
     catch(RuntimeException unsafeCapture){f.stop=true;System.out.println("DIRECTOR_TERRAIN_DISASTER_PREIMPACT_CAPTURE_REFUSED_NO_PHYSICAL_PREPARE "+unsafeCapture.getClass().getSimpleName());stabilize(f);return;}
     f.forward=f.capturedImpact.prepared().orElseThrow();
     var sealed=dossier(f);f.tx=append(List.of(new DurableFact(sealed)));starts.put(f.id,sealed);f.phase=Phase.SEAL;
    }
    case SEAL->{
     if(!durable(f.tx))return;if(!contextSafe(f)){stabilize(f);return;}
     phases.propose(f.disaster,new TransactionId(UUID.randomUUID()),threshold(f),conditions(f,true,false,false));f.phase=Phase.IMPACT_ACK;
    }
    case IMPACT_ACK->{
     if(!coreCommitted(f))return;
     if(!contextSafe(f)){stabilize(f);return;}
     if(f.selection!=null&&!f.selection.forwardContextAvailable())return;
     var guarded=new Guarded(f,f.preparedImpact);DecisionInput selectedInput;
     try{selectedInput=f.selection==null?null:f.selection.refresh();}
     catch(SelectedEventStart.ContextUnavailable notReady){return;}
     // Admission and EventRunner.submit are never retried by the transient WAIT catch.
     f.work=run(f,f.capturedImpact,guarded,true,selectedInput);f.impact=f.work;f.phase=Phase.IMPACT;
    }
    case IMPACT->{
     if(!applied(f,f.work))return;f.forwardSucceeded=!f.work.aborted;
     if(!f.forwardSucceeded){stabilize(f);return;}
     if(f.appliedAt<0)throw new IllegalStateException("physical apply tick missing");
     f.expires=Math.addExact(f.appliedAt,400);readMutations(f,false);
     System.out.println("DIRECTOR_TERRAIN_DISASTER_REAL_IMPACT_COMMITTED "+f.id+" "+f.family+" cells="+f.cells.size());
    }
    case MUTATION_READ->finalizeMutations(f);
    case MUTATION_ACK->{if(durable(f.tx))join(f);}
    case JOIN_ACK->{if(coreCommitted(f))f.phase=Phase.HOLD;}
    case HOLD->{if(!contextSafe(f)||now().value()>=f.expires)stabilize(f);}
    case STABILIZE_ACK->{if(coreCommitted(f))f.phase=Phase.CLEANUP;}
    case CLEANUP->cleanup(f);
    case INVERSE->{if(applied(f,f.work))releaseMetadata(f);}
    case OWNER_READ->releaseMetadata(f);
    case RELEASE_ACK->{if(durable(f.tx)){publishReleased(f);readMutations(f,true);}}
    case CLEAN_JOIN_ACK->{
     if(!coreCommitted(f))return;
     if(f.conflict){f.phase=Phase.CONFLICT;System.out.println("DIRECTOR_TERRAIN_DISASTER_CONFLICT_UNRESOLVED "+f.id);return;}
     var stage=phases.view(f.disaster).stage();
     if(stage==DisasterStage.WATCH||stage==DisasterStage.WARN)phases.abortNoImpact(f.disaster,new TransactionId(UUID.randomUUID()),"NO_IMPACT_OWNERSHIP_RELEASED",now());
     else if(stage==DisasterStage.STABILIZE)phases.propose(f.disaster,new TransactionId(UUID.randomUUID()),threshold(f),conditions(f,false,true,true));
     else if(stage==DisasterStage.RESOLVED||stage==DisasterStage.ABORTED_SAFE){end(f);return;}
     else throw new IllegalStateException("cleanup before durable STABILIZE");
     f.phase=Phase.RESOLVE_ACK;
    }
    case RESOLVE_ACK->{if(coreCommitted(f))end(f);}
    case END_ACK->{
     if(!durable(f.tx))return;for(var m:f.markers){m.binding.stop();release(m.execution);}release(f.impact);release(f.work);
     phases.retire(f.disaster);starts.remove(f.id);ends.add(f.id);prepares.values().removeIf(p->p.reservation().instanceId().value().equals(f.id));
     System.out.println("DIRECTOR_TERRAIN_DISASTER_RESOLVED_DURABLE "+f.id+" "+f.family);flight=null;
    }
    case REPAIR_ACK->{if(durable(f.tx))stabilize(f);}
    case CONFLICT->{/* Real isolated conflict remains visible in STABILIZE. No overwrite or false RESOLVED. */}
   }
  }catch(RuntimeException failure){failed=true;System.out.println("DIRECTOR_TERRAIN_DISASTER_RECOVERY_REQUIRED "+failure);failure.printStackTrace();}
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
  actor();scanned=false;scanning=null;cursor=Optional.empty();starts.clear();ends.clear();recoveredCatalogueHeads.clear();restoredDisasters.clear();warnEntries.clear();journalOwners.clear();journalMutations.clear();
  prepares.clear();results.clear();consequences.clear();knownDurablePrepares.clear();safeAborts.clear();floor=0;clockFloor=0;offset=0;
 }
 public void acceptRecoveryRows(List<DurableRecord> records,boolean last){
  actor();
  for(var row:records){
   if(row instanceof DurableAggregate a){
    clockFloor=Math.max(clockFloor,a.state().header().updatedTick().value());
    if(a.state() instanceof EventAggregate e){var id=e.value().instanceId().value();var old=recoveredCatalogueHeads.get(id);if(old==null||old.header().revision().compareTo(e.header().revision())<0)recoveredCatalogueHeads.put(id,e.value());}
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
   for(var id:ends)if(prepares.values().stream().noneMatch(p->p.reservation().instanceId().value().equals(id)&&!results.containsKey(p.operationToken()))&&!needsSelectedAbortRepair(id))starts.remove(id);
   prepares.values().removeIf(p->!starts.containsKey(p.reservation().instanceId().value()));
   knownDurablePrepares.retainAll(prepares.keySet());results.keySet().retainAll(prepares.keySet());
   journalMutations.values().removeIf(m->!starts.containsKey(m.instanceId().value()));consequences.keySet().retainAll(starts.keySet());
   offset=Math.max(0,clockFloor-server.overworld().getGameTime());scanned=true;
   System.out.println("DIRECTOR_TERRAIN_DISASTER_RECOVERY_SCAN pending="+starts.size());
  }
 }
 private void recover(){
  if(starts.isEmpty())return;var entry=starts.entrySet().iterator().next();var saved=entry.getValue();var f=new Flight();f.id=entry.getKey();f.player=UUID.fromString(text(saved,"player"));
  f.disaster=new DisasterId(UUID.fromString(text(saved,"disaster")));f.family=DisasterRunner.Family.valueOf(text(saved,"family"));
  f.sources=positions(saved,"sources");f.destinations=positions(saved,"destinations");var all=new ArrayList<>(f.sources);all.addAll(f.destinations);f.cells=List.copyOf(all);
  f.footprint=FOOTPRINT.decode(decode(saved,"footprint"));f.forward=PLAN.decode(decode(saved,"plan"));f.selected=new HashSet<>(f.cells);
  if(saved.payload().containsKey("selected_plan")){
   f.selectedPlan=new ModelCodec<>(ExecutionPlan.class).decode(decode(saved,"selected_plan"));
   f.selectedDecision=new DecisionId(UUID.fromString(text(saved,"selected_decision")));
   f.selectedDefinition=new ModelCodec<>(EventDefinition.class).decode(decode(saved,"selected_definition"));
   if(saved.payload().get("parent_chain") instanceof JsonValue.Text parent)f.parentChain=Optional.of(new ChainId(UUID.fromString(parent.value())));
   if(!f.selectedPlan.effects().getFirst().instanceId().value().equals(f.id)||!f.selectedDefinition.id().equals(f.selectedPlan.definition().id()))
    throw new IllegalStateException("selected terrain WAL lineage mismatch");
  }
  f.exits=((JsonValue.Array)saved.payload().get("exits")).values().stream().map(v->EXIT.decode(Base64.getDecoder().decode(((JsonValue.Text)v).value()))).toList();
  for(var value:((JsonValue.Array)saved.payload().get("owners")).values()){var owner=OWNER.decode(Base64.getDecoder().decode(((JsonValue.Text)value).value()));f.initial.put(owner.resourceKey(),owner);}
  catalogue(f);if(!f.definition.value().equals(text(saved,"definition"))||!f.forward.intent().instanceId().value().equals(f.id)||!f.forward.intent().expectedFootprint().cells().equals(new HashSet<>(f.cells)))throw new IllegalStateException("terrain disaster dossier identity");
  if(!loaded(f))return;
  var state=Objects.requireNonNull(restoredDisasters.get(f.disaster),"disaster aggregate missing");
  if(!state.eventInstanceId().value().equals(f.id)||!state.recipeId().equals(f.definition)||!state.envelope().equals(f.footprint))throw new IllegalStateException("disaster aggregate/dossier mismatch");
  f.audience=state.affectedAudience();f.audienceRevision=state.warningEvidence().audienceRevision();f.recovery=true;f.stop=true;
  for(var m:journalMutations.values())if(m.instanceId().value().equals(f.id))f.mutations.put(m.mutationId(),m);
  f.consequences.addAll(consequences.getOrDefault(f.id,List.of()));
  var repairs=new ArrayList<DurableRecord>();
  for(var p:f.cells){
   var k=key(p);var initial=Objects.requireNonNull(f.initial.get(k));var persisted=Objects.requireNonNull(journalOwners.get(k),"terrain owner missing from WAL");var actual=hash(f,p);
   var local=attached.read(level(f),pos(p)).orElse(null);
   if(local==null&&persisted.equals(initial)&&actual.equals(f.forward.preconditions().fingerprints().get(k))){local=initial;publish(f,p,local);}
   if(local==null)throw new IllegalStateException("terrain ownership missing in saved world");
   if(!local.equals(persisted)){
    boolean next=local.header().id().equals(persisted.header().id())&&local.resourceKey().equals(k)&&local.ownerTokens().equals(persisted.ownerTokens())&&local.header().revision().value()==persisted.header().revision().value()+1;
    boolean owned=next&&lineage(local,initial)&&local.expectedPostHash().equals(actual)&&(actual.equals(f.forward.preconditions().fingerprints().get(k))||actual.equals(f.forward.expectedPost().get(k)));
    boolean yielded=next&&Set.of(OwnershipDisposition.YIELDED_TO_PLAYER,OwnershipDisposition.CONFLICT_ISOLATED).contains(local.disposition())&&local.epoch().value()>=persisted.epoch().value()&&local.playerOverrideEpoch().value()>=persisted.playerOverrideEpoch().value();
    if(!owned&&!yielded)throw new IllegalStateException("WAL/world terrain ownership mismatch; never overwrite");
    repairs.add(new DurableOwnership(local));
   }
   owners.put(k,local);
   if(actual.equals(f.forward.expectedPost().get(k))&&lineage(local,initial)&&!f.mutations.containsKey(initial.creationMutationId())){
    var prepared=prepares.values().stream().filter(q->q.reservation().instanceId().value().equals(f.id)&&q.slice().operations().size()==1&&q.slice().operations().getFirst().primitive().equals(PHYSICAL)&&q.operationToken().equals(f.forward.intent().operationToken())).findFirst().orElseThrow(()->new IllegalStateException("observed post without durable physical PREPARE"));
    if(!prepared.durableBlobs().equals(f.forward.durableBlobs())||!prepared.readSet().equals(f.forward.preconditions())
      ||!prepared.slice().operations().equals(List.of(f.forward.intent())))throw new IllegalStateException("physical dossier does not match durable PREPARE");
    var e=new Execution();e.prepare=prepared;e.plan=f.forward;
    var recovered=physicalMutation(f,e,p,MutationState.APPLIED_UNCONFIRMED);f.mutations.put(recovered.mutationId(),recovered);repairs.add(new DurableMutation(recovered));
   }
  }
  var warningEntry=warnEntries.get(f.disaster);if(warningEntry==null)phases.restore(state);else phases.restore(state,warningEntry);
  flight=f;if(repairs.isEmpty())stabilize(f);else{f.tx=append(repairs);f.phase=Phase.REPAIR_ACK;}
  System.out.println("DIRECTOR_TERRAIN_DISASTER_RECOVERY_CLEANUP_ONLY "+f.id+" damage_replay=false");
 }
 public boolean recovering(){actor();return !scanned&&!failed;}
 public boolean busy(){actor();return !scanned||failed||flight!=null||!starts.isEmpty();}
 public String status(){actor();return failed?"TERRAIN_DISASTER_RECOVERY_REQUIRED":!scanned?"TERRAIN_DISASTER_RECOVERING":flight==null?"TERRAIN_DISASTER_IDLE":"TERRAIN_DISASTER "+flight.family+" "+flight.phase+" "+flight.id;}
 @Override public void close(){actor();if(flight!=null)for(var m:flight.markers)m.binding.stop();closed=true;}
}