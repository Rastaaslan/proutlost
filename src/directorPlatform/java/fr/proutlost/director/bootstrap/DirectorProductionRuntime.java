package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap;
import fr.proutlost.director.adapter.neoforge.server.commands.DirectorCommands;
import fr.proutlost.director.admin.server.*;
import fr.proutlost.director.engine.WorldDirector;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;

/** World-local assembly of the existing observation, journal, planning and
 * native execution algorithms. Operator content grants no commit permit.
 * All callbacks touching Minecraft remain on this world's server actor. */
public final class DirectorProductionRuntime implements DirectorCommands.Access,AutoCloseable {
 public enum Phase { RECOVERING,PAUSED,RUNNING,FAILED_SAFE,STOPPING,CLOSED }
 private final MinecraftServer server;private final JournalStore store;private final StoreSession session;
 private final WorldId world;private final DirectorOperatorBundle bundle;private final IEventBus bus;
 private final DirectorRuntimeContent content;private final DirectorNativeWorldStateBootstrap boot;
 private final DirectorNativeMemoryProjectionQualification memory;
 private final DirectorNativeGameplay gameplay;
 private final DirectorNativePhysicalCatalogues physicalCatalogues;
 private final DirectorProductionRegionalWeather regionalWeather;
 private final DirectorReadOnlyInputs readOnlyInputs;
 private DirectorNativeObservationQualification observation;
 private DirectorObservationWalIngestion ingress;private DirectorNativeAnchorWalIngestion anchors;
 private AdminProjectionPages pages;
 private DirectorAdminAssembly.Components administration;private AdminOperationDispatcher operations;
 private final ActorAuthorityPort authority=this::identity;
 private final SessionId consoleSession;
 private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
  new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"director-admin-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
 private final ConcurrentLinkedQueue<Runnable> actorCompletions=new ConcurrentLinkedQueue<>();
 private final List<RuntimeAdminAudit> recoveredAdmin=new ArrayList<>();private final List<DirectorFact> recoveredIntents=new ArrayList<>();
 private final Map<TransactionId,PrepareRecord> prepares=new LinkedHashMap<>();
 private CompletableFuture<StoreReadResult> reading;private StoreQuery query;private Optional<String> cursor=Optional.empty();
 private Sequence historyCut=new Sequence(0);private boolean scanned,recoveryVerified;
 private long logicalBase,processBase,lastClock,lastPublished=-1,lastPublishedCut=-1,revokedAdminHistory,durableClockFloor;
 private boolean worldBudgetPublished,activePublicationComplete;
 private long envelopeGenerationFloor;
 private DirectorReleaseDiagnostic.Report releaseReport;private Phase phase=Phase.RECOVERING;
 private DirectorBootstrap.Composition graph;private String diagnostic="RECOVERING";
 private DirectorWorldState healthExpected;
 private final String bootstrapImport=System.getProperty("proutlost.director.locationImport","").strip();
 private boolean bootstrapImportRequested,consumersClosed,shutdownDrained;
 private AdminCommand shutdownCommand;private AdminResult shutdownResult;private ActorIdentity shutdownActor;
 private final Set<SessionId> knownAdminSessions=new HashSet<>();
 private DurableBatch healthBatch;private CompletableFuture<CommitAck> healthAck;
 public DirectorProductionRuntime(MinecraftServer server,JournalStore store,DirectorOperatorBundle bundle,IEventBus bus){
  this.server=Objects.requireNonNull(server);this.store=Objects.requireNonNull(store);this.bundle=Objects.requireNonNull(bundle);this.bus=Objects.requireNonNull(bus);
  actor();session=store.session();world=session.worldId();
  if(store.readOnlySafe()||bundle.configuration().calibration()!=Calibration.APPROVED)throw new IllegalArgumentException("verified production writer/profile required");
  consoleSession=new SessionId(UUID.nameUUIDFromBytes(("director-console/"+world.value()+"/"+session.bootEpoch().value()).getBytes(StandardCharsets.UTF_8)));
  logicalBase=server.getTickCount();processBase=server.getTickCount();lastClock=logicalBase;
  content=new DirectorRuntimeContent(ProviderMode.PRODUCTION,bundle.configuration(),bundle.catalog(),bundle.profiles());content.archiveWorker(worker);
  readOnlyInputs=new DirectorReadOnlyInputs(store,ProviderMode.PRODUCTION,worker,server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("proutlost/director-fixtures"));
  boot=new DirectorNativeWorldStateBootstrap(server,world,store,bundle.configuration(),ProviderMode.PRODUCTION,bundle.catalog().hash(),bundle.defaultProfile());
  memory=new DirectorNativeMemoryProjectionQualification(server,world,store,bundle.configuration(),ProviderMode.PRODUCTION);
  gameplay=new DirectorNativeGameplay(server,world,store,content);
  physicalCatalogues=new DirectorNativePhysicalCatalogues(server,world,store,gameplay,this::identity,this::identity,Optional::empty);
  gameplay.physicalCatalogues(physicalCatalogues);
  regionalWeather=new DirectorProductionRegionalWeather(server,store,this,gameplay,content);gameplay.regionalWeather(regionalWeather);gameplay.install(bus);
  pages=new AdminProjectionPages(world,4096,128,22000);
 }
 private void actor(){if(!server.isSameThread())throw new IllegalStateException("production Director actor");}
 public long clock(){actor();lastClock=Math.max(lastClock,Math.addExact(logicalBase,Math.max(0,(long)server.getTickCount()-processBase)));return lastClock;}
 public Phase phase(){actor();return phase;}public String diagnostic(){actor();return diagnostic;}
 public Optional<DirectorBootstrap.Composition> composition(){actor();return Optional.ofNullable(graph);}
 public Optional<DirectorReleaseDiagnostic.Report> releaseDiagnostic(){actor();return Optional.ofNullable(releaseReport);}
 public int missingPorts(){return graph==null?9:(int)graph.missingPorts().stream().filter(p->!p.equals("QuestDirectorPort")&&!p.equals("AiAdvisorPort")).count();}
 public Optional<DirectorNativeGameplay> gameplay(){actor();return phase==Phase.CLOSED?Optional.empty():Optional.of(gameplay);}
 public DirectorRegionalWeatherRuntime regionalWeather(){actor();return regionalWeather.runtime();}
 public fr.proutlost.director.api.port.PhysicalCatalogRuntimePort physicalCatalogues(){actor();return physicalCatalogues;}
 public Optional<MemoryView> memory(){actor();return memory.view();}
 public Optional<DirectorNativeMemoryProjectionQualification.View> memoryStatus(){actor();return Optional.of(memory.status());}
 public DirectorWorldState worldState(){actor();return currentWorld();}
 public CatalogSnapshot catalogue(){actor();return content.catalog();}
 public boolean storeReadOnlySafe(){actor();return store.readOnlySafe();}
 public Optional<DirectorNativeObservationQualification.View> observationStatus(){actor();return observation==null?Optional.empty():Optional.of(observation.view());}
 public Optional<DirectorObservationWalIngestion.View> ingestionStatus(){actor();return ingress==null?Optional.empty():Optional.of(ingress.view());}
 public Optional<SnapshotBatch> snapshot(){actor();return observation==null?Optional.empty():observation.snapshot();}
 public Optional<Tick> observedTick(){actor();return observation==null?Optional.empty():Optional.of(new Tick(observation.now()));}
 public Optional<RegionId> primary(BlockPosition p){actor();return observation==null?Optional.empty():observation.indexedPrimary(p);}
 public boolean metadataQuiescent(){
  actor();if(observation==null||ingress==null||anchors==null||!memory.status().complete()||memory.status().failClosed())return false;
  var state=ingress.view().phase();return !observation.anchorOwner().traceBusy()&&observation.nextUncommittedAnchor().isEmpty()
   &&anchors.status().phase()==DirectorNativeAnchorWalIngestion.Phase.IDLE
   &&(state==DirectorObservationWalIngestion.Phase.IDLE||state==DirectorObservationWalIngestion.Phase.WAITING_FOR_MEMORY);
 }
 public boolean anchorWritesQuiescent(){return metadataQuiescent()&&!gameplay.metadataLaneBusy();}
 public Optional<Map<RegionId,Set<TagId>>> regionTags(){actor();return gameplay.importedRegionTags();}
 private void fail(RuntimeException failure){phase=Phase.FAILED_SAFE;diagnostic=failure.getClass().getSimpleName()+":"+failure.getMessage();
  gameplay.coordinator().runtime().ifPresent(runtime->runtime.automatic(false));
  // Stop ingress callbacks immediately, including the remaining listeners of
  // this tick. Preserve captured data and pending durable work for cleanup.
  if(observation!=null)observation.suspendSampling();
  System.out.println("DIRECTOR_PRODUCTION_FAILED_SAFE "+diagnostic);}
 public void poll(){
  actor();if(phase==Phase.CLOSED||phase==Phase.STOPPING||phase==Phase.FAILED_SAFE)return;
  try{
   if(!session.equals(store.session())||store.readOnlySafe())throw new IllegalStateException("production WAL authority revoked");
   pruneAdminSessions();
   for(int n=0;n<128;n++){var task=actorCompletions.poll();if(task==null)break;task.run();}
   boot.poll();if(boot.status().phase()==DirectorNativeWorldStateBootstrap.Phase.FAILED_SAFE)throw new IllegalStateException("production WORLD recovery failed");
   if(boot.world().isEmpty())return;
   if(!scanned&&observation==null){pollHistory();return;}
   if(scanned){content.envelopes().poll();if(content.envelopes().failed())throw new IllegalStateException("global event envelope failed safe");}
   memory.poll();if(memory.status().failClosed())throw new IllegalStateException("production memory unavailable");
   gameplay.poll();physicalCatalogues.tick();regionalWeather.poll();if(!memory.status().complete())return;
   if(observation==null){
    long floor=Math.max(durableClockFloor,Math.max(clock(),boot.world().orElseThrow().simTick().value()));
    for(var region:memory.view().orElseThrow().regions())floor=Math.max(floor,region.header().updatedTick().value());
    for(var player:memory.view().orElseThrow().operationalPlayers())floor=Math.max(floor,player.header().updatedTick().value());
    logicalBase=floor;processBase=server.getTickCount();lastClock=floor;
    observation=new DirectorNativeObservationQualification(server,world,session.bootEpoch(),memory.view().orElseThrow(),bundle.configuration(),ProviderMode.PRODUCTION);
    observation.register(bus);
    ingress=new DirectorObservationWalIngestion(server,world,store,observation,memory,ProviderMode.PRODUCTION);
    anchors=new DirectorNativeAnchorWalIngestion(server,world,store,observation,memory,ProviderMode.PRODUCTION);
   }
   observation.refreshRegionIndex(memory.view().orElseThrow(),memory.status().lastDurableSequence());observation.tick();
   gameplay.bindAnchorOwner(observation.anchorOwner());
   if(!recoveryVerified){
    anchors.poll(false);ingress.poll(false);
    if(gameplay.recovering())return;
    if(!scanned){pollHistory();return;}
    if(!prepares.isEmpty()){resetHistory();return;}
    recoveryVerified=true;
   }
   gameplay.pollLabChainsAtFreshContext();
   if(!bootstrapImport.isEmpty()){
    if(!bootstrapImportRequested)bootstrapImportRequested=gameplay.bootstrapLocations(bootstrapImport,content);
    if(gameplay.bootstrapLocationsFailed(content))throw new IllegalStateException("operator location import rejected");
    if(!bootstrapImportRequested||!gameplay.bootstrapLocationsComplete(content))return;
   }
   anchors.poll(!gameplay.metadataLaneBusy());ingress.poll(!gameplay.metadataLaneBusy());
   if(ingress.view().phase()==DirectorObservationWalIngestion.Phase.FAILED_SAFE)
    throw new IllegalStateException("production observation ingress failed safe");
   // The original native recoverers may write fresh cleanup PREPARE/results;
   // no new event admission is available until their actual recovery is done.
   if(gameplay.recovering())return;
   var executor=gameplay.coordinator().executor();if(executor.isEmpty())return;
   if(gameplay.coordinator().runtime().map(DirectorNativeCatalogueRuntime::failedSafe).orElse(false))throw new IllegalStateException("production scheduler failed safe");
   if(administration==null)installAdministration();
   refreshComposition(executor.get());
   if(graph==null||missingPorts()!=0)throw new IllegalStateException("mandatory production ports absent");
   if(!healthyPaused())return;
   if(operations!=null)operations.pump();
   administration.tick(gameplay.coordinator().worldWriterIdle()&&metadataQuiescent()&&!gameplay.metadataLaneBusy());
   publish();
   var current=currentWorld();var previousPhase=phase;phase=current.runControl()==RunControl.RUNNING?Phase.RUNNING:Phase.PAUSED;
   if(phase==Phase.RUNNING&&previousPhase!=Phase.RUNNING)gameplay.coordinator().runtime().ifPresent(r->r.automatic(true));
   diagnostic="mode=PRODUCTION phase="+phase+" ports=9 profile="+current.intensityProfileId().value();
  }catch(RuntimeException failure){fail(failure);}
 }
 private void resetHistory(){scanned=false;cursor=Optional.empty();reading=null;prepares.clear();recoveredAdmin.clear();recoveredIntents.clear();}
 private void pollHistory(){
  if(reading==null){query=new StoreQuery(world,Set.of(),new Sequence(0),cursor,512);var resident=store.view(query);
   reading=resident.status()==ReadStatus.NOT_RESIDENT?store.read(query).toCompletableFuture():CompletableFuture.completedFuture(resident);return;}
  if(!reading.isDone())return;var read=reading.getNow(null);reading=null;ReadContracts.validate(read,query,session);
  if(read.status()==ReadStatus.STALE){cursor=Optional.empty();prepares.clear();recoveredAdmin.clear();recoveredIntents.clear();return;}
  if(read.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("production recovery page unavailable");
  var page=read.page().orElseThrow();for(var restored:page.aggregates())rememberClock(restored.state());for(var row:page.records()){
   rememberClock(row);
   if(row instanceof DurablePrepare p){prepares.put(p.value().transactionId(),p.value());if(prepares.size()>4096)throw new IllegalStateException("unresolved production recovery capacity");}
   else if(row instanceof DurableResult result)prepares.remove(result.value().prepareTransactionId());
   else if(row instanceof DurableRuntimeAdminAudit a){
    if(!a.audit().before().worldId().equals(world)||!a.audit().after().worldId().equals(world))throw new IllegalStateException("foreign durable admin audit");
    if(restoreBoot(a.audit().before().bootEpoch()))recoveredAdmin.add(a.audit());
   }
   else if(row instanceof DurableFact f&&f.value().factType().equals(AdminOperationDispatcher.TYPE)){
    var fact=f.value();var payload=fact.payload();
    if(!fact.durable()||fact.evidenceStatus()!=EvidenceStatus.CONFIRMED||fact.eventId().isPresent()
     ||!payload.keySet().equals(Set.of("command","command_hash","authority","before","session")))throw new IllegalStateException("corrupt admin intent");
    var command=decode(AdminCommand.class,payload.get("command"));var applied=decode(ActorIdentity.class,payload.get("authority"));
    var before=decode(AdminSnapshotStamp.class,payload.get("before"));var saved=decode(StoreSession.class,payload.get("session"));
    var expectedId=UUID.nameUUIDFromBytes((world.value()+"/admin-operation/"+command.actorSession().value()+"/"+command.requestId().value()+"/intent").getBytes(StandardCharsets.UTF_8));
    if(!saved.worldId().equals(world)||!before.worldId().equals(world)||!saved.storeGeneration().equals(session.storeGeneration())
     ||!saved.bootEpoch().equals(before.bootEpoch())||!AdminOperationDispatcher.OPERATIONS.contains(command.operation())||!AdminContracts.authorized(command,applied)
     ||!fact.subjectRefs().equals(List.of(new ScopeRef(ScopeKind.WORLD,world.value())))||!fact.factId().value().equals(expectedId)
     ||!new JsonValue.Text(BlobContent.hash(new ModelCodec<>(AdminCommand.class).encode(command)).value()).equals(payload.get("command_hash")))
     throw new IllegalStateException("admin intent binding mismatch");
    if(restoreBoot(before.bootEpoch()))recoveredIntents.add(fact);
   }
  }
  cursor=page.nextCursor();if(!page.hasMore()){historyCut=page.lastDurableSequence();scanned=true;content.bindEnvelopes(store,()->new Tick(clock()),new Epoch(Math.max(session.bootEpoch().value(),envelopeGenerationFloor)));
   long unresolved=prepares.size();
   if(unresolved>0){diagnostic="RECOVERY_PENDING_NATIVE_PREPARES="+unresolved;}
  }
 }
 private void rememberClock(AggregateState state){
  durableClockFloor=Math.max(durableClockFloor,state.header().updatedTick().value());
  if(state instanceof WorldAggregate world)durableClockFloor=Math.max(durableClockFloor,world.value().simTick().value());
  if(state instanceof EntityOwnershipAggregate entity)durableClockFloor=Math.max(durableClockFloor,entity.value().lastSeenTick().value());
 }
 private void rememberClock(DurableRecord row){
  if(row instanceof DurablePrepare prepared)envelopeGenerationFloor=Math.max(envelopeGenerationFloor,prepared.value().reservationGeneration().value());
  if(row instanceof DurableUnique unique)unique.value().reservationToken().ifPresent(token->envelopeGenerationFloor=Math.max(envelopeGenerationFloor,token.generation().value()));
  if(row instanceof DurableFact lease&&lease.value().factType().equals(DirectorNativeEventEnvelopes.TYPE)){
   var token=decode(ReservationToken.class,lease.value().payload().get("token"));var saved=decode(StoreSession.class,lease.value().payload().get("session"));
   if(!token.worldId().equals(world)||!saved.worldId().equals(world)||lease.value().eventId().filter(token.instanceId()::equals).isEmpty())throw new IllegalStateException("foreign envelope history");
   envelopeGenerationFloor=Math.max(envelopeGenerationFloor,token.generation().value());
  }
  if(row instanceof DurableAggregate aggregate)rememberClock(aggregate.state());
  else if(row instanceof DurableOwnership owner)durableClockFloor=Math.max(durableClockFloor,owner.value().header().updatedTick().value());
  else if(row instanceof DurableMutation mutation)durableClockFloor=Math.max(durableClockFloor,mutation.value().header().updatedTick().value());
  else if(row instanceof DurableUnique unique)durableClockFloor=Math.max(durableClockFloor,unique.value().header().updatedTick().value());
  else if(row instanceof DurableRequest request)durableClockFloor=Math.max(durableClockFloor,request.request().state().header().updatedTick().value());
  else if(row instanceof DurableMemoryDelta delta)for(var value:delta.value().changes().values())durableClockFloor=Math.max(durableClockFloor,value.observedTick().value());
  else if(row instanceof DurableAudit audit)durableClockFloor=Math.max(durableClockFloor,audit.value().context().capturedTick().value());
  else if(row instanceof DurableRuntimeAdminAudit audit)durableClockFloor=Math.max(durableClockFloor,audit.audit().after().capturedTick().value());
 }
 private static <T>T decode(Class<T> type,JsonValue value){return new ModelCodec<>(type).decode(CanonicalJsonV1.encode(value));}
 private boolean restoreBoot(Epoch saved){
  if(saved.compareTo(session.bootEpoch())>0)throw new IllegalStateException("future admin boot epoch");
  if(saved.compareTo(session.bootEpoch())<0){revokedAdminHistory++;return false;}
  return true;
 }
 private record VerifiedWorld(DirectorWorldState value,Sequence cut){}
 private VerifiedWorld verifiedWorld(){
  var query=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.WORLD,world.value())),new Sequence(0),Optional.empty(),1);
  var read=store.view(query);ReadContracts.validate(read,query,session);
  if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().aggregates().size()!=1
   ||!(read.page().orElseThrow().aggregates().getFirst().state() instanceof WorldAggregate aggregate))throw new IllegalStateException("production WORLD unavailable");
  var current=aggregate.value();if(!current.bootEpoch().equals(session.bootEpoch())||!current.storeGeneration().equals(session.storeGeneration()))throw new IllegalStateException("stale production WORLD");
  return new VerifiedWorld(current,read.page().orElseThrow().lastDurableSequence());
 }
 private DirectorWorldState currentWorld(){return verifiedWorld().value();}
 private AdminSnapshotStamp stamp(){var current=verifiedWorld();return new AdminSnapshotStamp(world,session.bootEpoch(),current.value().header().revision(),new Tick(clock()),current.cut());}
 private AdminReadResult projected(AdminQuery query){
  var current=verifiedWorld();
  if(query.expectedSnapshotRevision().filter(r->!r.equals(current.value().header().revision())).isPresent())return unavailableProjection();
  if(query.panel()==AdminPanel.ACTIVE&&query.filter() instanceof EventFilter filter&&filter.instanceId().isPresent()&&query.cursor().isEmpty()){
   var key=new AggregateKey(AggregateKind.EVENT,filter.instanceId().get().value());
   var readQuery=new StoreQuery(world,Set.of(key),new Sequence(0),Optional.empty(),1);var read=store.view(readQuery);ReadContracts.validate(read,readQuery,session);
   if(read.status()!=ReadStatus.AVAILABLE||!read.page().orElseThrow().lastDurableSequence().equals(current.cut()))return unavailableProjection();
   if(!read.page().orElseThrow().aggregates().isEmpty()&&read.page().orElseThrow().aggregates().getFirst().state() instanceof EventAggregate event){
    var joined=gameplay.coordinator().inspect(event.value(),current.cut());
    if(joined.isPresent())return new AdminReadResult(AdminStatus.SUCCEEDED,List.of(joined.get()),Optional.empty(),Optional.empty(),Optional.of(new AdminSnapshotStamp(world,session.bootEpoch(),current.value().header().revision(),new Tick(clock()),current.cut())));
    return unavailableProjection();
   }
  }
  if(query.panel()==AdminPanel.ACTIVE||query.panel()==AdminPanel.DECISIONS){
   var snapshot=query.panel()==AdminPanel.ACTIVE?DirectorNativeAdminReadBridge.activePage(store,query,current.value()):
    DirectorNativeAdminReadBridge.decisionPage(store,query,current.value());
   if(query.panel()==AdminPanel.ACTIVE&&snapshot.isEmpty())return activePublicationComplete?pages.query(query,identity(query.actorSession()).orElseThrow(),session.bootEpoch()):unavailableProjection();
   return DirectorNativeAdminReadBridge.sameWalCut(snapshot,current.cut()).map(value->new AdminReadResult(AdminStatus.SUCCEEDED,value.rows(),Optional.empty(),value.nextCursor(),
    Optional.of(new AdminSnapshotStamp(world,session.bootEpoch(),value.snapshotRevision(),value.capturedTick(),value.lastDurableSequence())))).orElseGet(this::unavailableProjection);
  }
  if(query.panel()==AdminPanel.MEMORY&&(!memory.status().complete()||memory.status().lastDurableSequence()!=current.cut().value()))return unavailableProjection();
  if(query.panel()==AdminPanel.WORLD&&!worldBudgetPublished)return unavailableProjection();
  return pages.query(query,identity(query.actorSession()).orElseThrow(),session.bootEpoch());
 }
 private AdminReadResult unavailableProjection(){return new AdminReadResult(AdminStatus.DEFERRED,List.of(),Optional.empty(),Optional.empty(),Optional.empty());}
 private void installAdministration(){
  var calculations=readOnlyInputs.calculations();
  var exporter=new AdminAuditExporter(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("proutlost/director-exports"),worker,1024*1024);
  administration=DirectorAdminAssembly.create(store,authority,
   this::projected,this::currentWorld,bundle.profiles(),this::clock,this::stamp,
   change->{if(!currentWorld().header().revision().equals(change.after().snapshotRevision()))throw new IllegalStateException("admin WORLD readback changed");},
   bundle.configuration(),calculations,exporter,readOnlyInputs::audit,worker,r->{if(actorCompletions.size()>=128)throw new RejectedExecutionException("actor mailbox full");actorCompletions.add(r);},
   controls->{controls.afterCommitted((command,change)->{if(command.operation()==AdminOperation.RESUME)gameplay.coordinator().runtime().ifPresent(runtime->runtime.automatic(true));});operations=new AdminOperationDispatcher(store,authority,this::stamp,controls,new AdminOperationDispatcher.Hooks(){
    public AdminResult launch(AdminCommand command,ActorIdentity actor){return launchPhysical(command,actor);}
    public Optional<AdminResult> progress(RuntimeAdminKey key){return progressPhysical(key);}
   },128);return new NativeAdminReads(operations);},128,ProviderMode.PRODUCTION);
  recoveredIntents.forEach(operations::restore);recoveredAdmin.forEach(operations::restore);
 }
 private void refreshComposition(DirectorNativeCatalogueExecution executor){
  var roots=new ArrayList<DirectorBootstrap.Provider>();
  for(Object provider:List.of(observation.observationPort(),memory.memoryPort(),content,executor,
   content.admission(),store,administration.access()))roots.add(new DirectorBootstrap.Provider(provider,ProviderMode.PRODUCTION,List.of(),List.of()));
  roots.add(new DirectorBootstrap.Provider(content.effects(),ProviderMode.PRODUCTION,List.of(),List.copyOf(gameplay.coordinator().capabilities().values())));
  for(var primitive:content.primitives())roots.add(new DirectorBootstrap.Provider(primitive,ProviderMode.PRODUCTION,List.of(),List.of()));
  graph=DirectorBootstrap.compose(ProviderMode.PRODUCTION,roots);
  releaseReport=DirectorReleaseDiagnostic.inspect(graph,content.config(),content.catalog(),bundle.references().capabilities(),content.effects());
 }
 /** Health changes only after real port assembly and native recovery. Still PAUSED;
  * the canonical authenticated RESUME command is the explicit run control. */
 private boolean healthyPaused(){
  var current=currentWorld();if(healthBatch==null&&(current.healthMode()==HealthMode.NORMAL||current.healthMode()==HealthMode.DEGRADED))return true;
  if(healthBatch==null){
   var tick=new Tick(clock());var next=new DirectorWorldState(new AggregateHeader(1,current.header().id(),current.header().revision().plus(1),current.header().lastSequence(),tick),world,
    session.storeGeneration(),tick,RunControl.PAUSED,HealthMode.NORMAL,current.pacingState(),current.tension(),current.intensityProfileId(),content.catalog().hash(),current.configHash(),session.bootEpoch(),current.lastCheckpointSeq(),current.engineControl());
   healthExpected=next;
   healthBatch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),session.storeGeneration(),Map.of(new ScopeRef(ScopeKind.WORLD,world.value()),current.header().revision()),List.of(new DurableAggregate(new WorldAggregate(next))),List.of());
   healthAck=store.append(healthBatch).toCompletableFuture();return false;
  }
  if(!healthAck.isDone())return false;var ack=healthAck.getNow(null);
  if(ack==null||ack.ackStatus()!=AckStatus.DURABLE||!ack.transactionId().equals(healthBatch.transactionId())||!ack.transactionHash().equals(healthBatch.transactionHash())
   ||!ack.storeGeneration().equals(session.storeGeneration())||!session.equals(store.session())||ack.durableSequence().value()<1
   ||ack.permitAttempt().isPresent()||!ack.forcedBlobs().equals(DurableBlobReferences.collect(healthBatch.records()))
   ||!currentWorld().equals(healthExpected))throw new IllegalStateException("production health ACK not exact");
  healthBatch=null;healthAck=null;healthExpected=null;return currentWorld().healthMode()==HealthMode.NORMAL;
 }
 private void publish(){
  var verified=verifiedWorld();var current=verified.value();var cut=verified.cut();
  if(cut.value()==lastPublishedCut&&current.header().revision().value()==lastPublished)return;
  if(!memory.status().complete()||memory.status().lastDurableSequence()!=cut.value())return;
  var config=AdminWorldControlHandler.configuration(current,bundle.profiles());
  Optional<BudgetView> budget=Optional.empty();
  var discovery=store.residentBudgetAggregateKeys(4096);
  if(discovery.isPresent()&&discovery.get().session().equals(session)&&discovery.get().cut().equals(cut)&&discovery.get().keys().size()<=1024){
   var keys=discovery.get().keys().isEmpty()?Set.of(new AggregateKey(AggregateKind.WORLD,world.value())):discovery.get().keys();
   var query=new StoreQuery(world,keys,new Sequence(0),Optional.empty(),1024);var read=store.view(query);ReadContracts.validate(read,query,session);
   if(read.status()==ReadStatus.AVAILABLE&&!read.page().orElseThrow().hasMore()&&read.page().orElseThrow().lastDurableSequence().equals(cut))
    budget=Optional.of(CatalogueBudgetProjection.from(config,read.page().orElseThrow().aggregates().stream().map(RestoredAggregate::state).toList()));
  }
  var data=budget.<AdminData>map(value->new WorldAdminData(current,value,Map.of(),List.of())).orElseGet(()->
   new PublicStatusData(current.healthMode(),current.runControl()!=RunControl.RUNNING,Set.of(ReasonCode.CAPABILITY_MISSING)));
  var rows=new ArrayList<AdminRow>();rows.add(new AdminRow(new ScopeRef(ScopeKind.WORLD,world.value()),current.header().revision(),current.simTick(),data));
  var remembered=memory.view().orElseThrow();var tags=gameplay.importedRegionTags().orElse(Map.of());
  for(var row:DirectorNativeAdminReadBridge.memoryRows(new RegionFilter(Optional.empty(),Set.of()),remembered)){
   var region=(RegionAdminData)row.data();rows.add(new AdminRow(row.scope(),row.revision(),row.capturedTick(),new RegionAdminData(region.region(),region.memory(),
    tags.getOrDefault(region.region().regionId(),Set.of()),region.anchors(),region.locations())));
  }
  for(var player:remembered.operationalPlayers())rows.addAll(DirectorNativeAdminReadBridge.memoryRows(new PlayerFilter(player.playerId()),remembered));
  boolean activeComplete=false;
  var active=store.residentPlanningAggregateKeys(Set.of(AggregateKind.EVENT,AggregateKind.CHAIN,AggregateKind.DISASTER),128);
  if(active.isPresent()&&active.get().cut().equals(cut)){
   if(active.get().keys().isEmpty())activeComplete=true;
   else{
    var query=new StoreQuery(world,active.get().keys(),new Sequence(0),Optional.empty(),128);var read=store.view(query);ReadContracts.validate(read,query,session);
    if(read.status()==ReadStatus.AVAILABLE&&!read.page().orElseThrow().hasMore()&&read.page().orElseThrow().lastDurableSequence().equals(cut)){
     activeComplete=true;
     for(var restored:read.page().orElseThrow().aggregates()){
      Optional<AdminRow> joined=restored.state() instanceof EventAggregate event?gameplay.coordinator().inspect(event.value(),cut):
       restored.state() instanceof ChainAggregate chain?gameplay.coordinator().inspect(chain.value(),cut):
       AdminActiveWalProjection.row(store,restored.state(),new EventFilter(Optional.empty(),Set.of()),world,cut);
      if(joined.isPresent())rows.add(joined.get());else activeComplete=false;
     }
    }
   }
  }
  if(rows.size()>4096)return; // explicit old coherent publication; never abort the runtime for a GUI cache bound.
  var replacement=new AdminProjectionPages(world,4096,128,22000);
  replacement.publish(world,current.header().revision(),new Tick(clock()),cut,rows,capabilities());
  activePublicationComplete=activeComplete;worldBudgetPublished=budget.isPresent();pages=replacement;lastPublished=current.header().revision().value();lastPublishedCut=cut.value();
 }
 public Set<AdminOperation> capabilities(){return Set.of(AdminOperation.STATUS,AdminOperation.UI,AdminOperation.PAUSE,AdminOperation.RESUME,AdminOperation.DRAIN,AdminOperation.TENSION,AdminOperation.INTENSITY,AdminOperation.TRIGGER,AdminOperation.STOP,AdminOperation.STABILIZE,AdminOperation.RESCUE,AdminOperation.DEBUG,AdminOperation.REPORT,AdminOperation.SIMULATE,AdminOperation.REPLAY,AdminOperation.AUDIT_EXPORT,AdminOperation.EVENT_LIST,AdminOperation.EVENT_INSPECT,AdminOperation.MUTATION_INSPECT,AdminOperation.REGION,AdminOperation.PLAYER,AdminOperation.CHAIN);}
 private final class NativeAdminReads implements RuntimeAdminPort,ProviderProvenance {
  private final RuntimeAdminPort downstream;
  private final LinkedHashMap<RuntimeAdminKey,AdminResult> reads=new LinkedHashMap<>();
  NativeAdminReads(RuntimeAdminPort downstream){this.downstream=downstream;}
  public Set<ProviderMode> supportedModes(){return Set.of(ProviderMode.PRODUCTION);}
  public AdminResult submit(AdminCommand command){
   actor();if(command.operation()!=AdminOperation.STATUS&&command.operation()!=AdminOperation.DEBUG)return downstream.submit(command);
   var actual=identity(command.actorSession()).filter(a->AdminContracts.authorized(command,a));
   if(actual.isEmpty())return AdminFacade.failure(command,AdminStatus.REJECTED,ReasonCode.AUTH_DENIED);
   var current=currentWorld();if(!command.expectedRevision().equals(current.header().revision()))return AdminFacade.failure(command,AdminStatus.DEFERRED,ReasonCode.STALE_CONTEXT);
   var reasons=phase==Phase.FAILED_SAFE?Set.of(ReasonCode.RECOVERY_AMBIGUOUS):Set.<ReasonCode>of();
   var row=new AdminRow(new ScopeRef(ScopeKind.WORLD,world.value()),current.header().revision(),new Tick(clock()),
    new PublicStatusData(current.healthMode(),current.runControl()!=RunControl.RUNNING,reasons));
   var answer=new AdminResult(AdminStatus.SUCCEEDED,command.requestId(),current.header().revision(),Optional.of(row),Optional.empty());
   var key=new RuntimeAdminKey(command.actorSession(),command.requestId());reads.put(key,answer);if(reads.size()>128)reads.remove(reads.keySet().iterator().next());return answer;
  }
  public AdminResult poll(RuntimeAdminKey key){
   actor();var result=reads.get(key);if(result==null)return downstream.poll(key);
   if(identity(key.actorSession()).isEmpty())return new AdminResult(AdminStatus.REJECTED,key.requestId(),revision(),Optional.empty(),
    Optional.of(new SystemError(ReasonCode.AUTH_DENIED,Severity.WARNING,false,Optional.empty(),Set.of(),new CorrelationId(key.requestId().value()),SafeState.UNCHANGED)));
   return result;
  }
 }
 private AdminResult launchPhysical(AdminCommand command,ActorIdentity actor){
  if(command.operation()!=AdminOperation.TRIGGER)return gameplay.control(command,actor);
  if(!(command.payload() instanceof TriggerPayload payload))return AdminFacade.failure(command,AdminStatus.REJECTED,ReasonCode.CATALOG_INVALID);
  if(!payload.temporaryGroup().isEmpty())return AdminFacade.failure(command,AdminStatus.REJECTED,ReasonCode.CAPABILITY_MISSING);
  var participant=actor.playerId().map(p->server.getPlayerList().getPlayer(p.value())).orElseGet(()->payload.targets().stream().sorted().map(p->server.getPlayerList().getPlayer(p.value())).filter(Objects::nonNull).findFirst().orElse(null));
  var runtime=gameplay.coordinator().runtime();if(runtime.isEmpty())return AdminFacade.failure(command,AdminStatus.REJECTED,ReasonCode.CAPABILITY_MISSING);
  var result=runtime.get().enqueueAdmin(participant,command,actor,authority);
  return result.pending()||result.accepted()?new AdminResult(AdminStatus.PENDING,command.requestId(),revision(),Optional.empty(),Optional.empty()):
   AdminFacade.failure(command,AdminStatus.REJECTED,result.accepted()?ReasonCode.DELIVERY_UNCERTAIN:ReasonCode.NO_TARGET);
 }
 private Optional<AdminResult> progressPhysical(RuntimeAdminKey key){
  var nativeResult=gameplay.controlProgress(key);if(nativeResult.isPresent())return nativeResult;
  var event=new EventInstanceId(UUID.nameUUIDFromBytes(("admin-trigger/"+key.actorSession().value()+"/"+key.requestId().value()).getBytes(StandardCharsets.UTF_8)));
  var q=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.EVENT,event.value())),new Sequence(0),Optional.empty(),1);
  var read=store.view(q);ReadContracts.validate(read,q,session);if(read.status()!=ReadStatus.AVAILABLE)return Optional.empty();
  for(var row:read.page().orElseThrow().aggregates())if(row.state() instanceof EventAggregate e&&e.value().instanceId().equals(event)&&e.value().startedTick().isPresent()){
   var data=new AdminRow(new ScopeRef(ScopeKind.EVENT,event.value()),e.header().revision(),e.header().updatedTick(),new EventAdminData(e.value(),List.of(),List.of(),List.of()));
   return Optional.of(new AdminResult(AdminStatus.SUCCEEDED,key.requestId(),revision(),Optional.of(data),Optional.empty()));}
  var runtime=gameplay.coordinator().runtime();
  if(runtime.isPresent()){
   var reply=runtime.get().adminProgress(key);
   if(reply.isPresent()&&!reply.get().pending()&&!reply.get().accepted())return Optional.of(new AdminResult(AdminStatus.REJECTED,key.requestId(),revision(),Optional.empty(),
    Optional.of(new SystemError(ReasonCode.NO_TARGET,Severity.WARNING,false,Optional.empty(),Set.of(),new CorrelationId(key.requestId().value()),SafeState.UNCHANGED))));
  }
  return Optional.empty();
 }
 public Optional<ActorIdentity> identity(SessionId id){
  actor();if(phase==Phase.CLOSED||phase==Phase.STOPPING)return Optional.empty();
  if(id.equals(consoleSession))return Optional.of(new ActorIdentity(id,ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true));
  var identity=DirectorNativeNetworkBootstrap.adminActor(server,id);
  if(identity.isPresent()){
   if(!knownAdminSessions.contains(id)&&knownAdminSessions.size()>=128)return Optional.empty();
   knownAdminSessions.add(id);
  }
  return identity;
 }
 private void pruneAdminSessions(){
  actor();var revoked=knownAdminSessions.stream().filter(id->DirectorNativeNetworkBootstrap.adminActor(server,id).isEmpty()).toList();
  // Disconnect outside all authority/facade iterations. Old request results and
  // worker authorizations cannot survive a boot/session rotation or logout.
  for(var id:revoked){knownAdminSessions.remove(id);if(administration!=null)administration.disconnect(id);pages.disconnect(id);}
 }
 public ActorIdentity identity(CommandSourceStack source){
  actor();if(source.getServer()!=server)throw new IllegalArgumentException("foreign command source");
  if(source.getEntity() instanceof ServerPlayer player){
   // Resolve the current negotiated authority; no synthetic player session is issued.
   return DirectorNativeNetworkBootstrap.adminActorForPlayer(server,new PlayerId(player.getUUID())).orElseThrow(()->new IllegalStateException("J06 session required"));
  }
  if(source.getEntity()!=null||!source.hasPermission(4))throw new IllegalStateException("authenticated server console required");
  return identity(consoleSession).orElseThrow();
 }
 public AdminPort admin(){actor();if(administration==null)throw new IllegalStateException("production admin recovering");return administration.access();}
 public Revision revision(){actor();return currentWorld().header().revision();}
 public long directorTick(){return clock();}
 public SnapshotBatch fixture(ResourceId id){actor();return readOnlyInputs.fixture(id);}
 public void openUi(ServerPlayer player){actor();if(server.getPlayerList().getPlayer(player.getUUID())!=player||!player.hasPermissions(4))throw new IllegalStateException("live operator required");player.sendSystemMessage(net.minecraft.network.chat.Component.literal("World Director : ouvrir le panneau avec F8."));}
 public Optional<String> serverExportPath(SessionId session,RequestId request){actor();return administration==null?Optional.empty():administration.exportPath(session,request);}
 public boolean shutdownDrained(){actor();return shutdownDrained;}
 private void pollShutdown(){
  if(!session.equals(store.session())||store.readOnlySafe())throw new IllegalStateException("shutdown WAL authority revoked");
  for(int i=0;i<128;i++){var completion=actorCompletions.poll();if(completion==null)break;completion.run();}
  content.envelopes().poll();memory.poll();gameplay.poll();physicalCatalogues.tick();regionalWeather.poll();
  if(observation!=null&&memory.status().complete()){
   observation.refreshRegionIndex(memory.view().orElseThrow(),memory.status().lastDurableSequence());observation.tick();
   gameplay.bindAnchorOwner(observation.anchorOwner());gameplay.pollLabChainsAtFreshContext();
  }
  if(anchors!=null)anchors.poll(false);if(ingress!=null)ingress.poll(false);
  if(operations!=null)operations.pump();if(administration!=null)administration.tick(true);
  var progress=gameplay.controlProgress(new RuntimeAdminKey(shutdownCommand.actorSession(),shutdownCommand.requestId()));
  if(progress.isPresent())shutdownResult=progress.get();
  boolean ingressDrained=ingress==null||ingress.view().phase()!=DirectorObservationWalIngestion.Phase.WAL_PENDING;
  boolean anchorsDrained=anchors==null||anchors.status().phase()!=DirectorNativeAnchorWalIngestion.Phase.WAL_PENDING;
  shutdownDrained=shutdownResult!=null&&shutdownResult.status()==AdminStatus.SUCCEEDED&&gameplay.coordinator().worldWriterIdle()
   &&ingressDrained&&anchorsDrained&&!regionalWeather.runtime().hasActive()&&!content.envelopes().pendingWrites()&&(operations==null||!operations.hasPendingWrites())
   &&(administration==null||!administration.controls().hasPendingWrites());
 }
 public void close(){
  actor();if(consumersClosed)return;
  // Keep internal J01/J04 data and receipt processing alive while the native
  // stop probes drain. Public admin identities are revoked immediately.
  shutdownActor=new ActorIdentity(consoleSession,ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true);
  phase=Phase.STOPPING;regionalWeather.stopAll();
  try{
   gameplay.coordinator().runtime().ifPresent(runtime->runtime.automatic(false));
   if(scanned&&observation!=null&&!gameplay.recovering()){
    shutdownCommand=new AdminCommand(consoleSession,new RequestId(UUID.nameUUIDFromBytes((world.value()+"/shutdown/"+session.bootEpoch().value()).getBytes(StandardCharsets.UTF_8))),revision(),
     AdminOperation.STOP,new StopPayload(Optional.empty(),true,"SERVER_STOPPING"));
    shutdownResult=gameplay.control(shutdownCommand,shutdownActor);
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
    server.managedBlock(()->{pollShutdown();return shutdownDrained||System.nanoTime()>=deadline;});
   }
  }catch(RuntimeException failure){diagnostic="SHUTDOWN_RECOVERY_REQUIRED:"+failure.getClass().getSimpleName();}
  if(administration!=null){List.copyOf(knownAdminSessions).forEach(administration::disconnect);administration.disconnect(consoleSession);}
  if(ingress!=null)ingress.close();if(anchors!=null)anchors.close();if(observation!=null)observation.close();
  regionalWeather.close();physicalCatalogues.close();gameplay.close();memory.close();boot.close();worker.shutdownNow();consumersClosed=true;phase=Phase.CLOSED;
  System.out.println(shutdownDrained?"DIRECTOR_PRODUCTION_SHUTDOWN_DRAIN_CONFIRMED":"DIRECTOR_PRODUCTION_SHUTDOWN_RECOVERY_REQUIRED checkpoint_is_not_cleanup=true");
 }
}
