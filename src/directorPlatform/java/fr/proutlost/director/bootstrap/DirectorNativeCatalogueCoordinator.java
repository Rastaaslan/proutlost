package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Actor-owned wiring for the real TEST_ONLY catalogue. No driver alias, fake
 * participant, admission, exit, budget or receipt is supplied by this class. */
public final class DirectorNativeCatalogueCoordinator implements AutoCloseable {
 public interface Host {
  boolean ready();
  default Optional<fr.proutlost.director.runtime.DirectorTickWorkBudget> workBudget(){return Optional.empty();}
  boolean enabled();
  boolean transitionPending();
  boolean metadataBusy();
  default boolean metadataHeldBy(Object owner){return false;}
  boolean acquireMetadataLane(Object owner);
  void releaseMetadataLane(Object owner);
  Optional<BudgetView> budget();
  Optional<Map<RegionId,Set<TagId>>> authoredRegionTags();
  String control(ServerPlayer player,boolean run,CatalogSnapshot catalog,boolean providersReady);
  default void bindPhysicalSites(DirectorNativeCatalogueExecution executor){}
  void bindChainDriver(DirectorNativeChainGameplay.Driver driver);
  void bindSelectedContext(Supplier<Optional<DecisionInput>> input);
 }
 private static final DefinitionId SIGNAL=id("required_signal");
 private final MinecraftServer server;private final WorldId world;private final JournalStore store;
 private final StoreSession session;private final ConfigView config;private final Host host;
 private final Supplier<Map<DefinitionId,DirectorNativeCatalogueExecution.Driver>> driverFactory;
 private final DirectorNativeCatalogueInput input;
 private CatalogSnapshot catalog;
 private final DirectorRuntimeContent content;
 private Map<ResourceId,ConfigView> profiles=Map.of();
 private DirectorNativeCatalogueExecution executor;
 private NativeRequirementBridge requirements;
 private DirectorNativeEventLifecycle events;
 private DirectorNativeCatalogueRuntime runtime;
 private Map<CapabilityId,CapabilityDescriptor> capabilities=Map.of();
 private boolean closed,eventRecoveryLane;private String detail="WAIT_EXPLICIT_CONFIGURE";
 public DirectorNativeCatalogueCoordinator(MinecraftServer server,WorldId world,JournalStore store,
   ConfigView config,Host host,Supplier<Map<DefinitionId,DirectorNativeCatalogueExecution.Driver>> driverFactory){
  this(server,world,store,null,config,host,driverFactory);
 }
 public DirectorNativeCatalogueCoordinator(MinecraftServer server,WorldId world,JournalStore store,
   DirectorRuntimeContent content,Host host,Supplier<Map<DefinitionId,DirectorNativeCatalogueExecution.Driver>> driverFactory){
  this(server,world,store,content,content.config(),host,driverFactory);
 }
 private DirectorNativeCatalogueCoordinator(MinecraftServer server,WorldId world,JournalStore store,
   DirectorRuntimeContent content,ConfigView config,Host host,Supplier<Map<DefinitionId,DirectorNativeCatalogueExecution.Driver>> driverFactory){
  this.content=content;
  this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.store=Objects.requireNonNull(store);
  this.config=Objects.requireNonNull(config);this.host=Objects.requireNonNull(host);this.driverFactory=Objects.requireNonNull(driverFactory);
  actor();session=store.session();
  if(!world.equals(session.worldId())||(content==null?
   !Boolean.getBoolean("proutlost.director.nativeLab")||!DirectorLabObservationProfile.isVerifiedRuntime(config):!content.production()))throw new IllegalArgumentException("verified private LAB required");
  input=new DirectorNativeCatalogueInput(server,world,store,config,content==null?ProviderMode.TEST_ONLY:content.mode(),content==null?Map.of():content.profiles());
 }
 private static DefinitionId id(String name){return new DefinitionId("proutlost_director:candidate/"+name);}
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("catalogue coordinator actor");}
 private Tick tick(){return new Tick(DirectorStandaloneMod.directorLifecycle().runtimeClock(server));}
 private boolean operator(ServerPlayer player){return player!=null&&player.getServer()==server
  &&server.getPlayerList().getPlayer(player.getUUID())==player&&player.isAlive()&&player.hasPermissions(4);}
 public void resetRecoveryScan(){actor();input.resetRecoveryScan();}
 public void acceptRecoveryRows(List<DurableRecord> rows,boolean last,Sequence cut){
  actor();input.acceptRecoveryRows(rows,last,cut);
 }
 public void pollInputs(){
  actor();if(content!=null&&catalog==null&&host.ready())install(content.catalog(),Set.copyOf(content.catalog().primitives().values().stream().flatMap(p->p.capabilities().stream()).toList()));input.poll();
  boolean initialScan=events!=null&&events.recovering();
  // Drain every previously admitted REQ write, but do not start a new bus ACK.
  if(requirements!=null)requirements.poll(tick(),!initialScan);
  if(events==null)return;
  if(initialScan){
   if(!eventRecoveryLane){
    if(host.transitionPending()||requirements.writesInFlight()||!host.acquireMetadataLane(this))return;
    eventRecoveryLane=true;
    System.out.println("DIRECTOR_NATIVE_EVENT_RECOVERY_LANE_ACQUIRED physical_replay=false");
   }
   try{events.poll();}
   catch(RuntimeException error){releaseEventRecoveryLane();throw error;}
   if(events.failed()||!events.recovering())releaseEventRecoveryLane();
  }else events.poll();
 }
 private void releaseEventRecoveryLane(){
  if(!eventRecoveryLane)return;
  host.releaseMetadataLane(this);eventRecoveryLane=false;
  System.out.println("DIRECTOR_NATIVE_EVENT_RECOVERY_LANE_RELEASED "+events.status());
 }
 /** Read-only one-cut capture for the actual metadata owner. This does not
  * evaluate/admit an event, change control or synthesize observation revisions. */
 public Optional<DecisionInput> metadataReadbackInput(Object actualOwner){
  actor();if(catalog==null||executor==null||!host.ready()||host.metadataBusy()&&(actualOwner==null||!host.metadataHeldBy(actualOwner)))return Optional.empty();
  var before=store.residentAggregateKeys(Set.of(AggregateKind.WORLD),4096);if(before.isEmpty()||!before.orElseThrow().session().equals(session))return Optional.empty();
  var budget=host.budget();var tags=host.authoredRegionTags();if(budget.isEmpty()||tags.isEmpty())return Optional.empty();
  Optional<DirectorNativeCatalogueInput.Requests> requests=requirements.snapshot().map(r->new DirectorNativeCatalogueInput.Requests(r.session(),r.cut(),r.requests()));
  return input.capture(catalog,budget.orElseThrow(),capabilities,requests,tags.orElseThrow(),0).filter(c->c.cut().equals(before.orElseThrow().cut())).map(DirectorNativeCatalogueInput.Capture::input);
 }
 public Optional<DecisionInput> currentInput(){actor();return executor==null?Optional.empty():capture(0).map(executor::enrichInput);}
 private Optional<DecisionInput> capture(long seed){
  if(catalog==null||executor==null||!host.ready()||host.metadataBusy()||!host.enabled())return Optional.empty();
  var before=store.residentAggregateKeys(Set.of(AggregateKind.WORLD),4096);
  if(before.isEmpty()||!before.get().session().equals(session))return Optional.empty();
  var budget=host.budget();var tags=host.authoredRegionTags();
  if(budget.isEmpty()||tags.isEmpty())return Optional.empty();
  Optional<DirectorNativeCatalogueInput.Requests> requests=requirements.snapshot().map(r->
   new DirectorNativeCatalogueInput.Requests(r.session(),r.cut(),r.requests()));
  return input.capture(catalog,budget.get(),capabilities,requests,tags.get(),seed)
   .filter(c->c.cut().equals(before.get().cut()))
   .map(DirectorNativeCatalogueInput.Capture::input);
 }
 private DirectorNativeCatalogueExecution.GateResult requirementGate(SelectedEventStart selected){
  if(events!=null&&events.recovering())return new DirectorNativeCatalogueExecution.GateResult(
   DirectorNativeCatalogueExecution.GateStatus.WAITING,"WAIT_EVENT_METADATA_RECOVERY");
  var result=requirements.gate(selected);
  return new DirectorNativeCatalogueExecution.GateResult(
   DirectorNativeCatalogueExecution.GateStatus.valueOf(result.status().name()),result.detail());
 }
 public String configure(ServerPlayer player){
  actor();if(content!=null)content.requireLaboratory();if(!operator(player))return "Joueur OP4 vivant connecte requis.";
  if(catalog!=null)return "Catalogue deja configure: "+catalog.hash().value();
  if(!host.ready()||host.metadataBusy())return "WAIT_REAL_NATIVE_LANES";
  if(!session.equals(store.session())||store.readOnlySafe())return "J04 indisponible.";
  var exits=surveyExits(player);
  if(exits.isEmpty())return "Huit blocs consecutifs de sortie plane, chargee et praticable requis.";
  var candidate=new DirectorNativeCatalogueCatalog(config,exits);
  install(candidate.snapshot(),candidate.capabilities());
  detail="CONFIGURED_TEST_ONLY exits="+exits.size()+" capabilities="+capabilities.size();
  return detail+"; activation explicite puis ACK J04 requis.";
 }
 private void install(CatalogSnapshot snapshot,Set<CapabilityId> installedCapabilities){
  catalog=snapshot;
  var drivers=Map.copyOf(driverFactory.get());
  executor=content==null?new DirectorNativeCatalogueExecution(server,world,store,catalog,drivers):
   new DirectorNativeCatalogueExecution(server,world,store,content,drivers);
  host.bindPhysicalSites(executor);
  requirements=new NativeRequirementBridge(server,world,store,catalog.events().get(SIGNAL));
  events=content!=null&&content.declaredSynthetic()?DirectorNativeEventLifecycle.declaredSynthetic(server,world,store,content.qualificationInputs(),this::tick):new DirectorNativeEventLifecycle(server,world,store,catalog,this::tick,content==null?ProviderMode.TEST_ONLY:content.mode());
  Map<CapabilityId,CapabilityDescriptor> registered=new HashMap<>();
  if(executor.installed())for(var capability:installedCapabilities)registered.put(capability,
   new CapabilityDescriptor(capability,1,content==null?ProviderMode.TEST_ONLY:content.mode(),CapabilityStatus.AVAILABLE,
    new ResourceId("proutlost_director:native_catalogue"),content==null?Set.of("TEST_ONLY:REAL_NATIVE_BINDING"):Set.of()));
  capabilities=Map.copyOf(registered);
  executor.requirementBinding(requirements::executionStillBound);
  executor.completionHook(requirements::completionRecords);
  executor.abortHook(requirements::abortRecords);
  executor.preStartAbortGate(this::requirementGate);
  executor.preAdmissionGate((selected,journal)->{
   if(events.failed())return new DirectorNativeCatalogueExecution.GateResult(
    DirectorNativeCatalogueExecution.GateStatus.REJECTED,"actual EVENT metadata reader failed");
   if(events.recovering())return new DirectorNativeCatalogueExecution.GateResult(
    DirectorNativeCatalogueExecution.GateStatus.WAITING,"WAIT_EVENT_METADATA_RECOVERY");
   var binding=requirementGate(selected);
   if(binding.status()!=DirectorNativeCatalogueExecution.GateStatus.READY)return binding;
   if(content!=null){var envelope=globalEnvelope(selected,null);if(envelope.status()!=DirectorNativeCatalogueExecution.GateStatus.READY)return envelope;}
   return events.beginAudited(selected,journal,requirements.boundRequirements(selected));
  });
  executor.globalEnvelopeStaging((selected,authorized)->{if(content!=null)globalEnvelope(selected,authorized);});
  executor.liveContext(()->capture(0).map(executor::enrichInput));
  input.observer(executor::observe);
  for(var fact:input.knownFacts())executor.observe(new DurableFact(fact));
  host.bindSelectedContext(()->capture(0).map(executor::enrichInput));
  runtime=new DirectorNativeCatalogueRuntime(server,world,store,executor,this::capture,
   host::enabled,host::transitionPending,()->tick().value(),content==null?null:content.archiveWorker());
  runtime.globalBudget(host::workBudget);
  host.bindChainDriver(new DirectorNativeChainGameplay.Driver(){
   public boolean available(){return configuredAndReady();}
   public boolean supports(DefinitionId definition){return catalog.events().containsKey(definition);}
   public DirectorNativeChainGameplay.Launch start(ServerPlayer actor,DefinitionId definition,UUID event,
     RegionId region,ChainId parent){return runtime.enqueue(actor,definition,event,Optional.of(region),Optional.of(parent));}
   public DirectorNativeChainGameplay.Launch start(ServerPlayer actor,DefinitionId definition,UUID event){
    return runtime.enqueue(actor,definition,event,Optional.empty(),Optional.empty());
   }
   public Optional<DirectorNativeChainGameplay.Launch> pendingStart(UUID event){return runtime.pendingStart(event);}
   public void cancelPending(UUID event,String reason){runtime.cancelPending(event,reason);}
  });
  detail="CONFIGURED mode="+(content==null?ProviderMode.TEST_ONLY:content.mode());
 }
 private DirectorNativeCatalogueExecution.GateResult globalEnvelope(SelectedEventStart selected,BooleanSupplier authorized){
    var effects=executor.capturedEffects(selected.proposal().proposalId());
    var prepared=selected.plan().effects().stream().map(effect->Objects.requireNonNull(effects.get(effect.operationToken()))).toList();
    var audience=new HashSet<PlayerId>();selected.plan().roles().roles().values().forEach(audience::addAll);
    for(var snapshot:executor.capturedPlanningSnapshots(selected.proposal().proposalId()).getOrDefault(selected.plan().candidateId(),List.of())){
     var audienceBinding=snapshot.bindings().orElseThrow();if(!audienceBinding.audienceComplete())return new DirectorNativeCatalogueExecution.GateResult(DirectorNativeCatalogueExecution.GateStatus.WAITING,"INCOMPLETE_GLOBAL_AUDIENCE");
     audience.addAll(audienceBinding.incidentalAudience());
    }
    return content.envelopes().admit(selected,prepared,audience,authorized);
 }
 private boolean configuredAndReady(){
  return executor!=null&&executor.installed()&&events!=null&&!events.recovering()&&!events.failed()
   &&host.ready()&&!host.metadataBusy()&&host.enabled()&&session.equals(store.session())&&!store.readOnlySafe();
 }
 /** Called after real chain consent/cancellation polling in the same fresh phase. */
 public void pollAfterChains(){
  actor();if(runtime!=null){if(host.metadataBusy())runtime.deferEvaluationForMetadata();else runtime.pollAfterChains();}
 }
 public boolean adminResumeReady(){
  actor();return catalog!=null&&executor!=null&&executor.installed()&&capabilities.size()>=13&&
   (content!=null&&content.production()?catalog.events().size()>=9&&catalog.events().size()<=128:catalog.events().size()==9)&&catalog.chains().size()==2&&events!=null&&
   !events.recovering()&&!events.failed()&&host.ready()&&!host.metadataBusy()&&
   session.equals(store.session())&&!store.readOnlySafe();
 }
 public Optional<DirectorNativeCatalogueExecution> executor(){actor();return Optional.ofNullable(executor);}
 public Optional<DirectorNativeCatalogueRuntime> runtime(){actor();return Optional.ofNullable(runtime);}
 public Map<CapabilityId,CapabilityDescriptor> capabilities(){actor();return capabilities;}
 public Optional<AdminRow> inspect(EventInstance head,Sequence cut){
  actor();if(content==null||events==null)return Optional.empty();
  return content.liveClaims(head.instanceId(),tick()).flatMap(claims->events.inspect(head,cut,claims));
 }
 public Optional<AdminRow> inspect(ChainInstance chain,Sequence cut){
  actor();if(content==null)return Optional.empty();var definition=content.catalog().chains().get(chain.recipe().id());
  if(definition==null||definition.calibration()!=Calibration.APPROVED||!definition.contentRevision().equals(chain.recipe().revision())
   ||!BlobContent.hash(new fr.proutlost.director.api.codec.ModelCodec<>(ChainDefinition.class).encode(definition)).equals(chain.recipe().hash()))return Optional.empty();
  var facts=input.factsAt(cut);if(facts.isEmpty())return Optional.empty();
  var joined=facts.get().stream().filter(f->chain.consumedFactIds().contains(f.factId())).toList();
  if(!joined.stream().map(DirectorFact::factId).collect(java.util.stream.Collectors.toSet()).equals(chain.consumedFactIds()))return Optional.empty();
  return Optional.of(new AdminRow(new ScopeRef(ScopeKind.WORLD,world.value()),chain.header().revision(),chain.header().updatedTick(),new ChainAdminData(chain,joined)));
 }
 public Set<EventInstanceId> requestStopQueue(Optional<EventInstanceId> target){actor();return runtime==null?Set.of():runtime.requestStopQueue(target);}
 public boolean stopQueueComplete(Set<EventInstanceId> targets){actor();return runtime==null||runtime.stopQueueComplete(targets);}
 public boolean worldWriterIdle(){actor();return runtime==null||runtime.worldWriterIdle();}
 public Optional<Fingerprint> configuredHash(){actor();return catalog==null?Optional.empty():Optional.of(catalog.hash());}
 public String onOff(ServerPlayer player,boolean run){
  actor();if(!operator(player))return "Joueur OP4 vivant connecte requis.";
  if(catalog==null){String configured=configure(player);if(catalog==null)return configured;}
  if(run&&events.recovering())return "WAIT_EVENT_METADATA_RECOVERY "+events.status();
  return host.control(player,run,catalog,executor.installed()&&capabilities.size()>=13);
 }
 public String event(ServerPlayer player,String name){
  actor();if(!operator(player))return "Joueur OP4 vivant connecte requis.";
  if(runtime==null||!host.enabled())return "Catalogue TEST_ONLY non active.";
  DefinitionId definition;
  try{definition=name.contains(":")?new DefinitionId(name):id(name);}catch(IllegalArgumentException bad){return "Definition invalide.";}
  if(!catalog.events().containsKey(definition))return "Evenement original inconnu.";
  var p=player.blockPosition();var region=DirectorStandaloneMod.directorLifecycle().runtimePrimaryRegion(server,
   new BlockPosition(new DimensionId(player.serverLevel().dimension().location().toString()),p.getX(),p.getY(),p.getZ()));
  var result=runtime.enqueue(player,definition,UUID.randomUUID(),region,Optional.empty());
  return result.message();
 }
 public String automatic(ServerPlayer player,boolean run){
  actor();if(!operator(player))return "Joueur OP4 vivant connecte requis.";
  return runtime==null?"Catalogue non configure.":runtime.automatic(run);
 }
 public String requireSignal(ServerPlayer player,String key){
  actor();if(events!=null&&events.recovering())return "WAIT_EVENT_METADATA_RECOVERY";
  return requirements==null?"Catalogue non configure.":requirements.requireSignal(player,key);
 }
 public String status(){
  actor();return "CATALOGUE "+detail+" | "+input.diagnostic()
   +(runtime==null?"":" | "+runtime.status()+" | "+requirements.status()+" | "+events.status());
 }
 public boolean eventRecoveryLaneHeld(){actor();return eventRecoveryLane;}
 /** Native source geometry only. No blocks are placed and no chunks are loaded. */
 static List<RouteSegment> surveyExits(ServerPlayer player){
  var level=player.serverLevel();var origin=player.blockPosition();var dimension=new DimensionId(level.dimension().location().toString());
  List<RouteSegment> result=new ArrayList<>();
  for(var direction:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)){
   boolean valid=true;
   for(int step=0;step<=8;step++){
    var p=origin.relative(direction,step);
    if(!level.hasChunkAt(p)||!level.getWorldBorder().isWithinBounds(p)
      ||p.getY()<=level.getMinBuildHeight()||p.getY()+1>=level.getMaxBuildHeight()){valid=false;break;}
    var feet=level.getBlockState(p);var head=level.getBlockState(p.above());var floor=level.getBlockState(p.below());
    if(!feet.isAir()||!head.isAir()||!feet.getFluidState().isEmpty()||!head.getFluidState().isEmpty()
      ||floor.hasBlockEntity()||!floor.getFluidState().isEmpty()
      ||!BuiltInRegistries.BLOCK.getKey(floor.getBlock()).getNamespace().equals("minecraft")
      ||!floor.isSolidRender(level,p.below())){valid=false;break;}
   }
   if(valid){var target=origin.relative(direction,8);
    result.add(new RouteSegment(new BlockPosition(dimension,origin.getX(),origin.getY(),origin.getZ()),
     new BlockPosition(dimension,target.getX(),target.getY(),target.getZ()),.5));}
  }
  return List.copyOf(result);
 }
 @Override public void close(){
  actor();try{if(runtime!=null)runtime.close();releaseEventRecoveryLane();if(requirements!=null)requirements.closeAsync();}
  finally{closed=true;}
 }
}
