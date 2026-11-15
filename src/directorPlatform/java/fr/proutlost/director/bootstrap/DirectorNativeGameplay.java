package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.ownership.*;
import fr.proutlost.director.adapter.neoforge.network.DirectorGameplayAssets;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.IEventBus;

/** Explicit LAB-only physical growth. No automatic catalogue selection or production activation. */
public final class DirectorNativeGameplay implements AutoCloseable {
 private final DirectorRuntimeContent content;
 private static final PrimitiveId GROWTH=new PrimitiveId("proutlost:camp_growth");
 private static final CapabilityId PLANTS=new CapabilityId("mutation.plants_v1");
 private static final ResourceId RECIPE=new ResourceId("proutlost:test_camp_plants");
 private static final FactType DOSSIER=new FactType("proutlost_director:lab_growth_dossier");
 private static final FactType DONE=new FactType("proutlost_director:lab_growth_done");
 private static final ModelCodec<PreparedEffect> PLAN=new ModelCodec<>(PreparedEffect.class);
 private static final ModelCodec<OwnershipRecord> OWNER=new ModelCodec<>(OwnershipRecord.class);
 private static final BudgetVector CAPACITY=new BudgetVector(100,100,100,100,100,100,100,100,100,100);
 private CatalogSnapshot manualGrowthCatalog;
 private CatalogSnapshot manualGrowthCatalogue(){
  requireLaboratory();if(manualGrowthCatalog==null)manualGrowthCatalog=new DirectorLabCampRegrowthCatalog(DirectorLabObservationProfile.load()).snapshot();
  return manualGrowthCatalog;
 }
 private EventDefinition manualGrowthDefinition(){return Objects.requireNonNull(manualGrowthCatalogue().events().get(new DefinitionId("proutlost_director:candidate/camp_regrowth")));}
 private final MinecraftServer server; private final WorldId world; private final JournalStore store;
 private final ServerOwnershipAttachments attached=new ServerOwnershipAttachments(64);
 private final Map<ResourceKey,OwnershipRecord> owners=new HashMap<>();
 private final Map<UUID,DirectorFact> dossiers=new LinkedHashMap<>();
 private final Set<UUID> completed=new HashSet<>();
 private final Map<MutationId,MutationRecord> growthMutations=new HashMap<>();
 private final List<CompletableFuture<CommitAck>> hookAcks=new ArrayList<>();
 private PlayerOwnershipChangeHooks hooks; private Flight flight;
 private final DirectorNativeGoatGameplay goats;
 private final DirectorNativePerceptionGameplay perceptions;
 private final DirectorNativeChainGameplay chains;
 private DirectorNativeChainGameplay.Driver selectedChainDriver;
 private final DirectorNativeCatalogueControl catalogueControl;
 private DirectorNativeCatalogueCoordinator catalogueCoordinator;
 private final ConfigView catalogueConfig;
 private final DirectorNativeF8Control f8Control;
 private Object metadataLaneOwner;
 private DirectorNativeLocationImports locations;
 private DirectorNativeCampStateProjection campState;
 private DirectorNativePhysicalCatalogues physicalCatalogues;
 private DirectorProductionRegionalWeather regionalWeather;
 private final List<DurableRecord> locationRecoveryRows=new ArrayList<>();
 private Sequence locationRecoveryCut=new Sequence(0);
 private NativeControlledWaterPlayerProbe waterProbe;
 private boolean waterProbeReported;
 private final DirectorNativeLandslideGameplay landslides;
 private final DirectorNativeTerrainDisasterGameplay disasters;
 private final DirectorNativeFloodDisasterGameplay floods;
 private final DirectorNativeFireDisasterGameplay fires;
 private DirectorNativeStormGameplay storms;
 private WeatherOverrideObserver weatherObserver;
 private IEventBus weatherEventBus;
 private final Set<UUID> pendingStormRecovery=new HashSet<>();
 private WeatherRecoveryRouting regionalRecoveryRouting;
 private final Set<OperationToken> unresolvedWeatherRecovery=new HashSet<>();
 private ControlledWaterOwnershipHooks waterHooks;
 private final DirectorNativeFaunaMigrationGameplay fauna;
 private final DirectorNativeChoiceGameplay choices;
 private DirectorNativeTraceGameplay traces;
 private final Map<OperationToken,PrepareRecord> traceRecovery=new LinkedHashMap<>();
 private long reservationFloor;
 private CompletableFuture<StoreReadResult> pendingRead;
 private StoreQuery gameplayRecoveryQuery;
 private Optional<String> cursor=Optional.empty(); private boolean scanned,closed,failed;
 private static final class Execution {
  NativeWarningBinding perception;PrepareRecord perceptionPrepare;CompletableFuture<CommitAck> noSendAck;boolean noSend;
  boolean retainClaim;AdmissionService admission;DecisionId proposal;EventRunner runner;TransactionId tx;LiveValidationSnapshot live;
  ResourceArbiter arbiter;ReservationToken token;PreparedEffect plan;
  SliceResult actual;MutationRecord mutation,mutationCommitted;boolean mutationFinalized;StoreSession mutationSession;StoreQuery mutationQuery;
  CompletableFuture<StoreReadResult> mutationRead;DurableBatch mutationBatch;CompletableFuture<CommitAck> mutationAck;
  DecisionInput selectedInput;StoreSession originSession;EventRunner.Stage selectedStage=EventRunner.Stage.PREPARING;
 }
 /** Only a completed runner classification or invalid local RESULT may enter
  * this path. WAL/session/recovery/metadata failures retain the global gate. */
 private static final class GrowthTrancheUncertain extends IllegalStateException {
  final Execution execution;
  GrowthTrancheUncertain(Execution execution,String reason){super(reason);this.execution=execution;}
 }
 private static final class Flight {
  UUID id,player; ResourceId original; BlockPosition position; ResourceKey key;
  OwnershipRecord admitted; PreparedEffect forward; EffectPreparation captured;
  long warnAt=-1,warnSentAt,expires;long selectedDeadline=Long.MAX_VALUE; int phase;
  CompletableFuture<CommitAck> ack; Execution work,impact;
  NativeWarningBinding warning;Execution terminalMutation;boolean donePending,finishAfterFinalizeRelease;
  SelectedEventStart selection;ExecutionPlan selectedPlan;EventDefinition selectedDefinition;DecisionId selectedDecision;
  AnchorRecord selectedCamp;Optional<ChainId> parentChain=Optional.empty();boolean selectedRecovery;String selectedWait="";
  NativeCatalogueColdCompletion.Identity coldIdentity;NativeCatalogueColdCompletion.Read coldRead;boolean stopRequested,quarantined;
 }
 public DirectorNativeGameplay(MinecraftServer server,WorldId world,JournalStore store){this(server,world,store,null,true);}
 public DirectorNativeGameplay(MinecraftServer server,WorldId world,JournalStore store,DirectorRuntimeContent content){this(server,world,store,Objects.requireNonNull(content),false);}
 private DirectorNativeGameplay(MinecraftServer server,WorldId world,JournalStore store,DirectorRuntimeContent content,boolean legacyLaboratory){
  this.content=content;
  this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);
  this.store=Objects.requireNonNull(store);actor();
  java.util.function.BooleanSupplier controlReady=()->{
   var life=fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle();
   boolean observed=content!=null&&content.production()?scanned&&life.observationSnapshot(server).isPresent()&&life.runtimeMemoryView(server).isPresent():
    life.decisionSchedulerStatus(server).map(s->s.phase()==DirectorNativeDecisionScheduler.Phase.READY).orElse(false);
   return observed&&metadataLaneOwner==null&&(catalogueCoordinator==null||catalogueCoordinator.worldWriterIdle());
  };
  catalogueControl=content==null?new DirectorNativeCatalogueControl(server,world,store,controlReady):new DirectorNativeCatalogueControl(server,world,store,content,controlReady);
  goats=content==null?new DirectorNativeGoatGameplay(server,world,store):new DirectorNativeGoatGameplay(server,world,store,content);
  perceptions=content==null?new DirectorNativePerceptionGameplay(server,world,store):new DirectorNativePerceptionGameplay(server,world,store,content);
 var chainDriver=new DirectorNativeChainGameplay.Driver(){
  public boolean available(){return selectedChainDriver!=null&&selectedChainDriver.available();}
  public boolean supports(DefinitionId definition){return selectedChainDriver!=null&&selectedChainDriver.supports(definition);}
  public DirectorNativeChainGameplay.Launch start(ServerPlayer player,DefinitionId definition,UUID event,RegionId region,ChainId chain){
   return selectedChainDriver==null?new DirectorNativeChainGameplay.Launch(false,"CATALOGUE_DRIVER_NOT_BOUND"):selectedChainDriver.start(player,definition,event,region,chain);
  }
  public Optional<DirectorNativeChainGameplay.Launch> pendingStart(UUID event){return selectedChainDriver==null?Optional.empty():selectedChainDriver.pendingStart(event);}
  public void cancelPending(UUID event,String reason){if(selectedChainDriver!=null)selectedChainDriver.cancelPending(event,reason);}
  public boolean acquireMemoryLane(Object owner){return DirectorNativeGameplay.this.acquireMetadataLane(owner);}
  public void releaseMemoryLane(Object owner){DirectorNativeGameplay.this.releaseMetadataLane(owner);}
 };
 chains=content==null?new DirectorNativeChainGameplay(server,world,store,chainDriver):new DirectorNativeChainGameplay(server,world,store,chainDriver,content);
 perceptions.chainCompletion(chains);
  landslides=production()?null:new DirectorNativeLandslideGameplay(server,world,store,attached,owners);
  disasters=content==null?new DirectorNativeTerrainDisasterGameplay(server,world,store,attached,owners):new DirectorNativeTerrainDisasterGameplay(server,world,store,attached,owners,content);
  floods=production()?null:new DirectorNativeFloodDisasterGameplay(server,world,store,attached,owners);
  fires=production()?null:new DirectorNativeFireDisasterGameplay(server,world,store,attached,owners);
  fauna=content==null?new DirectorNativeFaunaMigrationGameplay(server,world,store):new DirectorNativeFaunaMigrationGameplay(server,world,store,content);
 choices=content==null?new DirectorNativeChoiceGameplay(server,world,store,
 ()->reservationFloor,value->reservationFloor=Math.max(reservationFloor,value)):new DirectorNativeChoiceGameplay(server,world,store,
 ()->reservationFloor,value->reservationFloor=Math.max(reservationFloor,value),content);
  goats.useSharedRecoveryScan();perceptions.useSharedRecoveryScan();if(landslides!=null)landslides.useSharedRecoveryScan();disasters.useSharedRecoveryScan();if(floods!=null)floods.useSharedRecoveryScan();if(fires!=null)fires.useSharedRecoveryScan();fauna.useSharedRecoveryScan();
  catalogueConfig=content==null?DirectorLabObservationProfile.loadRuntime():content.config();
  var catalogueHost=new DirectorNativeCatalogueCoordinator.Host(){
    public boolean ready(){return scanned&&!failed&&traces!=null;}
    public boolean enabled(){return catalogueControl.enabled()&&(physicalCatalogues==null||physicalCatalogues.ready());}
    public boolean transitionPending(){return catalogueControl.transitionPending();}
    public boolean metadataBusy(){return metadataLaneOwner!=null;}
    public boolean metadataHeldBy(Object owner){return metadataLaneHeldBy(owner);}
    public boolean acquireMetadataLane(Object owner){return DirectorNativeGameplay.this.acquireMetadataLane(owner);}
    public void releaseMetadataLane(Object owner){DirectorNativeGameplay.this.releaseMetadataLane(owner);}
    public Optional<BudgetView> budget(){return currentCatalogueBudget();}
    public Optional<Map<RegionId,Set<TagId>>> authoredRegionTags(){return importedRegionTags();}
    public String control(ServerPlayer player,boolean run,CatalogSnapshot catalog,boolean ready){return catalogueControl.request(player,run,catalog,ready);}
    public void bindPhysicalSites(DirectorNativeCatalogueExecution executor){if(physicalCatalogues!=null)physicalCatalogues.bindExecutor(executor);}
    public void bindChainDriver(DirectorNativeChainGameplay.Driver driver){DirectorNativeGameplay.this.bindChainDriver(driver);}
    public void bindSelectedContext(java.util.function.Supplier<Optional<DecisionInput>> input){selectedGrowthContext(input);}
   };
  java.util.function.Supplier<Map<DefinitionId,DirectorNativeCatalogueExecution.Driver>> driverFactory=()->{
   var drivers=DirectorNativeCatalogueDrivers.create(server,this,perceptions,goats,fauna,disasters,traces,choices);
   if(production()&&content.catalog().events().values().stream().anyMatch(e->e.steps().stream().anyMatch(step->step.args() instanceof GlobalWeatherLease)))drivers=ensureStorm().drivers(drivers);
   return regionalWeather==null?drivers:regionalWeather.drivers(drivers);
  };
  catalogueCoordinator=content==null?new DirectorNativeCatalogueCoordinator(server,world,store,catalogueConfig,catalogueHost,driverFactory):new DirectorNativeCatalogueCoordinator(server,world,store,content,catalogueHost,driverFactory);
  if(production()) chains.productionInput(catalogueCoordinator::currentInput);
  f8Control=production()?null:new DirectorNativeF8Control(server,store,catalogueControl,catalogueCoordinator::adminResumeReady);
 }
 public void install(IEventBus bus){
  actor();if(hooks!=null)throw new IllegalStateException("hooks already installed");
  weatherEventBus=Objects.requireNonNull(bus);if(weatherObserver!=null)weatherObserver.register(bus);
  waterHooks=new ControlledWaterOwnershipHooks(server,attached,this::now,next->{
   owners.put(next.resourceKey(),next);hookAcks.add(append(List.of(new DurableOwnership(next))));
  },bus);
  hooks=new PlayerOwnershipChangeHooks(server,attached,this::now,next->{
   owners.put(next.resourceKey(),next);hookAcks.add(append(List.of(new DurableOwnership(next))));
  },bus);
 }
 /** The global weather observer belongs only to a coordinator that actually uses/reconciles weather. */
 private DirectorNativeStormGameplay ensureStorm(){
  actor();if(storms!=null)return storms;
  var observer=new WeatherOverrideObserver(server,store.session().bootEpoch());
  try{
   if(weatherEventBus!=null)observer.register(weatherEventBus);
   var driver=production()?new DirectorNativeStormGameplay(server,world,store,owners,observer,content):new DirectorNativeStormGameplay(server,world,store,owners,observer,
    args->new DirectorLabStormCatalog(DirectorLabObservationProfile.load(),args).snapshot());
   // Its existing bounded J04 scan recovers the real reservation floor and weather lineage.
   // A late-created driver must never be marked scanned from an invented empty history.
   weatherObserver=observer;storms=driver;return driver;
  }catch(RuntimeException failure){observer.close();throw failure;}
 }
 private void observeWeatherRecovery(DurableRecord row){
  if(regionalRecoveryRouting==null)regionalRecoveryRouting=new WeatherRecoveryRouting(world);
  regionalRecoveryRouting.observe(row);

  if(row instanceof DurableFact fact&&fact.value().eventId().isPresent()){
   var id=fact.value().eventId().orElseThrow().value();var type=fact.value().factType().value();
   if(type.equals("proutlost_director:lab_storm_start"))pendingStormRecovery.add(id);
   else if(type.equals("proutlost_director:lab_storm_end"))pendingStormRecovery.remove(id);
  }else if(row instanceof DurablePrepare prepare&&prepare.value().slice().operations().stream()
      .anyMatch(i->i.primitive().equals(new PrimitiveId("proutlost:global_weather_lease"))))
   unresolvedWeatherRecovery.add(prepare.value().operationToken());
  else if(row instanceof DurableResult result)unresolvedWeatherRecovery.remove(result.value().operationToken());
  if(pendingStormRecovery.size()>64||unresolvedWeatherRecovery.size()>128)throw new IllegalStateException("weather recovery bound exceeded");
 }
 private void openRequiredWeatherRecovery(){
  boolean required=!pendingStormRecovery.isEmpty()||!unresolvedWeatherRecovery.isEmpty()||owners.values().stream()
   .anyMatch(owner->owner.resourceKey().domain()==OwnershipDomain.WEATHER&&owner.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE&&(regionalRecoveryRouting==null||!regionalRecoveryRouting.regional(owner)));
  if(!required)return;
  ensureStorm(); // The existing driver validates selected production provenance; historical LAB dossiers remain refused.
 }
 private boolean production(){return content!=null&&content.production();}
 private ProviderMode providerMode(){return content==null?ProviderMode.TEST_ONLY:content.mode();}
 private void requireLaboratory(){if(content!=null)content.requireLaboratory();}
 private void requireSelected(SelectedEventStart selected){
  Objects.requireNonNull(selected);
  if(selected.mode()!=providerMode()||(content!=null&&!selected.definition().equals(content.definition(selected.definition().id()))))
   throw new IllegalArgumentException("selected runtime content/mode mismatch");
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("LAB actor");}
 private Tick now(){return new Tick(production()?fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server):server.overworld().getGameTime());}
 private ServerLevel level(BlockPosition p){
  for(var l:server.getAllLevels())if(l.dimension().location().toString().equals(p.dimension().value()))return l;
  throw new IllegalStateException("dimension unavailable");
 }
 private static BlockPos pos(BlockPosition p){return new BlockPos(p.x(),p.y(),p.z());}
 private static ResourceKey key(BlockPosition p){return new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z());}
 private boolean loaded(BlockPosition p){return level(p).getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)!=null;}
 private java.util.function.Supplier<Optional<DecisionInput>> selectedGrowthInput=Optional::empty;
 public void selectedGrowthContext(java.util.function.Supplier<Optional<DecisionInput>> source){actor();selectedGrowthInput=Objects.requireNonNull(source);}
 public NativeSelectedGrowthHabitat.Survey previewGrowth(Candidate candidate,DecisionInput input){actor();return NativeSelectedGrowthHabitat.preview(server,candidate,input);}
 public boolean selectedGrowthAvailable(){actor();return scanned&&!failed&&!store.readOnlySafe()&&flight==null&&dossiers.isEmpty()&&!physicalDisasterBusy();}
 public Optional<UUID> activeGrowthEvent(){actor();return flight==null?Optional.empty():Optional.of(flight.id);}
 private EventDefinition growthDefinition(Flight f){return f.selectedDefinition==null?manualGrowthDefinition():f.selectedDefinition;}
 private Tick selectedGrowthTick(){return new Tick(fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server));}
 private boolean selectedGrowthExpired(Flight f){return f.selectedPlan!=null&&selectedGrowthTick().value()>=f.selectedDeadline;}
 private long selectedGrowthRemaining(Flight f){return f.selectedPlan==null?Long.MAX_VALUE:selectedGrowthTick().value()>=f.selectedDeadline?0:f.selectedDeadline-selectedGrowthTick().value();}
 private CatalogueEventLedger.Selection growthSelection(Flight f){return new CatalogueEventLedger.Selection(f.selectedDefinition,f.selectedPlan);}
 private boolean selectedGrowthCampLive(Flight f){
  if(f.selectedPlan==null)return true;var session=store.session();
  var query=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.ANCHOR,f.selectedCamp.anchorId().value())),new Sequence(0),Optional.empty(),1);
  var read=store.view(query);ReadContracts.validate(read,query,session);
  return read.status()==ReadStatus.AVAILABLE&&session.equals(store.session())&&read.page().orElseThrow().aggregates().stream()
      .anyMatch(a->a.state() instanceof AnchorAggregate anchor&&anchor.value().equals(f.selectedCamp));
 }
 private DirectorFact growthDossier(Flight f){
  var values=new TreeMap<String,JsonValue>();
  values.put("plan",new JsonValue.Text(Base64.getEncoder().encodeToString(PLAN.encode(f.forward))));
  values.put("owner",new JsonValue.Text(Base64.getEncoder().encodeToString(OWNER.encode(f.admitted))));
  values.put("original",new JsonValue.Text(f.original.value()));values.put("player",new JsonValue.Text(f.player.toString()));
  if(f.selectedPlan!=null){
   values.put("selected_plan",new JsonValue.Text(Base64.getEncoder().encodeToString(new ModelCodec<>(ExecutionPlan.class).encode(f.selectedPlan))));
   values.put("selected_definition",new JsonValue.Text(Base64.getEncoder().encodeToString(new ModelCodec<>(EventDefinition.class).encode(f.selectedDefinition))));
   values.put("selected_decision",new JsonValue.Text(f.selectedDecision.value().toString()));
   values.put("selected_deadline",new JsonValue.Text(Long.toString(f.selectedDeadline)));
   NativeCatalogueColdCompletion.pin(Objects.requireNonNull(f.coldIdentity),values);
   values.put("selected_camp",new JsonValue.Text(Base64.getEncoder().encodeToString(new ModelCodec<>(AnchorRecord.class).encode(f.selectedCamp))));
   f.parentChain.ifPresent(id->values.put("parent_chain",new JsonValue.Text(id.value().toString())));
  }
  return fact(DOSSIER,f.id,values);
 }
 /** Original one-step camp_regrowth composition; never calls the manual pick-based begin(). */
 public String beginSelected(SelectedEventStart selected){requireSelected(selected);
  actor();Objects.requireNonNull(selected);
  if(!scanned||failed||store.readOnlySafe()||flight!=null||!dossiers.isEmpty()||physicalDisasterBusy())
   throw new IllegalStateException("growth selected lane unavailable");
  var current=selected.refresh();var plan=selected.plan();
  if(!plan.scopes().worldId().equals(world)||!plan.definition().id().equals(new DefinitionId("proutlost_director:candidate/camp_regrowth"))
      ||plan.effects().size()!=1||!plan.effects().getFirst().primitive().equals(GROWTH)
      ||!(plan.effects().getFirst().typedArgs() instanceof CampGrowth args)||args.maxBlocks()<1
      ||plan.effects().getFirst().expectedFootprint().cells().size()!=1)
   throw new IllegalArgumentException("exact original growth selection required");
  var ids=plan.roles().roles().getOrDefault("witness",List.of());
  if(ids.size()!=1)throw new IllegalArgumentException("one real selected growth witness required");
  var player=server.getPlayerList().getPlayer(ids.getFirst().value());
  if(player==null||!player.isAlive())throw new IllegalArgumentException("selected growth witness unavailable");
  var p=plan.effects().getFirst().expectedFootprint().cells().iterator().next();
  if(!NativeSelectedGrowthHabitat.allowed(server,p)||player.serverLevel()!=level(p)
      ||player.distanceToSqr(p.x()+.5,p.y()+.5,p.z()+.5)>256)throw new IllegalArgumentException("real selected growth cell unavailable");
  var f=new Flight();f.id=selected.eventId().value();f.player=player.getUUID();f.position=p;f.key=key(p);
  if(dossiers.containsKey(f.id)||completed.contains(f.id))throw new IllegalArgumentException("selected growth already consumed");
  f.selection=selected;f.selectedPlan=plan;f.selectedDefinition=selected.definition();f.selectedDecision=selected.proposal().proposalId();
  f.selectedDeadline=Math.addExact(selectedGrowthTick().value(),selected.definition().timing().maxDurationTicks().value());
  f.parentChain=selected.parentChain();f.selectedCamp=NativeSelectedGrowthHabitat.camp(plan,current.context());
  f.coldIdentity=NativeCatalogueColdCompletion.beforeApply(selected,current);
  if(!selectedGrowthCampLive(f))throw new IllegalArgumentException("selected abandoned camp changed");
  var previous=attached.read(level(p),pos(p));var state=level(p).getBlockState(pos(p));
  f.warning=new NativeWarningBinding(server,new PlayerId(f.player),new PerceptionTransform(p.x()+.5,p.y()+.8,p.z()+.5,
      net.minecraft.util.Mth.wrapDegrees(180-player.getYRot()),0,2),1200);
  f.original=new ResourceId(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
  f.admitted=new OwnershipRecord(new AggregateHeader(1,previous.map(o->o.header().id()).orElseGet(UUID::randomUUID),
      previous.map(o->o.header().revision().plus(1)).orElse(new Revision(1)),new Sequence(0),now()),
      f.key,Set.of(new OwnerToken(UUID.randomUUID())),previous.map(o->o.epoch().plus(1)).orElse(new Epoch(1)),
      OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),hash(p),
      previous.map(OwnershipRecord::playerOverrideEpoch).orElse(new Epoch(0)),Optional.empty());
  // Native capture is pure; publish the initial attachment only after exact selected capture succeeds.
  owners.put(f.key,f.admitted);
  try{f.captured=capture(f,false);f.forward=f.captured.prepared().orElseThrow();}
  catch(RuntimeException refused){if(previous.isPresent())owners.put(f.key,previous.get());else owners.remove(f.key);throw refused;}
  attached.write(level(p),pos(p),f.admitted);
  var dossier=growthDossier(f);f.ack=append(List.of(new DurableOwnership(f.admitted),new DurableFact(dossier)));
  flight=f;return "Camp regrowth catalogue "+f.id+"; mutation native "+f.admitted.creationMutationId().value()+".";
 }
 private Fingerprint hash(BlockPosition p){return BlockTransformBinding.stateBlob(level(p).getBlockState(pos(p))).reference().hash();}
 private CompletableFuture<CommitAck> append(List<DurableRecord> rows){
  return store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),
   store.session().storeGeneration(),Map.of(),rows,List.of())).toCompletableFuture();
 }
 private boolean durable(CompletableFuture<CommitAck> ack){
  if(!ack.isDone())return false;
  if(ack.isCompletedExceptionally()||ack.getNow(null).ackStatus()!=AckStatus.DURABLE)
   throw new IllegalStateException("LAB durable ACK unavailable");
  return true;
 }
 private DirectorFact fact(FactType type,UUID id,Map<String,JsonValue> payload){
  return new DirectorFact(new FactId(UUID.randomUUID()),type,
   List.of(new ScopeRef(ScopeKind.WORLD,world.value())),Optional.of(new EventInstanceId(id)),
   EvidenceStatus.CONFIRMED,payload,true,Optional.empty());
 }
 public String begin(ServerPlayer player){return begin(player,UUID.randomUUID());}
 public String begin(ServerPlayer player,UUID requestedEvent){requireLaboratory();
  actor();if(!scanned||failed||store.readOnlySafe())return "Laboratoire en attente / WAL indisponible.";
  if(flight!=null||!dossiers.isEmpty()||physicalDisasterBusy())return "Un événement de laboratoire attend sa fin ou sa récupération.";
  if(!(player.pick(6,0,false) instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK)
   return "Vise un bloc de pierre, terre ou cobblestone à moins de six blocs.";
  var l=player.serverLevel();var p=hit.getBlockPos();var state=l.getBlockState(p);
  if(!(state.is(Blocks.STONE)||state.is(Blocks.DIRT)||state.is(Blocks.COBBLESTONE)))
   return "Ce laboratoire accepte uniquement pierre, terre et cobblestone.";
  var dimension=new DimensionId(l.dimension().location().toString());
  var position=new BlockPosition(dimension,p.getX(),p.getY(),p.getZ());
  var previous=attached.read(l,p);
  if(previous.isPresent()&&previous.get().disposition()!=OwnershipDisposition.RELEASED)
   return "Ce bloc possède déjà une provenance active ou cédée.";
  // Refuse before reserving anything if this actual client cannot render the warning.
  var transform=new PerceptionTransform(p.getX()+.5,p.getY()+.8,p.getZ()+.5,net.minecraft.util.Mth.wrapDegrees(180-player.getYRot()),0,2);
  var warning=new NativeWarningBinding(server,new PlayerId(player.getUUID()),transform,1200);
  Flight f=new Flight();f.id=Objects.requireNonNull(requestedEvent);f.player=player.getUUID();f.position=position;f.key=key(position);
  f.warning=warning;f.original=new ResourceId(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
  var epoch=previous.map(o->o.epoch().plus(1)).orElse(new Epoch(1));
  f.admitted=new OwnershipRecord(new AggregateHeader(1,previous.map(o->o.header().id()).orElseGet(UUID::randomUUID),
   previous.map(o->o.header().revision().plus(1)).orElse(new Revision(1)),new Sequence(0),now()),
   f.key,Set.of(new OwnerToken(UUID.randomUUID())),epoch,OwnershipDisposition.DIRECTOR_ACTIVE,
   new MutationId(UUID.randomUUID()),hash(position),previous.map(OwnershipRecord::playerOverrideEpoch).orElse(new Epoch(0)),Optional.empty());
  owners.put(f.key,f.admitted);attached.write(l,p,f.admitted);
  f.captured=capture(f,false);f.forward=f.captured.prepared().orElseThrow();
  var dossier=fact(DOSSIER,f.id,Map.of(
   "plan",new JsonValue.Text(Base64.getEncoder().encodeToString(PLAN.encode(f.forward))),
   "owner",new JsonValue.Text(Base64.getEncoder().encodeToString(OWNER.encode(f.admitted))),
   "original",new JsonValue.Text(f.original.value()),"player",new JsonValue.Text(f.player.toString())));
  f.ack=append(List.of(new DurableOwnership(f.admitted),new DurableFact(dossier)));
  flight=f;System.out.println("DIRECTOR_LAB_GROWTH_DOSSIER_SUBMITTED "+f.id);
  return "LAB : avertissement puis mousse temporaire ; retour à l'état initial après 20 s. Event="+f.id+" Mutation="+f.admitted.creationMutationId().value()+" (consultable apres impact).";
 }
 private BlockTransformBinding binding(Flight f,boolean inverse){
  var from=inverse?new ResourceId("minecraft:moss_block"):f.original;
  var to=inverse?f.original:new ResourceId("minecraft:moss_block");
  return new BlockTransformBinding(d->level(f.position),()->new OwnershipView(owners),
   (p,next)->{owners.put(next.resourceKey(),next);attached.write(level(p),pos(p),next);},
   this::now,Map.of(f.selectedPlan==null?RECIPE:((CampGrowth)f.selectedPlan.effects().getFirst().typedArgs()).recipe(),new BlockTransformBinding.Recipe(Map.of(from,to),false)),1);
 }
 private PlanningSnapshot snapshot(Flight f){
  var p=f.position;var owner=Objects.requireNonNull(owners.get(f.key));
  var footprint=new Footprint(p.dimension(),Set.of(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4)),Set.of(p),List.of());
  var cell=new TerrainCellSnapshot(p,hash(p),BlockTransformBinding.STATE_CODEC,Set.of(),Set.of(),
   false,true,owner.epoch(),owner.playerOverrideEpoch());
  var terrain=new TerrainSnapshot(footprint,Map.of(p,cell),List.of(),Map.of(),new Revision(1));
  if(f.selection!=null&&(f.phase==0||f.phase==1)){
   var selected=SelectedNativeSnapshot.terrain(f.selection.refresh(),terrain);
   // The pure native capture precedes START/ownership ACK. Preserve the real J01/J04 frame
   // and add only this flight's actual admission, which START journals before any PREPARE.
   var admitted=new HashMap<>(selected.ownership().records());admitted.put(f.key,owner);
   return new PlanningSnapshot(selected.snapshot(),new OwnershipView(admitted),selected.budgets(),selected.blobs(),selected.bindings());
  }
  // Actual native terrain reads; no synthetic player/camp eligibility or invented WORLD revision.
  var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(terrain),Map.of());
  var sample=new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),now(),Map.of(),
   context,Set.of(),footprint.loadedChunks(),HealthMode.NORMAL);
  return new PlanningSnapshot(sample,new OwnershipView(owners),new BudgetView(CAPACITY,BudgetMath.zero(),Map.of()),
   new BlobBundle(List.of()));
 }
 private EffectPreparation capture(Flight f,boolean inverse){
  if(!inverse&&f.selection!=null){f.selection.refresh();if(!selectedGrowthCampLive(f))throw new IllegalStateException("selected camp changed");}
  var context=snapshot(f);
  var intent=!inverse&&f.selectedPlan!=null?f.selectedPlan.effects().getFirst():new EffectIntent(new OperationToken(UUID.randomUUID()),GROWTH,1,
   (CampGrowth)growthDefinition(f).steps().getFirst().args(),
   context.snapshot().context().terrain().getFirst().loadedFootprint(),new EventInstanceId(f.id));
  return binding(f,inverse).capture(intent,context);
 }
 private ReservationToken admitManualGrowth(Flight f,Execution e,EffectPreparation captured,ServerEffectPort.Binding physical){
  var prepared=captured.prepared().orElseThrow();var intent=prepared.intent();
  var definition=growthDefinition(f);var step=definition.steps().getFirst();
  var playerId=new PlayerId(f.player);var roles=f.selectedPlan==null?new RoleAssignments(Map.of("witness",List.of(playerId))):f.selectedPlan.roles();
  var worldScope=new ScopeRef(ScopeKind.WORLD,world.value());
  var scopes=f.selectedPlan==null?new ScopeSet(world,Set.of(worldScope,new ScopeRef(ScopeKind.PLAYER,playerId.value()))):f.selectedPlan.scopes();
  var reference=new DefinitionRef(definition.id(),definition.contentRevision(),
   BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(definition)));
  var execution=new ExecutionPlan(f.selectedPlan==null?new CandidateId(UUID.randomUUID()):f.selectedPlan.candidateId(),reference,List.of(intent),roles,scopes,prepared.preconditions());
  var request=new AdmissionRequest(f.selectedDecision==null?new DecisionId(UUID.randomUUID()):f.selectedDecision,prepared.preconditions(),prepared.claims(),roles,execution);
  var validator=new AdmissionService.HardValidator(){
   public ProviderMode mode(){return providerMode();}
   public AdmissionService.Assessment assess(AdmissionRequest proposed,Tick tick){
    var player=server.getPlayerList().getPlayer(f.player);
    boolean valid=proposed.equals(request)&&player!=null&&(f.selectedPlan!=null||player.hasPermissions(4))&&player.isAlive()&&selectedGrowthCampLive(f)
     &&player.serverLevel()==level(f.position)
     &&player.distanceToSqr(f.position.x()+.5,f.position.y()+.5,f.position.z()+.5)<=256
     &&!store.readOnlySafe()&&loaded(f.position)
     &&intent.primitive().equals(step.primitive())&&intent.typedArgs().equals(step.args())
     &&intent.expectedFootprint().cells().equals(Set.of(f.position))
     &&prepared.claims().size()==1&&physical.revalidate(prepared,captured.blobs())&&f.warning.received()
     &&DirectorNativeNetworkBootstrap.qualifiedRecipient(server,playerId,PerceptionKind.SYMBOL,
       DirectorGameplayAssets.WARNING,DirectorGameplayAssets.WARNING_HASH).isPresent();
    boolean budgetFits=f.selection==null||BudgetMath.fits(prepared.budgetEstimate(),SelectedEventAdmission.available(f.selection));
    valid=valid&&budgetFits;
    return new AdmissionService.Assessment(new ValidationResult(valid,
      valid?List.of():List.of(budgetFits?ReasonCode.STALE_CONTEXT:ReasonCode.BUDGET_EXHAUSTED),prepared.preconditions()),prepared.claims(),
      new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1.0,42),
      Map.of(worldScope,prepared.budgetEstimate()));
   }
  };
  e.admission=new AdmissionService(()->f.selection==null?manualGrowthCatalogue():f.selection.captured().catalog(),validator,e.arbiter,this::now,
   new DurationTicks(Math.max(1,Math.min(1200,selectedGrowthRemaining(f)))),4,providerMode());e.proposal=request.proposalId();
  var first=content==null?e.admission.admit(request):content.admit(e.admission,request);
  if(first.status()!=AdmissionStatus.DEFERRED)throw new IllegalStateException("manual J03 queue refused: "+first);
  var epochs=new HashMap<ResourceKey,Epoch>();prepared.claims().forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
  var answer=e.admission.resolveWindow(epochs,Map.of(worldScope,f.selection==null?CAPACITY:SelectedEventAdmission.available(f.selection))).get(request.proposalId());
  if(answer==null||answer.status()!=AdmissionStatus.ACCEPTED)throw new IllegalStateException("manual J03 admission refused: "+answer);
  System.out.println("DIRECTOR_LAB_J02_J03_MANUAL_ADMISSION_ACCEPTED definition="+definition.id().value()+
    " definition_hash="+reference.hash().value()+" decision="+request.proposalId().value()+
    " automatic_eligibility=false calibration="+definition.calibration());
  return answer.token().orElseThrow();
 }
 /** Data-only journal rows for a real, already-returned single-cell block RESULT. No execution authority. */
 static List<DurableRecord> blockResultRecords(PreparedEffect plan,PrepareRecord prepare,OwnershipRecord original,
   OwnershipRecord actualOwner,Optional<MutationRecord> previous,SliceResult actual,boolean inverse,Tick tick){
  var intent=plan.intent();var k=original.resourceKey();var id=original.creationMutationId();
  var rows=new ArrayList<DurableRecord>();
  rows.add(new DurableResult(new ResultRecord(prepare.transactionId(),intent.operationToken(),actual,Set.of(),List.of(),List.of())));
  boolean nativeOwnerPost=actualOwner!=null&&actualOwner.header().id().equals(original.header().id())
   &&actualOwner.header().revision().value()==original.header().revision().value()+1
   &&actualOwner.resourceKey().equals(k)&&actualOwner.creationMutationId().equals(id)
   &&actualOwner.ownerTokens().equals(original.ownerTokens())&&actualOwner.epoch().equals(original.epoch())
   &&actualOwner.playerOverrideEpoch().equals(original.playerOverrideEpoch())
   &&actual.appliedOperationIds().contains(intent.operationToken())
   &&actualOwner.expectedPostHash().equals(actual.observedHashes().get(k));
  if(nativeOwnerPost)rows.add(new DurableOwnership(actualOwner));
  // An unchanged PRE owner is already durable. Never duplicate its revision to preserve a failed RESULT.
  if(actual.outcome()!=Outcome.SUCCEEDED||actual.remainingWork()!=0||!actual.conflictCells().isEmpty())return List.copyOf(rows);
  if(!nativeOwnerPost||!intent.primitive().equals(GROWTH)||intent.version()!=1||!prepare.slice().operations().equals(List.of(intent))
    ||!prepare.operationToken().equals(intent.operationToken())||!prepare.durableBlobs().equals(plan.durableBlobs())
    ||actual.outcome()!=Outcome.SUCCEEDED||actual.remainingWork()!=0||!actual.conflictCells().isEmpty()
    ||!actual.appliedOperationIds().equals(Set.of(intent.operationToken()))||!actual.observedHashes().equals(plan.expectedPost())
    ||!plan.expectedPost().keySet().equals(Set.of(k))||original.ownerTokens().size()!=1
    ||!actualOwner.header().id().equals(original.header().id())||!actualOwner.resourceKey().equals(k)
    ||!actualOwner.creationMutationId().equals(id)||!actualOwner.ownerTokens().equals(original.ownerTokens())
    ||!actualOwner.epoch().equals(original.epoch())||!actualOwner.playerOverrideEpoch().equals(original.playerOverrideEpoch())
    ||!actualOwner.expectedPostHash().equals(actual.observedHashes().get(k)))throw new IllegalArgumentException("actual growth RESULT/ownership mismatch");
  // Historical runs without an original record cannot be retroactively promoted from an inverse.
  // Their real cleanup RESULT/owner remain durable; no invented original mutation or SUCCESS.
  if(inverse&&previous.isEmpty())return List.copyOf(rows);
  rows.set(0,new DurableResult(new ResultRecord(prepare.transactionId(),intent.operationToken(),actual,Set.of(id),List.of(),List.of())));
  var evidence=List.of(new RecoveryEvidence(k,EvidenceStatus.CONFIRMED,Optional.of(actual.observedHashes().get(k)),
    Optional.of(original.ownerTokens().iterator().next()),original.epoch(),actualOwner.playerOverrideEpoch(),false));
  MutationRecord mutation;
  if(inverse){
   var old=previous.orElseThrow();var h=old.header();
   if(!old.mutationId().equals(id)||!h.id().equals(id.value())||!old.instanceId().equals(intent.instanceId())
     ||!old.ownerToken().equals(original.ownerTokens().iterator().next())||!old.ownerEpoch().equals(original.epoch())
     ||!old.primitiveId().equals(GROWTH)||!old.noninvertibleEffects().isEmpty()
     ||!plan.durableBlobs().preStates().get(k).hash().equals(old.postStateRef().hash())
     ||!plan.durableBlobs().expectedPostStates().get(k).hash().equals(old.preStateRef().hash()))
    throw new IllegalArgumentException("growth inverse/original mutation mismatch");
   mutation=new MutationRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),h.lastSequence(),tick),
    id,old.instanceId(),old.sliceId(),MutationState.ROLLED_BACK,old.primitiveId(),old.primitiveVersion(),old.footprint(),
    old.preStateRef(),old.postStateRef(),old.ownerToken(),old.ownerEpoch(),old.readSetVersions(),old.noninvertibleEffects(),
    evidence,old.compensationRef(),new Sequence(0));
  }else{
   if(previous.isPresent())throw new IllegalArgumentException("growth creation mutation already exists");
   mutation=new MutationRecord(new AggregateHeader(1,id.value(),new Revision(1),new Sequence(0),tick),
    id,intent.instanceId(),prepare.slice().sliceId(),MutationState.VERIFIED,intent.primitive(),intent.version(),intent.expectedFootprint(),
    Objects.requireNonNull(plan.durableBlobs().preStates().get(k)),Objects.requireNonNull(plan.durableBlobs().expectedPostStates().get(k)),
    original.ownerTokens().iterator().next(),original.epoch(),plan.preconditions(),Set.of(),evidence,Optional.empty(),new Sequence(0));
  }
  rows.add(new DurableMutation(mutation));return List.copyOf(rows);
 }
 /** physicalCommit is the real RestoredAggregate.committedSequence from an explicit MUTATION query. */
 static MutationRecord committedMutation(MutationRecord old,Sequence physicalCommit,Tick tick){
  if(physicalCommit.value()<=0)throw new IllegalArgumentException("real physical mutation commit sequence required");
  var h=old.header();return new MutationRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),physicalCommit,tick),
   old.mutationId(),old.instanceId(),old.sliceId(),old.state()==MutationState.VERIFIED?MutationState.COMMITTED:old.state(),
   old.primitiveId(),old.primitiveVersion(),old.footprint(),old.preStateRef(),old.postStateRef(),old.ownerToken(),old.ownerEpoch(),
   old.readSetVersions(),old.noninvertibleEffects(),old.reconciliationEvidence(),old.compensationRef(),physicalCommit);
 }
 private boolean finalizeMutation(Execution e){
  if(e.mutation==null||e.mutationFinalized)return true;
  if(e.mutationAck!=null){
   if(!e.mutationAck.isDone())return false;var ack=e.mutationAck.getNow(null);
   if(ack==null||ack.ackStatus()!=AckStatus.DURABLE||!e.mutationSession.equals(store.session())
     ||!ack.transactionId().equals(e.mutationBatch.transactionId())||!ack.transactionHash().equals(e.mutationBatch.transactionHash())
     ||!ack.storeGeneration().equals(e.mutationSession.storeGeneration())||ack.durableSequence().value()<=0
     ||ack.permitAttempt().isPresent()||!ack.forcedBlobs().equals(DurableBlobReferences.collect(e.mutationBatch.records())))
    throw new IllegalStateException("growth mutation metadata ACK mismatch");
   growthMutations.put(e.mutationCommitted.mutationId(),e.mutationCommitted);e.mutationFinalized=true;return true;
  }
  if(e.mutationRead==null){
   e.mutationSession=store.session();e.mutationQuery=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.MUTATION,e.mutation.mutationId().value())),new Sequence(0),Optional.empty(),1);
   e.mutationRead=store.read(e.mutationQuery).toCompletableFuture();return false;
  }
  if(!e.mutationRead.isDone())return false;var read=e.mutationRead.getNow(null);ReadContracts.validate(read,e.mutationQuery,e.mutationSession);
  if(!e.mutationSession.equals(store.session())||read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().hasMore()
    ||read.page().orElseThrow().aggregates().size()!=1)throw new IllegalStateException("growth mutation durable projection unavailable");
  var restored=read.page().orElseThrow().aggregates().getFirst();
  if(!(restored.state() instanceof MutationAggregate aggregate)||!aggregate.value().equals(e.mutation))
   throw new IllegalStateException("growth mutation actual committed row mismatch");
  e.mutationCommitted=committedMutation(aggregate.value(),restored.committedSequence(),now());
  e.mutationBatch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),e.mutationSession.storeGeneration(),Map.of(),List.of(new DurableMutation(e.mutationCommitted)),List.of());
  e.mutationAck=store.append(e.mutationBatch).toCompletableFuture();return false;
 }
 private boolean knownRolledBack(Flight f,OwnershipRecord current,Fingerprint observed,MutationRecord mutation){
  if(mutation==null||mutation.state()!=MutationState.ROLLED_BACK||current.disposition()!=OwnershipDisposition.DIRECTOR_ACTIVE)return false;
  var original=f.admitted;var pre=f.forward.durableBlobs().preStates().get(f.key);var post=f.forward.durableBlobs().expectedPostStates().get(f.key);
  if(!current.header().id().equals(original.header().id())||!current.resourceKey().equals(f.key)
    ||!current.ownerTokens().equals(original.ownerTokens())||!current.creationMutationId().equals(original.creationMutationId())
    ||!current.epoch().equals(original.epoch())||!current.playerOverrideEpoch().equals(original.playerOverrideEpoch())
    ||!observed.equals(pre.hash())||!current.expectedPostHash().equals(pre.hash())
    ||!mutation.header().id().equals(mutation.mutationId().value())||!mutation.mutationId().equals(original.creationMutationId())
    ||!mutation.instanceId().value().equals(f.id)||!mutation.primitiveId().equals(GROWTH)||mutation.primitiveVersion()!=1
    ||!mutation.footprint().equals(f.forward.intent().expectedFootprint())||!mutation.preStateRef().equals(pre)||!mutation.postStateRef().equals(post)
    ||!current.ownerTokens().equals(Set.of(mutation.ownerToken()))||!current.epoch().equals(mutation.ownerEpoch())
    ||mutation.reconciliationEvidence().size()!=1)return false;
  var evidence=mutation.reconciliationEvidence().getFirst();
  return evidence.resource().equals(f.key)&&evidence.status()==EvidenceStatus.CONFIRMED
   &&evidence.observedHash().equals(Optional.of(observed))&&evidence.ownerToken().equals(Optional.of(mutation.ownerToken()))
   &&evidence.ownerEpoch().equals(current.epoch())&&evidence.playerOverrideEpoch().equals(current.playerOverrideEpoch())
   &&!evidence.localAbsenceProven();
 }
 private Execution execute(Flight f,EffectPreparation captured,ServerEffectPort.Binding binding,boolean ownershipResult,boolean manualAdmission) {
  return execute(f,captured,binding,ownershipResult,manualAdmission,manualAdmission&&f.selection!=null?f.selection.refresh():null);
 }
 private Execution execute(Flight f,EffectPreparation captured,ServerEffectPort.Binding binding,boolean ownershipResult,boolean manualAdmission,DecisionInput selectedInput) {
  var plan=captured.prepared().orElseThrow();var intent=plan.intent();
  long leaseTicks=f.selectedPlan!=null&&(manualAdmission||intent.primitive().equals(new PrimitiveId("proutlost:warning")))?Math.min(1200,selectedGrowthRemaining(f)):1200;
  if(leaseTicks<1)throw new IllegalStateException("selected growth deadline reached before PREPARE");
  var priorOwner=ownershipResult?Objects.requireNonNull(owners.get(f.key)):null;
  var e=new Execution();e.originSession=store.session();if(binding instanceof NativeWarningBinding warning)e.perception=warning;e.plan=plan;e.retainClaim=manualAdmission;
  if(manualAdmission&&f.selection!=null)e.selectedInput=Objects.requireNonNull(selectedInput);
  e.arbiter=new ResourceArbiter(world,new Epoch(Math.max(reservationFloor,store.session().bootEpoch().value())),1000,4,new ResourceArbiter.Compatibility(){
   public boolean share(ResourceClaim a,ResourceClaim b){return false;}
   public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}
  });
  var scope=new ScopeRef(ScopeKind.WORLD,world.value());var epochs=new HashMap<ResourceKey,Epoch>();
  plan.claims().forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
  if(manualAdmission)e.token=admitManualGrowth(f,e,captured,binding);
  else {
  var candidate=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),intent.instanceId(),
   new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1.0,42),
   now().plus(leaseTicks),plan.claims(),Map.of(scope,plan.budgetEstimate()));
  e.token=e.arbiter.arbitrate(List.of(candidate),now(),epochs,Map.of(scope,CAPACITY))
   .getFirst().accepted().orElseThrow(()->new IllegalStateException("logical admission refused"));
  }
  reservationFloor=e.token.generation().value();
  var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),intent.operationToken(),
   intent.expectedFootprint(),plan.preconditions(),List.of(intent),plan.budgetEstimate());
  e.live=new LiveValidationSnapshot(plan.preconditions(),intent.expectedFootprint().loadedChunks(),HealthMode.NORMAL,now(),e.token.generation());
  var authority=new CommitPermit.Authority(store,4);
  var port=new ServerEffectPort(authority,i->{throw new IllegalStateException("already captured");},s->e.live,
   Map.of(intent.primitive(),binding),binding.capabilities(),4,
   s->new StabilizationLiveSnapshot(store.session(),e.live,new OwnershipView(owners)),m->{},(m,r)->{});
  port.hydrate(plan,captured.blobs());
  var prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),intent.operationToken(),e.token.generation(),
   plan.preconditions(),e.token.expiryTick(),slice,e.token,captured.validation(),e.selectedInput==null?List.of():CatalogueEventLedger.reserve(growthSelection(f),e.selectedInput,e.token,selectedGrowthTick()),Set.of(),List.of(),plan.durableBlobs());
  e.perceptionPrepare=prepare;e.tx=prepare.transactionId();e.runner=new EventRunner(authority,store,content==null?port:content.attach(plan,captured.blobs(),port,f.selection!=null&&(manualAdmission||intent.primitive().equals(new PrimitiveId("proutlost:warning")))),e.arbiter,4);
  var batch=new DurableBatch(e.tx,store.session().storeGeneration(),prepare.readSet().revisions(),
   List.of(new DurablePrepare(prepare)),captured.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
  e.runner.submit(batch,plan.expectedPost(),e.live,actual->{
   e.actual=actual;var rows=new ArrayList<DurableRecord>();var selectedBlobs=new ArrayList<BlobContent>();
   if(ownershipResult){
    rows.addAll(blockResultRecords(plan,prepare,priorOwner,Objects.requireNonNull(owners.get(f.key)),
      Optional.ofNullable(growthMutations.get(f.admitted.creationMutationId())),actual,!manualAdmission,now()));
    for(var row:rows)if(row instanceof DurableMutation m){e.mutation=m.value();growthMutations.put(m.value().mutationId(),m.value());}
    if(!manualAdmission&&e.mutation==null)System.out.println("DIRECTOR_LAB_GROWTH_LEGACY_INVERSE_WITHOUT_ORIGINAL_MUTATION event="+f.id+" mutation="+f.admitted.creationMutationId().value());
   }else rows.add(new DurableResult(new ResultRecord(e.tx,intent.operationToken(),actual,Set.of(),List.of(),List.of())));
   if(e.selectedInput!=null){var ledger=CatalogueEventLedger.effect(growthSelection(f),e.selectedInput,prepare,actual,selectedGrowthTick());rows.addAll(ledger.records());selectedBlobs.addAll(ledger.blobs());}
   if(f.selectedPlan==null&&manualAdmission&&e.mutation!=null&&e.mutation.state()==MutationState.VERIFIED)rows.addAll(chains.completionRecords(f.id,manualGrowthDefinition().id(),Map.of(
    "prepare",new JsonValue.Text(e.tx.value().toString()),"operation",new JsonValue.Text(intent.operationToken().value().toString()),
    "result",new JsonValue.Text(Base64.getEncoder().encodeToString(new ModelCodec<>(SliceResult.class).encode(actual))),
    "physical_plan_hash",new JsonValue.Text(plan.durableBlobs().preparedPlan().hash().value()))));
   return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,selectedBlobs);
  });
  return e;
 }
 private boolean applied(Execution e){
  if(e.noSend){if(!durable(e.noSendAck))return false;release(e);return true;}
  if(e.selectedInput!=null&&e.selectedStage==EventRunner.Stage.PREPARING&&!flight.selection.forwardContextAvailable()&&now().compareTo(e.token.expiryTick())<0)return false;
  e.live=new LiveValidationSnapshot(e.live.readSet(),e.live.loadedChunks(),e.live.health(),now(),e.token.generation());
  var result=e.runner.poll(e.tx,e.live);e.selectedStage=result.stage();
  if(result.stage()==EventRunner.Stage.RECOVERY_REQUIRED&&e.perception!=null&&e.perception.definitivelyNeverSent()){
   var safe=new SliceResult(Outcome.ABORTED_SAFE,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
   e.noSendAck=append(List.of(new DurableResult(new ResultRecord(e.tx,e.perceptionPrepare.operationToken(),safe,Set.of(),List.of(),List.of()))));e.noSend=true;return false;
  }
  if(result.stage()==EventRunner.Stage.REJECTED||result.stage()==EventRunner.Stage.RECOVERY_REQUIRED){
   // An unverifiable RESULT ACK is a store-level fault, even when the store
   // has not published READ_ONLY_SAFE yet. It cannot be quarantined locally.
   if(result.failure().filter("result ACK unavailable or mismatched"::equals).isPresent())
    throw new IllegalStateException("growth RESULT ACK authority unavailable");
   throw new GrowthTrancheUncertain(e,"flight "+result.stage()+" "+result.failure());
  }
  if(result.stage()!=EventRunner.Stage.COMMITTED)return false;
  if(e.actual==null||e.actual.outcome()!=Outcome.SUCCEEDED||e.actual.remainingWork()!=0||!e.actual.conflictCells().isEmpty()
    ||!e.actual.appliedOperationIds().equals(Set.of(e.plan.intent().operationToken()))||!e.actual.observedHashes().equals(e.plan.expectedPost()))
   throw new GrowthTrancheUncertain(e,"growth/WARN actual RESULT is not a completed success; durable failure retained");
  if(!finalizeMutation(e))return false;
  if(!e.retainClaim)release(e);
  return true;
 }
 private void release(Execution e) {
  e.arbiter.release(e.token);
  if(e.admission!=null){e.admission.released(e.proposal,e.token);e.admission=null;}
 }
 private void warn(Flight f) {
  if(!f.warning.available()){finish(f,true);return;}
  PlanningSnapshot context;
  try{context=snapshot(f);}
  catch(SelectedEventStart.ContextUnavailable wait){f.selectedWait=wait.getMessage();return;}
  var intent=new EffectIntent(new OperationToken(UUID.randomUUID()),new PrimitiveId("proutlost:warning"),1,
   new Warning("witness",DirectorGameplayAssets.WARNING,new DurationTicks(200)),
   context.snapshot().context().terrain().getFirst().loadedFootprint(),new EventInstanceId(f.id));
  f.work=execute(f,f.warning.capture(intent,context),f.warning,false,false);f.warnSentAt=now().value();f.phase=1;
 }
 private boolean appendSelectedGrowthDone(Flight f){
  var rows=new ArrayList<DurableRecord>();var blobs=new ArrayList<BlobContent>();Map<ScopeRef,Revision> expected=Map.of();
  String outcome="LEGACY_CLEANUP_ONLY";
  if(f.coldIdentity!=null){
   if(f.coldRead==null)f.coldRead=new NativeCatalogueColdCompletion.Read(store,f.coldIdentity);
   var current=f.coldRead.poll();if(current.isEmpty()){f.selectedWait="WAIT_REAL_LEDGER_METADATA";return false;}
   var view=current.orElseThrow();expected=view.scopeRevisions();var tick=NativeCatalogueColdCompletion.clock(view,now());
   boolean expired=f.selection==null?tick.value()>=f.selectedDeadline:selectedGrowthExpired(f);
   var mutation=growthMutations.get(f.admitted.creationMutationId());
   boolean physical=mutation!=null&&mutation.instanceId().value().equals(f.id)&&mutation.primitiveId().equals(GROWTH)
     &&mutation.mutationId().equals(f.admitted.creationMutationId())&&mutation.durableSeq().value()>0
     &&Set.of(MutationState.ROLLED_BACK,MutationState.YIELDED).contains(mutation.state());
   if(physical&&!expired){
    var evidence=Map.<String,JsonValue>of("mutation_refs",new JsonValue.Array(List.of(new JsonValue.Text(mutation.mutationId().value().toString()))),
      "mutation_state",new JsonValue.Text(mutation.state().name()),"physical_commit_sequence",new JsonValue.Text(Long.toString(mutation.durableSeq().value())),
      "original_operation",new JsonValue.Text(f.forward.intent().operationToken().value().toString()),"cleanup_complete",new JsonValue.Bool(true));
    if(f.selection!=null){
     var original=NativeCatalogueColdCompletion.originalFact(f.coldIdentity,evidence);
     rows.addAll(f.selection.completionRecords(evidence));
     var ledger=CatalogueEventLedger.completed(growthSelection(f),view.metadata(),original,tick);
     rows.addAll(ledger.records());blobs.addAll(ledger.blobs());
    }else{
     // The physical gate is the actual positive terminal MutationRecord, not a reconstructed observation.
     var ledger=NativeCatalogueColdCompletion.completed(f.coldIdentity,view,evidence,false,now());
     rows.addAll(ledger.records());blobs.addAll(ledger.blobs());
    }
    outcome="CONFIRMED_ORIGINAL_OUTCOME";
   }else if(physical&&expired){
    var payload=new HashMap<String,JsonValue>();
    payload.put("definition",new JsonValue.Text(f.selectedDefinition.id().value()));
    payload.put("decision",new JsonValue.Text(f.selectedDecision.value().toString()));
    payload.put("source",new JsonValue.Text("CATALOGUE_SELECTED"));payload.put("reason",new JsonValue.Text("MAX_DURATION_CLEANUP_COMPLETE"));
    payload.put("recovery_complete",new JsonValue.Bool(true));payload.put("selected_deadline",new JsonValue.Text(Long.toString(f.selectedDeadline)));
    payload.put("mutation_refs",new JsonValue.Array(List.of(new JsonValue.Text(mutation.mutationId().value().toString()))));
    var id=new FactId(UUID.nameUUIDFromBytes(("catalogue-aborted-v1\0"+f.id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    var aborted=new DirectorFact(id,new FactType("proutlost_director:catalogue_event_aborted"),
      f.selectedPlan.scopes().scopes().stream().sorted(Comparator.comparing(ScopeRef::toString)).toList(),Optional.of(new EventInstanceId(f.id)),
      EvidenceStatus.CONFIRMED,payload,true,Optional.empty());
    rows.add(new DurableFact(aborted));rows.add(NativeCatalogueFactOutbox.abort(aborted,f.selectedDecision,tick));
    outcome="PROVEN_MAX_DURATION_CLEANUP_COMPLETE";
   }else if(f.selection!=null&&f.impact==null&&!f.selectedRecovery){
    rows.addAll(f.selection.abortRecords("CANCELLED_BEFORE_PHYSICAL_PREPARE",Map.of("no_physical_effect",new JsonValue.Bool(true))));
    outcome="PROVEN_BEFORE_PHYSICAL_PREPARE";
   }else{
    var owner=owners.get(f.key);var local=attached.read(level(f.position),pos(f.position));
    boolean restored=mutation==null&&owner!=null&&local.filter(owner::equals).isPresent()
      &&owner.header().id().equals(f.admitted.header().id())&&owner.ownerTokens().equals(f.admitted.ownerTokens())
      &&owner.creationMutationId().equals(f.admitted.creationMutationId())&&owner.epoch().equals(f.admitted.epoch())
      &&owner.disposition()==OwnershipDisposition.RELEASED
      &&hash(f.position).equals(f.forward.preconditions().fingerprints().get(f.key));
    if(restored){
     // Proven present cleanup does not pretend a lost original effect Result succeeded.
     var payload=new HashMap<String,JsonValue>();payload.put("definition",new JsonValue.Text(f.selectedDefinition.id().value()));
     payload.put("decision",new JsonValue.Text(f.selectedDecision.value().toString()));payload.put("source",new JsonValue.Text("CATALOGUE_SELECTED"));
     payload.put("reason",new JsonValue.Text("COLD_CLEANUP_WITHOUT_CONFIRMED_ORIGINAL_OUTCOME"));payload.put("recovery_complete",new JsonValue.Bool(true));
     payload.put("owner",new JsonValue.Text(owner.header().id().toString()));payload.put("observed_pre",new JsonValue.Text(hash(f.position).value()));
     f.parentChain.ifPresent(parent->payload.put("parent_chain",new JsonValue.Text(parent.value().toString())));
     var id=new FactId(UUID.nameUUIDFromBytes(("catalogue-aborted-v1\0"+f.id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
     var fact=new DirectorFact(id,new FactType("proutlost_director:catalogue_event_aborted"),
       f.selectedPlan.scopes().scopes().stream().sorted(Comparator.comparing(ScopeRef::toString)).toList(),Optional.of(new EventInstanceId(f.id)),
       EvidenceStatus.CONFIRMED,payload,true,Optional.empty());
     rows.add(new DurableFact(fact));rows.add(NativeCatalogueFactOutbox.abort(fact,f.selectedDecision,tick));outcome="PROVEN_RECOVERY_WITHOUT_ORIGINAL_OUTCOME";
    }else{
     rows.add(new DurableFact(fact(new FactType("proutlost_director:selected_growth_outcome_uncertain"),f.id,
       Map.of("reason",new JsonValue.Text("NO_PROVEN_ORIGINAL_OUTCOME_OR_RESTORED_PRE")))));
     outcome="UNCERTAIN_NO_ORIGINAL_OUTCOME";
    }
   }
  }else System.out.println("DIRECTOR_GROWTH_COLD_LEGACY_CLEANUP_ONLY_NO_ORIGINAL_OUTCOME "+f.id);
  rows.add(new DurableFact(fact(DONE,f.id,Map.of("selected",new JsonValue.Bool(true),"outcome_qualification",new JsonValue.Text(outcome)))));
  f.ack=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),expected,rows,blobs)).toCompletableFuture();
  f.donePending=false;return true;
 }
 private void finish(Flight f,boolean release){
  if(f.warning!=null)f.warning.stop();
  var current=Objects.requireNonNull(owners.get(f.key));var rows=new ArrayList<DurableRecord>();
  var mutation=growthMutations.get(f.admitted.creationMutationId());
  MutationState terminal=current.disposition()==OwnershipDisposition.YIELDED_TO_PLAYER?MutationState.YIELDED:
   current.disposition()==OwnershipDisposition.CONFLICT_ISOLATED?MutationState.CONFLICT_ISOLATED:null;
  boolean matching=mutation!=null&&mutation.instanceId().value().equals(f.id)&&current.creationMutationId().equals(mutation.mutationId())
   &&current.resourceKey().equals(f.key)&&current.header().id().equals(f.admitted.header().id())
   &&current.ownerTokens().equals(f.admitted.ownerTokens())&&current.ownerTokens().contains(mutation.ownerToken())
   &&current.epoch().compareTo(mutation.ownerEpoch())>=0;
  if(matching&&terminal!=null&&(mutation.state()==terminal||mutation.state()==MutationState.ROLLED_BACK)&&mutation.durableSeq().value()==0){
   f.terminalMutation=new Execution();f.terminalMutation.mutation=mutation;f.finishAfterFinalizeRelease=release;f.phase=6;return;
  }
  boolean persistOwner=false;
  if(release&&current.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE){
   persistOwner=true;
   var h=current.header();current=new OwnershipRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),
    h.lastSequence(),now()),f.key,current.ownerTokens(),current.epoch(),OwnershipDisposition.RELEASED,
    current.creationMutationId(),current.expectedPostHash(),current.playerOverrideEpoch(),current.exceptionContractId());
   owners.put(f.key,current);attached.write(level(f.position),pos(f.position),current);
  }
  if(persistOwner)rows.add(new DurableOwnership(current));
  if(matching&&terminal!=null&&mutation.state()!=MutationState.ROLLED_BACK&&mutation.state()!=terminal&&loaded(f.position)){
   var h=mutation.header();
   var evidence=List.of(new RecoveryEvidence(f.key,EvidenceStatus.CONFIRMED,Optional.of(hash(f.position)),
    Optional.of(mutation.ownerToken()),current.epoch(),current.playerOverrideEpoch(),false));
   var next=new MutationRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),h.lastSequence(),now()),
    mutation.mutationId(),mutation.instanceId(),mutation.sliceId(),terminal,mutation.primitiveId(),mutation.primitiveVersion(),
    mutation.footprint(),mutation.preStateRef(),mutation.postStateRef(),mutation.ownerToken(),mutation.ownerEpoch(),
    mutation.readSetVersions(),mutation.noninvertibleEffects(),evidence,mutation.compensationRef(),new Sequence(0));
   // Player hooks / the existing conflict path already enqueue this exact owner revision.
   rows.add(new DurableMutation(next));growthMutations.put(next.mutationId(),next);
   f.terminalMutation=new Execution();f.terminalMutation.mutation=next;f.donePending=true;
  }
  if(f.selectedPlan!=null)f.donePending=true;
  if(!f.donePending)rows.add(new DurableFact(fact(DONE,f.id,Map.of())));
  f.ack=append(rows);f.phase=5;
 }
 public void bindAnchorOwner(DirectorNativeAnchorOwner owner){
  actor();
  if(traces==null)traces=content==null?new DirectorNativeTraceGameplay(server,world,store,owner,List.copyOf(traceRecovery.values()),
   ()->reservationFloor,value->reservationFloor=Math.max(reservationFloor,value)):new DirectorNativeTraceGameplay(server,world,store,owner,List.copyOf(traceRecovery.values()),
   ()->reservationFloor,value->reservationFloor=Math.max(reservationFloor,value),content);
  else if(traces.owner()!=owner)throw new IllegalStateException("Trace owner changed inside WAL session");
  choices.bind(owner,traces);
 }
 public Optional<DirectorNativeAnchorOwner> labAnchorOwner(){actor();return traces==null?Optional.empty():Optional.of(traces.owner());}
 public UUID labTestBeginTrace(AnchorId id,Runnable afterCapture,Runnable afterApply){requireLaboratory();
  actor();if(!Boolean.getBoolean("proutlost.director.nativeGameTests"))throw new IllegalStateException("Trace fixture disabled");
  return traces==null?null:traces.labBegin(id,afterCapture,afterApply);
 }
 public DirectorNativeTraceGameplay.TraceStatus labTraceStatus(){
  actor();return traces==null?new DirectorNativeTraceGameplay.TraceStatus(null,"UNBOUND","NONE",Optional.empty(),Optional.empty(),0):traces.status();
 }
 public String selectChoicePacket(ServerPlayer player,AnchorInteraction interaction){actor();return choices.selectPacket(player,interaction);}
 public String beginChoice(ServerPlayer player){requireLaboratory();actor();return choices.begin(player);}
public String selectChoice(ServerPlayer player,UUID instance,ResourceId action){actor();return choices.select(player,instance,action);}
public String choiceStatus(){actor();return choices.status();}
 public UUID labTestBeginAnchorMutation(AnchorId id,PrimitiveId primitive,PrimitiveArgs args,UUID event,
 java.util.function.Function<DirectorNativeAnchorOwner.Lease,AnchorMutationBinding> factory,
 Runnable afterCapture,Runnable afterApply){requireLaboratory();
 actor();if(!Boolean.getBoolean("proutlost.director.nativeGameTests"))throw new IllegalStateException("native mutation fixture disabled");
 return traces==null?null:traces.labBeginAnchorMutation(id,primitive,args,event,factory,afterCapture,afterApply);
}
 public String waterProbe(ServerPlayer player,boolean start){requireLaboratory();
  actor();if(!player.isAlive()||!player.hasPermissions(4))return "Joueur OP4 vivant requis.";
  if(start){if(waterProbe!=null&&!waterProbeReported)return "Mesure de nage deja active.";waterProbe=new NativeControlledWaterPlayerProbe(server,player,600);waterProbeReported=false;return "Mesure native de nage active pour30secondes; aucun mouvement ne sera impose.";}
  if(waterProbe==null)return "Aucune mesure de nage.";
  var s=waterProbe.snapshot();return "WATER_PROBE "+s.finishedReason()+" samples="+s.samples().size()+" submerged="+s.submergedSamples()+" swimming="+s.submergedSwimmingSamples()+" distance="+s.submergedSwimmingHorizontalDistance()+" air="+s.minAir()+"/"+s.maxAir()+" flying="+s.flyingSeen()+" nonSurvival="+s.creativeOrSpectatorSeen();
 }
 public String beginFauna(ServerPlayer player){requireLaboratory();actor();if(physicalDisasterBusy())return "Une catastrophe LAB est deja active/en recuperation.";return fauna.begin(player);}
 public String cancelFauna(ServerPlayer player){requireLaboratory();actor();return fauna.cancel(player);}
 public UUID labTestBeginFauna(NativeFaunaHabitat.Layout layout){requireLaboratory();actor();return fauna.labTestBegin(layout);}
 public DirectorNativeFaunaMigrationGameplay.LabStatus labFaunaStatus(){actor();return fauna.labStatus();}
 public String beginTrace(ServerPlayer player){requireLaboratory();actor();return traces==null?"Observation J01 en attente.":traces.begin(player);}
 private boolean physicalDisasterBusy(){return disasters.busy()||(floods!=null&&floods.busy())||(fires!=null&&fires.busy())||(storms!=null&&storms.busy());}
 public String beginDisaster(ServerPlayer player,fr.proutlost.director.runtime.DisasterRunner.Family family){requireLaboratory();
  actor();if(flight!=null||!dossiers.isEmpty()||(landslides!=null&&landslides.busy())||fauna.busy()||physicalDisasterBusy())return "Une mutation LAB est deja active/en recuperation.";
  return switch(family){case FLOOD->floods.begin(player);case FIRE->fires.begin(player);case STORM->ensureStorm().begin(player);default->disasters.begin(player,family);};
 }
 public String cancelDisaster(ServerPlayer player){requireLaboratory();
  actor();if(floods.busy())return floods.cancel(player);if(fires.busy())return fires.cancel(player);if(storms!=null&&storms.busy())return storms.cancel(player);return disasters.cancel(player);
 }
 public String beginLandslide(ServerPlayer player){requireLaboratory();actor();if(physicalDisasterBusy())return "Une catastrophe LAB est deja active/en recuperation.";return landslides.begin(player);}
 public String cancelLandslide(ServerPlayer player){requireLaboratory();actor();return landslides.cancel(player);}
 public String beginPerception(ServerPlayer player,String name){requireLaboratory();actor();return perceptions.begin(player,name);}
 public String beginChain(ServerPlayer player,String name){requireLaboratory();actor();if(physicalDisasterBusy())return "Une catastrophe LAB est deja active/en recuperation.";return chains.begin(player,name);}
 public String suspendChain(ServerPlayer player,UUID chain){requireLaboratory();actor();return chains.suspend(player,chain);}
 public String resumeChain(ServerPlayer player,UUID chain){requireLaboratory();actor();return chains.resume(player,chain);}
 public String chainStatus(){actor();return chains.status();}
 public String catalogueCommand(ServerPlayer player,String action,String value){requireLaboratory();
  actor();return switch(action){
   case "configure"->catalogueCoordinator.configure(player);
   case "on"->catalogueCoordinator.onOff(player,true);
   case "off"->catalogueCoordinator.onOff(player,false);
   case "status"->catalogueControl.status()+" | "+catalogueCoordinator.status();
   case "event"->catalogueCoordinator.event(player,value);
   case "auto"->catalogueCoordinator.automatic(player,"on".equals(value));
   case "require_signal"->catalogueCoordinator.requireSignal(player,value);
   default->"Commande catalogue inconnue.";
  };
 }
 /** The same physical-debit projection as catalogue admission, pinned to the WORLD read cut. */
 public Optional<BudgetView> catalogueAdminBudgetAt(ConfigView profile,Sequence expectedCut){actor();if(content==null||!content.acceptsConfig(profile))return Optional.empty();return catalogueBudget(profile,Optional.of(expectedCut));}
 public Optional<BudgetView> catalogueAdminBudgetAt(Sequence expectedCut){
  actor();Objects.requireNonNull(expectedCut);
  return catalogueBudget(catalogueConfig,Optional.of(expectedCut));
 }
 private Optional<BudgetView> currentCatalogueBudget(){
  if(!production())return catalogueBudget(catalogueConfig);
  var current=store.session();var query=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.WORLD,world.value())),new Sequence(0),Optional.empty(),1);
  var read=store.view(query);ReadContracts.validate(read,query,current);if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().aggregates().size()!=1||!(read.page().orElseThrow().aggregates().getFirst().state() instanceof WorldAggregate w))return Optional.empty();
  var profile=AdminWorldControlHandler.configuration(w.value(),content.profiles());if(!profile.provenanceHash().equals(w.value().configHash()))return Optional.empty();
  return catalogueBudget(profile,Optional.of(read.page().orElseThrow().lastDurableSequence()));
 }
 private Optional<BudgetView> catalogueBudget(ConfigView runtimeConfig){
  return catalogueBudget(runtimeConfig,Optional.empty());
 }
 private Optional<BudgetView> catalogueBudget(ConfigView runtimeConfig,Optional<Sequence> expectedCut){
  var session=store.session();var discovered=production()?store.residentBudgetAggregateKeys(4096):store.residentAggregateKeys(
   Set.of(AggregateKind.OWNERSHIP,AggregateKind.ENTITY_OWNERSHIP,AggregateKind.REGION),4096);
  if(discovered.isEmpty()||!discovered.get().session().equals(session)||discovered.get().keys().size()>1024||
     expectedCut.filter(cut->!cut.equals(discovered.get().cut())).isPresent())return Optional.empty();
  var keys=discovered.get().keys();if(keys.isEmpty())keys=Set.of(new AggregateKey(AggregateKind.WORLD,world.value()));
  var query=new StoreQuery(world,keys,new Sequence(0),Optional.empty(),1024);var read=store.view(query);
  ReadContracts.validate(read,query,session);
  if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().hasMore()||
     !read.page().orElseThrow().lastDurableSequence().equals(discovered.get().cut())||
     !store.session().equals(session))return Optional.empty();
  return Optional.of(CatalogueBudgetProjection.from(runtimeConfig,
   read.page().orElseThrow().aggregates().stream().map(row->row.state()).toList()));
 }

 /** Explicit LAB chains get the exact published memory/J01 cut before observation/scheduler writers. */
 public void pollLabChainsAtFreshContext(){actor();if(scanned&&!failed){
  catalogueControl.poll();ensureLocations();locations.poll();ensureCampState();campState.poll();
  if(!catalogueCoordinator.eventRecoveryLaneHeld()&&!locations.metadataLaneHeld()&&!campState.metadataLaneHeld()){chains.poll();catalogueCoordinator.pollAfterChains();}
 }}
 private void ensureCampState(){
  if(campState!=null)return;var life=fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle();
  campState=content==null?new DirectorNativeCampStateProjection(server,world,store,catalogueConfig,()->life.observationSnapshot(server),()->life.runtimeMemoryView(server),()->life.runtimeClock(server),()->life.runtimeMetadataIngressQuiescent(server),this::acquireMetadataLane,this::releaseMetadataLane):new DirectorNativeCampStateProjection(server,world,store,catalogueConfig,()->life.observationSnapshot(server),()->life.runtimeMemoryView(server),()->life.runtimeClock(server),()->life.runtimeMetadataIngressQuiescent(server),this::acquireMetadataLane,this::releaseMetadataLane,content.mode());
 }
 public String campStateStatus(){actor();return campState==null?"CAMP_STATE_WAIT_BOOT":campState.status();}
 private void ensureLocations(){
  if(locations!=null)return;if(!scanned)throw new IllegalStateException("recovery incomplete");
  var life=fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle();
  locations=new DirectorNativeLocationImports(server,world,store,()->life.runtimeMemoryView(server),()->life.runtimeMetadataIngressQuiescent(server),this::acquireMetadataLane,this::releaseMetadataLane,content);
  locations.acceptRecoveryRows(locationRecoveryRows,true,locationRecoveryCut);locationRecoveryRows.clear();
 }
 public String importLocations(ServerPlayer player,String name){actor();if(!scanned)return "Recuperation J04 en cours.";ensureLocations();return locations.submit(player,name);}
 public boolean bootstrapLocations(String name,DirectorRuntimeContent expected){
  actor();if(content==null||content!=expected||!content.production())throw new IllegalArgumentException("production import owner mismatch");
  ensureLocations();return locations.submitApproved(name,expected);
 }
 public boolean bootstrapLocationsComplete(DirectorRuntimeContent expected){actor();return locations!=null&&locations.approvedSubmissionComplete(expected);}
 public boolean bootstrapLocationsFailed(DirectorRuntimeContent expected){actor();return locations!=null&&locations.approvedSubmissionFailed(expected);}
 public String locationImportStatus(){actor();return locations==null?"LOCATION_IMPORT_WAIT_BOOT":locations.status();}
 public Optional<Map<RegionId,Set<TagId>>> importedRegionTags(){actor();return locations!=null&&locations.regionTagsReady()?Optional.of(locations.regionTags()):Optional.empty();}
 public boolean catalogueOwnsWorldLane(){actor();return metadataLaneOwner!=null||catalogueControl.ownsWorldLane();}
 public boolean catalogueEnabled(){actor();return catalogueControl.enabled();}
 public HealthMode catalogueObservationHealth(){actor();return catalogueControl.observationHealth();}
 public Optional<Fingerprint> catalogueConfiguredHash(){actor();return catalogueControl.configuredCatalogHash();}
 public DirectorNativeCatalogueCoordinator coordinator(){actor();return catalogueCoordinator;}
 public DirectorNativeCatalogueControl catalogueControl(){actor();return catalogueControl;}
 public String catalogueControlStatus(){actor();return catalogueControl.status();}
 public boolean adminControlAvailable(){actor();return f8Control!=null&&f8Control.available();}
 public java.util.concurrent.CompletionStage<AdminResult> submitAdminControl(AdminCommand command){actor();requireLaboratory();return f8Control.submit(command);}
 public String requestCatalogueControl(ServerPlayer player,boolean run,CatalogSnapshot catalog,boolean ready){actor();return catalogueControl.request(player,run,catalog,ready);}
 public boolean retainD0LogicalPin(PhysicalSitePin pin){actor();if(content==null||!content.envelopesReady())return false;content.envelopes().retainHistoricalPhysical(pin);return true;}
 public void retireD0LogicalPin(EventInstanceId event){actor();if(content!=null&&content.envelopesReady())content.envelopes().retireHistoricalPhysical(event);}
 public void regionalWeather(DirectorProductionRegionalWeather owner){actor();if(regionalWeather!=null)throw new IllegalStateException("regional production owner already installed");regionalWeather=Objects.requireNonNull(owner);}
 public void physicalCatalogues(DirectorNativePhysicalCatalogues owner){actor();if(physicalCatalogues!=null)throw new IllegalStateException("physical owner already installed");physicalCatalogues=Objects.requireNonNull(owner);}
 public OwnershipView physicalOwnershipSnapshot(){actor();return new OwnershipView(owners);}
 public boolean metadataInputQuiescent(){actor();return scanned&&!failed&&fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeMetadataIngressQuiescent(server);}
 public boolean metadataLaneBusy(){actor();return metadataLaneOwner!=null;}
 public boolean acquireMetadataLane(Object owner){
  actor();Objects.requireNonNull(owner);if(metadataLaneOwner==owner)return true;
  if(metadataLaneOwner!=null||!fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeMetadataIngressQuiescent(server))return false;
  if(traces==null)return false;traces.owner().beginMetadata(owner);metadataLaneOwner=owner;return true;
 }
 public void releaseMetadataLane(Object owner){actor();if(metadataLaneOwner!=owner)throw new IllegalStateException("metadata lane owner mismatch");if(traces==null)throw new IllegalStateException("canonical anchor owner missing");traces.owner().endMetadata(owner);metadataLaneOwner=null;}
 public boolean metadataLaneHeldBy(Object owner){actor();return metadataLaneOwner==owner;}
 public void bindChainDriver(DirectorNativeChainGameplay.Driver driver){
  actor();Objects.requireNonNull(driver);if(selectedChainDriver!=null&&selectedChainDriver!=driver)throw new IllegalStateException("selected chain driver already bound");selectedChainDriver=driver;
 }
 private static final class PhysicalAdminControl {
  final AdminCommand command;final StoreSession session;final List<java.util.function.Supplier<AdminStatus>> probes;final Set<EventInstanceId> events;
  StoreQuery query;CompletableFuture<StoreReadResult> read;
  PhysicalAdminControl(AdminCommand command,StoreSession session,List<java.util.function.Supplier<AdminStatus>> probes,Set<EventInstanceId> events){
   this.command=command;this.session=session;this.probes=List.copyOf(probes);this.events=Set.copyOf(events);
  }
 }
 private final Map<RuntimeAdminKey,PhysicalAdminControl> physicalAdminControls=new LinkedHashMap<>();
 private AdminResult physicalAdminResult(AdminCommand command,AdminStatus status,ReasonCode reason){
  return new AdminResult(status,command.requestId(),command.expectedRevision(),Optional.empty(),reason==null?Optional.empty():Optional.of(
   new SystemError(reason,Severity.WARNING,status==AdminStatus.DEFERRED,Optional.empty(),Set.of(new ScopeRef(ScopeKind.WORLD,world.value())),
    new CorrelationId(command.requestId().value()),status==AdminStatus.FAILED_SAFE?SafeState.ISOLATED:SafeState.UNCHANGED)));
 }
 private AdminResult physicalAdminProgress(PhysicalAdminControl control){
  if(!control.session.equals(store.session())||store.readOnlySafe())return physicalAdminResult(control.command,AdminStatus.FAILED_SAFE,ReasonCode.WAL_UNAVAILABLE);
  boolean pending=false,failedControl=false;
  for(var probe:control.probes){
   var state=probe.get();pending|=state==AdminStatus.PENDING;failedControl|=state!=AdminStatus.PENDING&&state!=AdminStatus.SUCCEEDED;
  }
  if(pending||failedControl)return physicalAdminResult(control.command,pending?AdminStatus.PENDING:AdminStatus.FAILED_SAFE,
   !pending?(control.command.operation()==AdminOperation.RESCUE?ReasonCode.DELIVERY_UNCERTAIN:ReasonCode.RECOVERY_AMBIGUOUS):null);
  if(!production()||control.events.isEmpty())return physicalAdminResult(control.command,AdminStatus.SUCCEEDED,null);
  return physicalAdminEventProof(control);
 }
 /** Read only the pinned EVENT heads after the native cleanup ACK; never infer terminal state from a missing flight. */
 private AdminResult physicalAdminEventProof(PhysicalAdminControl control){
  try{
   if(control.read==null){
    var keys=new HashSet<AggregateKey>();control.events.forEach(id->keys.add(new AggregateKey(AggregateKind.EVENT,id.value())));
    control.query=new StoreQuery(world,keys,new Sequence(0),Optional.empty(),128);
    var resident=store.view(control.query);
    control.read=resident.status()==ReadStatus.NOT_RESIDENT?store.read(control.query).toCompletableFuture():CompletableFuture.completedFuture(resident);
   }
   if(!control.read.isDone())return physicalAdminResult(control.command,AdminStatus.PENDING,null);
   var read=control.read.getNow(null);control.read=null;
   if(read==null)return physicalAdminResult(control.command,AdminStatus.FAILED_SAFE,ReasonCode.WAL_UNAVAILABLE);
   ReadContracts.validate(read,control.query,control.session);
   if(read.status()==ReadStatus.STALE)return physicalAdminResult(control.command,AdminStatus.PENDING,null);
   if(read.status()==ReadStatus.NOT_RESIDENT)return physicalAdminResult(control.command,AdminStatus.DEFERRED,ReasonCode.RECOVERY_AMBIGUOUS);
   if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().hasMore())return physicalAdminResult(control.command,AdminStatus.FAILED_SAFE,ReasonCode.WAL_UNAVAILABLE);
   var heads=new HashMap<EventInstanceId,EventInstance>();
   for(var row:read.page().orElseThrow().aggregates())if(row.state() instanceof EventAggregate event&&row.committedSequence().value()>0)
    heads.put(event.value().instanceId(),event.value());
   for(var id:control.events){var event=heads.get(id);if(event==null||!Set.of(EventState.SUCCEEDED,EventState.PARTIAL,EventState.ABORTED_SAFE,EventState.FAILED_SAFE).contains(event.state()))
    return physicalAdminResult(control.command,AdminStatus.PENDING,null);}
   var result=physicalAdminResult(control.command,AdminStatus.SUCCEEDED,null);
   if(control.events.size()!=1)return result;
   var event=heads.get(control.events.iterator().next());
   return new AdminResult(result.status(),result.requestId(),result.revision(),Optional.of(new AdminRow(new ScopeRef(ScopeKind.EVENT,event.instanceId().value()),
    event.header().revision(),new Tick(fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server)),
    new EventAdminData(event,List.of(),List.of(),List.of()))),Optional.empty());
  }catch(RuntimeException failedProof){return physicalAdminResult(control.command,AdminStatus.FAILED_SAFE,ReasonCode.RECOVERY_AMBIGUOUS);}
 }
 /** Invoked only by the canonical dispatcher after its exact forced intent and actor/revision checks. */
 public AdminResult control(AdminCommand command,ActorIdentity actor){
  actor();Objects.requireNonNull(command);Objects.requireNonNull(actor);
  if(!AdminContracts.authorized(command,actor))return physicalAdminResult(command,AdminStatus.REJECTED,ReasonCode.AUTH_DENIED);
  var key=new RuntimeAdminKey(command.actorSession(),command.requestId());var previous=physicalAdminControls.get(key);
  if(previous!=null)return previous.command.equals(command)?physicalAdminProgress(previous):physicalAdminResult(command,AdminStatus.REJECTED,ReasonCode.IDEMPOTENCY_CONFLICT);
  if(physicalAdminControls.size()>=64)return physicalAdminResult(command,AdminStatus.DEFERRED,ReasonCode.BUDGET_EXHAUSTED);
  if(failed||store.readOnlySafe())return physicalAdminResult(command,AdminStatus.FAILED_SAFE,ReasonCode.WAL_UNAVAILABLE);
  if(!scanned)return physicalAdminResult(command,AdminStatus.DEFERRED,ReasonCode.RECOVERY_AMBIGUOUS);
  var probes=new ArrayList<java.util.function.Supplier<AdminStatus>>();var affectedEvents=new HashSet<EventInstanceId>();
  if(command.payload() instanceof StopPayload payload){
   if(payload.all()&&((landslides!=null&&landslides.busy())||(floods!=null&&floods.busy())||(fires!=null&&fires.busy())||(storms!=null&&storms.busy()&&!production())))
    return physicalAdminResult(command,AdminStatus.REJECTED,ReasonCode.CAPABILITY_MISSING);
   if(payload.all()&&(!chains.controlRecoveryReady()||!goats.controlRecoveryReady()||!perceptions.controlRecoveryReady()||!fauna.controlRecoveryReady()
       ||!disasters.controlRecoveryReady()||!choices.controlRecoveryReady()||traces!=null&&!traces.controlRecoveryReady()))
    return physicalAdminResult(command,AdminStatus.DEFERRED,ReasonCode.RECOVERY_AMBIGUOUS);
   var target=payload.instanceId();
   if(production()){
    affectedEvents.addAll(chains.controlEventTargets(target));
    if(target.isPresent())affectedEvents.add(target.orElseThrow());
    else{
     activeGrowthEvent().map(EventInstanceId::new).ifPresent(affectedEvents::add);goats.activeEvent().map(EventInstanceId::new).ifPresent(affectedEvents::add);
     perceptions.activeEvent().map(EventInstanceId::new).ifPresent(affectedEvents::add);fauna.activeEvent().map(EventInstanceId::new).ifPresent(affectedEvents::add);
     disasters.activeEvent().map(EventInstanceId::new).ifPresent(affectedEvents::add);choices.activeEvent().map(EventInstanceId::new).ifPresent(affectedEvents::add);
     if(traces!=null)traces.activeEvent().map(EventInstanceId::new).ifPresent(affectedEvents::add);if(storms!=null)storms.activeEvent().map(EventInstanceId::new).ifPresent(affectedEvents::add);
    }
   }
   var queueTargets=catalogueCoordinator.requestStopQueue(target);
   if(production())affectedEvents.addAll(queueTargets);
   if(payload.all()||!queueTargets.isEmpty())probes.add(()->catalogueCoordinator.stopQueueComplete(queueTargets)?AdminStatus.SUCCEEDED:AdminStatus.PENDING);
   chains.requestStop(target).ifPresent(probes::add);if(storms!=null)storms.requestStop(target).ifPresent(probes::add);var f=flight;
   if(f!=null&&target.filter(id->!id.value().equals(f.id)).isEmpty()){
    f.stopRequested=true;var event=f.id;
    probes.add(()->{actor();return completed.contains(event)?AdminStatus.SUCCEEDED:failed||f.quarantined?AdminStatus.FAILED_SAFE:AdminStatus.PENDING;});
   }
   goats.requestStop(target).ifPresent(probes::add);perceptions.requestStop(target).ifPresent(probes::add);
   fauna.requestStop(target).ifPresent(probes::add);disasters.requestStop(target).ifPresent(probes::add);
   if(traces!=null)traces.requestStop(target).ifPresent(probes::add);choices.requestStop(target).ifPresent(probes::add);
   if(probes.isEmpty()&&!payload.all())return physicalAdminResult(command,AdminStatus.REJECTED,ReasonCode.NO_TARGET);
  }else if(command.payload() instanceof StabilizePayload payload){
   disasters.requestStabilize(payload.disasterId()).ifPresent(probes::add);
   if(probes.isEmpty())return physicalAdminResult(command,AdminStatus.REJECTED,ReasonCode.NO_TARGET);
   if(production())disasters.activeEvent().map(EventInstanceId::new).ifPresent(affectedEvents::add);
  }else if(command.payload() instanceof RescuePayload payload){
   if(payload.self()&&actor.playerId().isEmpty())return physicalAdminResult(command,AdminStatus.REJECTED,ReasonCode.AUTH_DENIED);
   var target=payload.self()?actor.playerId().orElseThrow():payload.target().orElseThrow();
   var reset=DirectorNativeNetworkBootstrap.rescuePlayer(server,target);
   if(reset.isEmpty())return physicalAdminResult(command,AdminStatus.DEFERRED,ReasonCode.NO_TARGET);
   var expected=reset.orElseThrow();
   probes.add(()->{actor();return DirectorNativeNetworkBootstrap.sessionNegotiated(server,expected)?AdminStatus.SUCCEEDED:
    DirectorNativeNetworkBootstrap.currentSession(server,target).filter(expected::equals).isEmpty()?AdminStatus.FAILED_SAFE:AdminStatus.PENDING;});
  }else return physicalAdminResult(command,AdminStatus.REJECTED,ReasonCode.CAPABILITY_MISSING);
  var control=new PhysicalAdminControl(command,store.session(),probes,affectedEvents);var result=physicalAdminProgress(control);
  if(result.status()==AdminStatus.PENDING)physicalAdminControls.put(key,control);
  return result;
 }
 public Optional<AdminResult> controlProgress(RuntimeAdminKey key){
  actor();var control=physicalAdminControls.get(key);if(control==null)return Optional.empty();
  var result=physicalAdminProgress(control);if(result.status()!=AdminStatus.PENDING)physicalAdminControls.remove(key);return Optional.of(result);
 }
 public String beginGoat(ServerPlayer player){requireLaboratory();actor();return goats.begin(player);}
 public String cancelGoat(ServerPlayer player){requireLaboratory();actor();return goats.cancel(player);}
 public void poll(){
  actor();if(waterProbe!=null&&!waterProbeReported&&waterProbe.poll()){waterProbeReported=true;System.out.println("DIRECTOR_LAB_REAL_PLAYER_WATER_PROBE "+new com.google.gson.Gson().toJson(waterProbe.snapshot()));}if(weatherObserver!=null)weatherObserver.poll();if(waterHooks!=null&&waterHooks.failure().isPresent()){failed=true;return;}if(scanned){goats.poll();perceptions.poll();if(landslides!=null)landslides.poll();disasters.poll();if(floods!=null)floods.poll();if(fires!=null)fires.poll();if(storms!=null)storms.poll();fauna.poll();if(traces!=null)traces.poll();choices.poll();}if(failed)return;
  try{
   if(store.readOnlySafe())throw new IllegalStateException("WAL unavailable");
   for(var ack:List.copyOf(hookAcks))if(durable(ack))hookAcks.remove(ack);
   if(!scanned){scan();return;}
   // Async J04 readers must progress every actor tick, independent of J01 rescan windows.
   if(f8Control!=null)f8Control.poll();catalogueCoordinator.pollInputs();
   if(flight==null){recover();return;}
   var f=flight;if(f.quarantined||!loaded(f.position))return;
   switch(f.phase){
    case 0->{if(durable(f.ack)){if(f.stopRequested){finish(f,true);return;}if(selectedGrowthExpired(f)){finish(f,true);return;}warn(f);}}
    case 1->{
     if(f.stopRequested||f.work.noSend){if(applied(f.work))finish(f,true);return;}
     if(selectedGrowthExpired(f)&&f.warnAt>=0){finish(f,true);return;}
     if(f.warnAt<0 && now().value()-f.warnSentAt>=1200){finish(f,true);return;}
     if(f.warnAt<0) {
      boolean warningDone=applied(f.work);
      if(warningDone&&(f.work.noSend||!f.warning.available())){finish(f,true);return;}
      if(warningDone&&f.warning.received()){
       f.warnAt=now().value();System.out.println("DIRECTOR_LAB_GROWTH_WARN_RENDERER_RECEIVED "+f.id);
      }
     } else if(now().value()-f.warnAt>=200) {
      var player=server.getPlayerList().getPlayer(f.player);
      if(player==null||!f.warning.received()||player.serverLevel()!=level(f.position)||
         player.distanceToSqr(f.position.x()+.5,f.position.y()+.5,f.position.z()+.5)>256) {
       finish(f,true);return;
      }
      var current=attached.read(level(f.position),pos(f.position)).orElseThrow();
      owners.put(f.key,current);
      if(!current.equals(f.admitted)||!hash(f.position).equals(f.forward.preconditions().fingerprints().get(f.key))) {
       finish(f,true);System.out.println("DIRECTOR_LAB_GROWTH_CANCELLED_PREIMPACT "+f.id);return;
      }
      if(f.selection!=null){
       try{f.selection.refresh();}catch(SelectedEventStart.ContextUnavailable wait){
        f.selectedWait=wait.getMessage();if(now().value()-f.warnAt>=900)finish(f,true);return;
       }catch(IllegalStateException refused){f.selectedWait=refused.getMessage();finish(f,true);return;}
      }
      DecisionInput selectedInput;
      try{
       f.captured=capture(f,false);
       selectedInput=f.selection==null?null:f.selection.refresh();
      }catch(SelectedEventStart.ContextUnavailable wait){f.selectedWait=wait.getMessage();return;}
      // Native admission, PREPARE submit, physical apply and RESULT are outside the transient WAIT boundary.
      f.work=execute(f,f.captured,binding(f,false),true,true,selectedInput);f.impact=f.work;f.phase=2;
     }
    }
    case 2->{if(applied(f.work)){
     f.warning.stop();f.expires=now().value()+(f.stopRequested?0:Math.min(400,selectedGrowthRemaining(f)));f.phase=3;
     System.out.println("DIRECTOR_LAB_GROWTH_PHYSICAL_COMMITTED "+f.id+" at="+f.position+" mutation="+f.admitted.creationMutationId().value());
    }}
    case 3->{if(f.stopRequested||now().value()>=f.expires||selectedGrowthExpired(f)){
     var current=attached.read(level(f.position),pos(f.position)).orElseThrow();owners.put(f.key,current);
     var allowed=OwnershipRules.rollback(current,f.admitted.ownerTokens().iterator().next(),f.admitted.epoch(),
      f.admitted.playerOverrideEpoch(),f.forward.expectedPost().get(f.key),Optional.of(hash(f.position)),true);
     if(allowed!=OwnershipRules.Rollback.ELIGIBLE){
      if(current.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE){
       current=OwnershipRules.cede(current,OwnershipRules.ExternalChange.UNKNOWN,now());
       owners.put(f.key,current);attached.write(level(f.position),pos(f.position),current);
       hookAcks.add(append(List.of(new DurableOwnership(current))));
      }
      finish(f,false);
     }else{
      f.captured=capture(f,true);f.work=execute(f,f.captured,binding(f,true),true,false);f.phase=4;
     }
    }}
    case 4->{if(applied(f.work)){finish(f,true);System.out.println("DIRECTOR_LAB_GROWTH_INVERSE_COMMITTED "+f.id);}}
    case 5->{if(durable(f.ack)){
     if(f.terminalMutation!=null&&!finalizeMutation(f.terminalMutation))return;
     if(f.donePending){
      if(f.selectedPlan!=null){if(!appendSelectedGrowthDone(f))return;}
      else{f.donePending=false;f.ack=append(List.of(new DurableFact(fact(DONE,f.id,Map.of()))));}
      return;
     }
     if(f.impact!=null)release(f.impact);
     completed.add(f.id);dossiers.remove(f.id);growthMutations.remove(f.admitted.creationMutationId());flight=null;
     System.out.println("DIRECTOR_LAB_GROWTH_CLEANUP_COMPLETE "+f.id);
    }}
    case 6->{if(finalizeMutation(f.terminalMutation))finish(f,f.finishAfterFinalizeRelease);}
    default->throw new IllegalStateException("LAB phase");
   }
  }catch(GrowthTrancheUncertain uncertainty){
   var f=flight;var execution=uncertainty.execution;
   if(!production()||f==null||f.selection==null||!scanned||store.readOnlySafe()
      ||execution.originSession==null||!execution.originSession.equals(store.session())
      ||execution!=f.work&&execution!=f.impact){
    failed=true;System.out.println("DIRECTOR_LAB_GROWTH_RECOVERY_REQUIRED "+uncertainty);uncertainty.printStackTrace();
   }else{
    // Keep the flight, original runner, claims, mutations and metadata lane.
    // No ABORTED_SAFE/terminal fact, release or retry is inferred here.
    f.quarantined=true;f.selectedWait=uncertainty.getMessage();
    System.out.println("DIRECTOR_PRODUCTION_GROWTH_ISOLATED_RECOVERY_REQUIRED event="+f.id+" reason="+f.selectedWait);
   }
  }catch(RuntimeException e){failed=true;System.out.println("DIRECTOR_LAB_GROWTH_RECOVERY_REQUIRED "+e);e.printStackTrace();}
 }
 private void scan(){
  if(pendingRead==null) {
   var query=new StoreQuery(world,Set.of(),new Sequence(0),cursor,128);gameplayRecoveryQuery=query;
   var resident=store.view(query);
   pendingRead=resident.status()==ReadStatus.NOT_RESIDENT?store.read(query).toCompletableFuture():CompletableFuture.completedFuture(resident);
   return;
  }
  if(!pendingRead.isDone())return;
  var read=pendingRead.getNow(null);pendingRead=null;
  ReadContracts.validate(read,gameplayRecoveryQuery,store.session());
  if(read.status()==ReadStatus.STALE) {
   cursor=Optional.empty();owners.clear();dossiers.clear();completed.clear();growthMutations.clear();traceRecovery.clear();pendingStormRecovery.clear();unresolvedWeatherRecovery.clear();if(regionalRecoveryRouting!=null)regionalRecoveryRouting.clear();reservationFloor=0;locationRecoveryRows.clear();locationRecoveryCut=new Sequence(0);
   goats.resetRecoveryScan();perceptions.resetRecoveryScan();if(landslides!=null)landslides.resetRecoveryScan();disasters.resetRecoveryScan();if(floods!=null)floods.resetRecoveryScan();if(fires!=null)fires.resetRecoveryScan();if(storms!=null)storms.resetRecoveryScan();fauna.resetRecoveryScan();choices.resetRecoveryScan();chains.resetRecoveryScan();catalogueCoordinator.resetRecoveryScan();if(f8Control!=null)f8Control.resetRecoveryScan();return;
  }
  if(read.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("recovery read unavailable: "+read.status());
  var page=read.page().orElseThrow();locationRecoveryCut=page.lastDurableSequence();
  for(var record:page.records()){
   observeWeatherRecovery(record);
   if(record instanceof DurableFact lf&&lf.value().factType().equals(DirectorNativeLocationImports.APPLIED))locationRecoveryRows.add(record);
   if(record instanceof DurablePrepare p&&(
 p.value().slice().operations().stream().anyMatch(i->i.primitive().equals(TraceBinding.ID))||
 p.value().durableBlobs().preparedPlan().codecId().equals(SharedChoiceResolutionBinding.PLAN_CODEC)))
 traceRecovery.put(p.value().operationToken(),p.value());
   if(record instanceof DurableResult result)traceRecovery.remove(result.value().operationToken());
   if(record instanceof DurablePrepare prepare)reservationFloor=Math.max(reservationFloor,prepare.value().reservationGeneration().value());
   MutationRecord mutation=record instanceof DurableMutation dm?dm.value():
    record instanceof DurableAggregate da&&da.state() instanceof MutationAggregate ma?ma.value():null;
   if(mutation!=null&&mutation.primitiveId().equals(GROWTH)){
    var old=growthMutations.get(mutation.mutationId());
    if(old==null||old.header().revision().compareTo(mutation.header().revision())<0)growthMutations.put(mutation.mutationId(),mutation);
    else if(old.header().revision().equals(mutation.header().revision())&&!old.equals(mutation))throw new IllegalStateException("ambiguous growth mutation recovery");
   }
   if(record instanceof DurableOwnership row){
    var owner=row.value();var previous=owners.get(owner.resourceKey());
    if(previous==null||owner.epoch().value()>previous.epoch().value()||
       owner.epoch().equals(previous.epoch())&&owner.header().revision().value()>previous.header().revision().value())
     owners.put(owner.resourceKey(),owner);
    else if(owner.epoch().equals(previous.epoch())&&owner.header().revision().equals(previous.header().revision())&&!owner.equals(previous))
     throw new IllegalStateException("ambiguous ownership recovery");
   }else if(record instanceof DurableFact row){
    var fact=row.value();if(fact.eventId().isEmpty())continue;var id=fact.eventId().get().value();
    if(fact.factType().equals(DOSSIER))dossiers.put(id,fact);
    else if(fact.factType().equals(DONE))completed.add(id);
   }
  }
  goats.acceptRecoveryRows(page.records(),!page.hasMore());
  perceptions.acceptRecoveryRows(page.records(),!page.hasMore());
  if(landslides!=null)landslides.acceptRecoveryRows(page.records(),!page.hasMore());
  disasters.acceptRecoveryRows(page.records(),!page.hasMore());
  if(floods!=null)floods.acceptRecoveryRows(page.records(),!page.hasMore());
  if(fires!=null)fires.acceptRecoveryRows(page.records(),!page.hasMore());
  fauna.acceptRecoveryRows(page.records(),!page.hasMore());
  chains.acceptRecoveryRows(page.records(),!page.hasMore(),page.lastDurableSequence());
  catalogueCoordinator.acceptRecoveryRows(page.records(),!page.hasMore(),page.lastDurableSequence());
  if(f8Control!=null)f8Control.acceptRecoveryRows(page.records(),!page.hasMore());
  for(var record:page.records())choices.acceptRecovery(record);
  cursor=page.nextCursor();
  if(!page.hasMore()){
   completed.forEach(dossiers::remove);growthMutations.values().removeIf(m->!dossiers.containsKey(m.instanceId().value()));choices.finishRecoveryScan();openRequiredWeatherRecovery();scanned=true;
   System.out.println("DIRECTOR_LAB_GROWTH_RECOVERY_SCAN pending="+dossiers.size());
  }
 }
 private static String text(DirectorFact fact,String key){return ((JsonValue.Text)fact.payload().get(key)).value();}
 private void recover(){
  for(var entry:dossiers.entrySet()){
   var dossier=entry.getValue();var plan=PLAN.decode(Base64.getDecoder().decode(text(dossier,"plan")));
   var p=plan.intent().expectedFootprint().cells().iterator().next();if(!loaded(p))continue;
   Flight f=new Flight();f.id=entry.getKey();f.position=p;f.key=key(p);f.forward=plan;
   f.original=new ResourceId(text(dossier,"original"));f.player=UUID.fromString(text(dossier,"player"));
   f.admitted=OWNER.decode(Base64.getDecoder().decode(text(dossier,"owner")));
   if(dossier.payload().get("selected_plan") instanceof JsonValue.Text selected){
    f.selectedPlan=new ModelCodec<>(ExecutionPlan.class).decode(Base64.getDecoder().decode(selected.value()));
    f.selectedDefinition=new ModelCodec<>(EventDefinition.class).decode(Base64.getDecoder().decode(text(dossier,"selected_definition")));
    f.selectedDecision=new DecisionId(UUID.fromString(text(dossier,"selected_decision")));
    f.selectedDeadline=dossier.payload().get("selected_deadline") instanceof JsonValue.Text deadline?Long.parseLong(deadline.value()):Long.MIN_VALUE;
    f.selectedCamp=new ModelCodec<>(AnchorRecord.class).decode(Base64.getDecoder().decode(text(dossier,"selected_camp")));
    if(dossier.payload().get("parent_chain") instanceof JsonValue.Text parent)f.parentChain=Optional.of(new ChainId(UUID.fromString(parent.value())));
    f.selectedRecovery=true;f.coldIdentity=NativeCatalogueColdCompletion.restore(dossier).orElse(null);
    if(!f.selectedPlan.effects().getFirst().instanceId().value().equals(f.id)||!f.selectedPlan.effects().getFirst().equals(plan.intent()))
     throw new IllegalStateException("selected growth WAL identity mismatch");
   }
   if(production()&&(f.selectedDefinition==null||f.selectedDefinition.calibration()!=Calibration.APPROVED))
    throw new IllegalStateException("historical TEST_ONLY growth requires laboratory recovery");
   f.expires=now().value();var persisted=owners.get(f.key);var local=attached.read(level(p),pos(p));
   var observed=hash(p);var pre=plan.preconditions().fingerprints().get(f.key);
   if(observed.equals(pre)&&local.isEmpty()){
    f.ack=append(List.of(new DurableFact(fact(DONE,f.id,Map.of("recovery",new JsonValue.Text("PRE_PRESENT_NO_REPLAY"))))));
    f.phase=5;flight=f;return;
   }
   if(local.isEmpty()||persisted==null||!persisted.equals(local.get()))
    throw new IllegalStateException("recovery ownership not proven for "+f.id);
   owners.put(f.key,local.get());
   var rolledBack=growthMutations.get(f.admitted.creationMutationId());
   if(knownRolledBack(f,local.get(),observed,rolledBack)){
    if(rolledBack.durableSeq().value()>0)finish(f,true);
    else{f.terminalMutation=new Execution();f.terminalMutation.mutation=rolledBack;f.finishAfterFinalizeRelease=true;f.phase=6;}
    flight=f;System.out.println("DIRECTOR_LAB_GROWTH_RECOVERED_ROLLED_BACK_NO_REPLAY "+f.id);return;
   }
   if(observed.equals(pre)&&local.get().equals(f.admitted)){
    finish(f,true);flight=f;return;
   }
   f.phase=3;flight=f;
   System.out.println("DIRECTOR_LAB_GROWTH_RECOVERED_FOR_CLEANUP "+f.id);return;
  }
 }
 public boolean recovering(){actor();return (!scanned&&!failed)||goats.recovering()||perceptions.recovering()||(landslides!=null&&landslides.recovering())||fauna.recovering()||choices.recovering()||(storms!=null&&storms.recovering());}
 public String status(){actor();return (failed?"RECOVERY_REQUIRED":!scanned?"RECOVERING":flight==null?
  "IDLE pending="+dossiers.size():flight.quarantined?"RECOVERY_REQUIRED_GROWTH_ISOLATED id="+flight.id:"ACTIVE phase="+flight.phase+" id="+flight.id)+" | "+goats.status()+" | "+perceptions.status()+" | "+(landslides==null?"LAB_NOT_ACTIVE":landslides.status())+" | "+disasters.status()+" | "+(floods==null?"LAB_NOT_ACTIVE":floods.status())+" | "+(fires==null?"LAB_NOT_ACTIVE":fires.status())+" | "+(storms==null?"LAB_NOT_ACTIVE":storms.status())+" | "+chains.status()+" | TRACE_"+labTraceStatus().phase()+" | "+fauna.status()+" | "+choiceStatus();}
 @Override public void close(){
  actor();if(f8Control!=null)f8Control.close();catalogueCoordinator.close();if(locations!=null)locations.close();if(campState!=null)campState.close();catalogueControl.close();goats.close();perceptions.close();if(landslides!=null)landslides.close();disasters.close();if(floods!=null)floods.close();if(fires!=null)fires.close();if(storms!=null)storms.close();if(weatherObserver!=null)weatherObserver.close();if(waterHooks!=null){waterHooks.close();waterHooks=null;}fauna.close();choices.close();chains.close();if(traces!=null)traces.close();if(hooks!=null){hooks.close();hooks=null;}weatherEventBus=null;closed=true;
  // Existing store lifecycle drains WAL. No direct world mutation during shutdown.
 }
}
