package fr.proutlost.director.bootstrap;
import fr.proutlost.director.runtime.ResultTransaction;
import fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap;
import fr.proutlost.director.adapter.neoforge.network.DirectorGameplayAssets;
import fr.proutlost.director.adapter.neoforge.server.effects.BlockTransformBinding;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.engine.*;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ChainRunner;
import fr.proutlost.director.runtime.ChainDispatchRecovery;
import fr.proutlost.director.memory.ChainStateMemoryProjection;
import fr.proutlost.director.memory.ScopedAggregateCapture;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Exact native chain lifecycle; manual OP4 entry points remain laboratory-only.
 * Dispatch intent is durable BEFORE calling an existing native provider. Restart never replays it.
 */
public final class DirectorNativeChainGameplay implements AutoCloseable {
 public record Launch(boolean accepted,String message,boolean pending,
          Optional<fr.proutlost.director.api.error.ReasonCode> refusalReason){
   public Launch { Objects.requireNonNull(message);Objects.requireNonNull(refusalReason); }
   public Launch(boolean accepted,String message,boolean pending){this(accepted,message,pending,Optional.empty());}
  public Launch(boolean accepted,String message){this(accepted,message,false);}
 }
 public interface Driver {
  default boolean available(){return true;}
  boolean supports(DefinitionId definition);
  Launch start(ServerPlayer actor,DefinitionId definition,UUID event,RegionId region,ChainId chain);
  default Optional<Launch> pendingStart(UUID event){return Optional.empty();}
  default void cancelPending(UUID event,String reason){}
  default boolean acquireMemoryLane(Object owner){return false;}
  default void releaseMemoryLane(Object owner){}
 }
 private static final FactType START=new FactType("proutlost_director:lab_chain_started");
 private static final FactType DISPATCH=new FactType("proutlost_director:lab_chain_dispatch");
 private static final FactType SUSPEND=new FactType("proutlost_director:lab_chain_suspended");
 private static final FactType RESUME=new FactType("proutlost_director:lab_chain_resumed");
 private static final FactType LANDSLIDE=new FactType("proutlost:controlled_landslide_observed");
 private static final ResourceId SCAR=new ResourceId("proutlost:scar_still_relevant");
 private static final DefinitionId CAMP=new DefinitionId("proutlost_director:candidate/camp_regrowth");
 private static final ModelCodec<ChainDefinition> CHAIN_CODEC=new ModelCodec<>(ChainDefinition.class);
 private final MinecraftServer server;private final WorldId world;private final JournalStore store;private final Driver driver;
 private final DirectorLabChainCatalog laboratory;private final DirectorRuntimeContent content;private final DirectorNativeRegionalPlanningBridge regional;
 private final Map<ChainId,Flight> flights=new LinkedHashMap<>();private final Map<UUID,Dispatch> dispatches=new HashMap<>();
 private final Map<ChainId,ChainInstance> restored=new HashMap<>();private final Map<ChainId,DirectorFact> starts=new HashMap<>();
 private final NavigableMap<FactId,DirectorFact> facts=new TreeMap<>(Comparator.comparing(FactId::value));private final Set<ChainId> suspended=new HashSet<>();
 private final Set<DecisionId> durableAudits=new HashSet<>();
 private long nextProductionStart;private int productionDefinitionCursor;private FactId productionFactCursor;
 private final boolean chainDiagnostics=Boolean.getBoolean("proutlost.director.diagnostics.unit_overruns");
 private int chainDiagnosticLines,lastApprovedChainCount=-1;private long startAvailabilityNanos,startContextReads;
 private long diagnosticGoal(){return config().values().get("budget.tick_p95_ms").value().multiply(java.math.BigDecimal.valueOf(1_000_000)).setScale(0,java.math.RoundingMode.CEILING).longValueExact();}
 private void diagnoseChain(long began,long beganCpu,long afterIncremental,long afterDriver,long afterMemory,long afterFlights){
  if(!chainDiagnostics||chainDiagnosticLines>=64)return;
  long ended=System.nanoTime(),elapsed=Math.max(0,ended-began),goal=diagnosticGoal();if(elapsed<=goal)return;
  long cpu=fr.proutlost.director.runtime.DecisionWorkBudget.threadCpuNanos();
  long usedCpu=beganCpu>=0&&cpu>=beganCpu?cpu-beganCpu:-1;chainDiagnosticLines++;
  System.out.println("DIRECTOR_CHAIN_MAINTENANCE_PHASE_COST tick="+now().value()+" wall_ns="+elapsed+" cpu_ns="+usedCpu
   +" incremental_ns="+Math.max(0,afterIncremental-began)+" driver_pending_ns="+Math.max(0,afterDriver-afterIncremental)
   +" memory_ns="+Math.max(0,afterMemory-afterDriver)+" flights_ns="+Math.max(0,afterFlights-afterMemory)
   +" starts_ns="+Math.max(0,ended-afterFlights)+" start_availability_ns="+startAvailabilityNanos
   +" approved_chains="+lastApprovedChainCount+" start_context_reads="+startContextReads+" unchanged_goal_ns="+goal+" diagnostic_ordinal="+chainDiagnosticLines);
 }
 private String productionDiagnostic="NOT_EVALUATED";
 private java.util.function.Supplier<Optional<DecisionInput>> productionInput=Optional::empty;private boolean productionInputBound;
 private CompletableFuture<StoreReadResult> scan;private StoreQuery scanQuery;private Optional<String> cursor=Optional.empty();
 private Sequence scannedCut=new Sequence(0);private boolean scanned,closed,failed;private long offset;
 private static final class Tx {DurableBatch batch;StoreSession session;CompletableFuture<CommitAck> ack;}
 private static final class Dispatch {UUID event;ChainId chain;DefinitionId definition;boolean primer,recovered,driverPending,cancelRequested;Tx tx;Tick tick;Optional<Revision> branchRevision=Optional.empty();DispatchGuard guard;}
 private static final class Flight {
  ChainId id;UUID actor;RegionId region;ChainDefinition definition;ChainRunner runner;Tx initial,suspension,recoveryResume;
  DefinitionId queued,afterResume;Dispatch current;boolean recovered,cancelled,pendingBranch,suspensionCommitted,resumeCancelled;
  String waiting="INITIAL";long nextPoll;PendingStart origin,resumeRequest;Tx resumption;
  ChainRunner.Advance committedBranch;RegionMemory expectedMemory;boolean memoryLane;
 }
 private boolean occupiesActiveSlot(Flight f){
  // A local cancel flag is not a durable suspension: retain its slot until exact ACK.
  if(f.resumption!=null)return true;
  if(f.cancelled)return !f.suspensionCommitted;
  if(f.initial!=null||f.current!=null||f.queued!=null||f.pendingBranch)return true;
  var state=f.runner.snapshot();var nodes=state.currentNodes();
  boolean completed=state.state()==ChainState.COMPLETED||state.state()==ChainState.SETTLED_AS_WORLD_STATE;
  return !completed||nodes.isEmpty()||!nodes.stream().allMatch(f.definition.terminalStates()::contains);
 }
 private long activeFlightCount(){return flights.values().stream().filter(this::occupiesActiveSlot).count();}
 private record Context(SnapshotBatch snapshot,MemoryView memory,DirectorWorldState world,Sequence cut,ConfigView config){}
 private record PendingKey(UUID actor,String name){}
 private record PendingStart(PendingKey key,StoreSession storeSession,SessionIdentity clientSession,RegionId region,
  BlockPosition target,net.minecraft.core.Direction face,Fingerprint targetHash,long deadline,Optional<ChainId> resumeChain){}
 private record DispatchGuard(StoreSession storeSession,SessionIdentity clientSession,RegionId region,DimensionId dimension,long deadline,PendingStart origin){}
 private final Map<PendingKey,PendingStart> pendingStarts=new LinkedHashMap<>();
 private static final class MemoryWrite {Tx tx;RegionMemory expected;Object owner;}
 private MemoryWrite memoryWrite;
 private static final class ScarRead {Context context;RegionId region;List<DirectorFact> sources;StoreQuery query;StoreSession session;CompletableFuture<StoreReadResult> future;}
 private ScarRead scarRead;
 private final Object scarMemoryOwner=new Object();
 private final Set<RegionId> knownScars=new HashSet<>();
 private int scarCursor;
 private static final class MemoryLaneBusy extends RuntimeException{}
 public DirectorNativeChainGameplay(MinecraftServer server,WorldId world,JournalStore store,Driver driver){
  this(server,world,store,driver,null);
 }
 public DirectorNativeChainGameplay(MinecraftServer server,WorldId world,JournalStore store,Driver driver,DirectorRuntimeContent content){
  this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.store=Objects.requireNonNull(store);this.driver=Objects.requireNonNull(driver);
  this.content=content;laboratory=content==null?new DirectorLabChainCatalog(DirectorLabObservationProfile.loadRuntime()):null;
  regional=new DirectorNativeRegionalPlanningBridge(config(),content==null?ProviderMode.TEST_ONLY:content.mode());actor();
 }
 private ConfigView config(){return content==null?laboratory.config():content.config();}
 private ChainDefinition definition(String name){
  if(content==null)return laboratory.definition(name);
  var id=new DefinitionId(name.contains(":")?name:"proutlost_director:candidate/"+name);
  return Objects.requireNonNull(content.catalog().chains().get(id),"unknown exact production chain");
 }
 private EventDefinition event(DefinitionId id){return content==null?laboratory.event(id):content.definition(id);}
 private DefinitionRef reference(ChainDefinition chain){return new DefinitionRef(chain.id(),chain.contentRevision(),BlobContent.hash(CHAIN_CODEC.encode(chain)));}
 private CatalogSnapshot conditionCatalogue(EventDefinition event,ChainDefinition chain){
  return content==null?laboratory.conditionCatalogue(event,chain):content.catalog();
 }
 private void requireLaboratory(){if(content!=null)content.requireLaboratory();}
 private boolean production(){return content!=null&&content.production();}
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("chain server actor");}
 private Tick now(){return new Tick(Math.addExact(server.overworld().getGameTime(),offset));}
 private static JsonValue.Text text(Object x){return new JsonValue.Text(x.toString());}
 private static String text(DirectorFact f,String key){return ((JsonValue.Text)f.payload().get(key)).value();}
 private Tx append(List<DurableRecord> records){
  var tx=new Tx();tx.session=store.session();tx.batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),tx.session.storeGeneration(),Map.of(),records,List.of());
  tx.ack=store.append(tx.batch).toCompletableFuture();return tx;
 }
 private boolean durable(Tx tx){
  if(tx==null||!tx.ack.isDone())return false;var ack=tx.ack.getNow(null);
  if(ack==null||ack.ackStatus()!=AckStatus.DURABLE||!store.session().equals(tx.session)
    ||!ack.transactionId().equals(tx.batch.transactionId())||!ack.transactionHash().equals(tx.batch.transactionHash())
    ||!ack.storeGeneration().equals(tx.session.storeGeneration())||ack.durableSequence().value()==0
    ||ack.permitAttempt().isPresent()||!ack.forcedBlobs().equals(DurableBlobReferences.collect(tx.batch.records())))
   throw new IllegalStateException("chain metadata ACK uncertain");
  return true;
 }
 private DirectorFact meta(FactType type,Flight f,Map<String,JsonValue> payload){
  var values=new TreeMap<>(payload);values.put("chain",text(f.id.value()));values.put("actor",text(f.actor));values.put("region",text(f.region.value()));
  values.put("recipe",text(f.definition.id().value()));values.put("tick",text(now().value()));values.put("manual_lab",new JsonValue.Bool(!production()));
  values.put("provider_mode",text(production()?ProviderMode.PRODUCTION:ProviderMode.TEST_ONLY));
  return new DirectorFact(new FactId(UUID.randomUUID()),type,List.of(new ScopeRef(ScopeKind.WORLD,world.value()),new ScopeRef(ScopeKind.REGION,f.region.value())),
   Optional.empty(),EvidenceStatus.CONFIRMED,values,true,Optional.empty());
 }
 private String contextDiagnostic="UNSAMPLED";
 private CompletableFuture<ScopedAggregateCapture.View> scopedHydration;
 private ScopedAggregateCapture.View scopedCapture;
 private String eligibilityDiagnostic="UNSAMPLED";
 private Optional<Context> waitContext(String reason,String observed){contextDiagnostic=reason+" "+observed;return Optional.empty();}
 private Optional<Context> context(){
  var life=DirectorStandaloneMod.directorLifecycle();var live=life.observationSnapshot(server);var memory=life.runtimeMemoryView(server);var projection=life.memoryProjectionView(server);
  var observedNow=life.runtimeObservationTick(server);
  long maximumAge=new EngineParameters(config()).ticks("scheduler.snapshot_max_age_ticks");
  String inputs="live="+live.isPresent()+" memory="+memory.isPresent()+" projection="+projection.map(p->
   "complete:"+p.complete()+",failClosed:"+p.failClosed()+",cut:"+p.lastDurableSequence()+",scans:"+p.completedScans()).orElse("ABSENT")
   +" health="+live.map(p->p.serverHealth().name()).orElse("ABSENT")
   +" captured="+live.map(p->Long.toString(p.capturedTick().value())).orElse("ABSENT")
   +" observedNow="+observedNow.map(p->Long.toString(p.value())).orElse("ABSENT")+" maxAge="+maximumAge;
  if(!production()&&!Boolean.getBoolean("proutlost.director.nativeLab"))return waitContext("LAB_DISABLED",inputs);
  if(store.readOnlySafe())return waitContext("STORE_READ_ONLY",inputs);
  if(live.isEmpty()||memory.isEmpty()||projection.isEmpty())return waitContext("INPUT_ABSENT",inputs);
  if(!projection.get().complete()||projection.get().failClosed())return waitContext("PROJECTION_UNAVAILABLE",inputs);
  if(live.get().serverHealth()!=HealthMode.NORMAL&&live.get().serverHealth()!=HealthMode.READ_ONLY_SAFE)return waitContext("HEALTH",inputs);
  if(production()&&live.get().serverHealth()!=HealthMode.NORMAL)return waitContext("PRODUCTION_HEALTH",inputs);
  // READ_ONLY_SAFE is intentional LAB observation, not a fabricated NORMAL health grant.
  if(observedNow.isEmpty())return waitContext("OBSERVATION_CLOCK_ABSENT",inputs);
  long age=observedNow.get().value()-live.get().capturedTick().value();inputs+=" age="+age;
  if(age<0||age>maximumAge)return waitContext("SNAPSHOT_AGE",inputs);
  var session=store.session();var query=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.WORLD,world.value())),new Sequence(0),Optional.empty(),1);
  var read=store.view(query);ReadContracts.validate(read,query,session);
  if(read.status()!=ReadStatus.AVAILABLE)return waitContext("WORLD_READ_"+read.status(),inputs);
  var rows=read.page().orElseThrow().aggregates();
  if(rows.size()!=1||!(rows.getFirst().state() instanceof WorldAggregate wa))return waitContext("WORLD_ROW",inputs+" rows="+rows.size());
  String worldEvidence=" world="+wa.value().worldId().value()+"/"+world.value()
   +" boot="+wa.value().bootEpoch().value()+"/"+session.bootEpoch().value()
   +" generation="+wa.value().storeGeneration().value()+"/"+session.storeGeneration().value();
  if(!session.equals(store.session()))return waitContext("SESSION_CHANGED",inputs+worldEvidence);
  if(!wa.value().worldId().equals(world)||!wa.value().bootEpoch().equals(session.bootEpoch())
    ||!wa.value().storeGeneration().equals(session.storeGeneration()))return waitContext("WORLD_IDENTITY",inputs+worldEvidence);
  if(production()&&(!content.profiles().containsKey(wa.value().intensityProfileId())
    ||!content.profiles().get(wa.value().intensityProfileId()).provenanceHash().equals(wa.value().configHash())
    ||!wa.value().catalogHash().equals(content.catalog().hash())))
   return waitContext("PRODUCTION_PROVENANCE_CHANGED",inputs+worldEvidence);
  var cut=read.page().orElseThrow().lastDurableSequence();inputs+=worldEvidence+" storeCut="+cut.value();
  if(projection.get().lastDurableSequence()!=cut.value()) {
   var generation=store.metadataSnapshot();
   if(generation.isEmpty()||generation.get().generations().isEmpty()
     ||!currentSelectedMemory(memory.get(),new Sequence(projection.get().lastDurableSequence())).filter(cut::equals).isPresent())
    return waitContext("CUT_MISMATCH_OR_SCOPED_MEMORY_CHANGED",inputs);
  }
  var authoredTags=life.runtimeImportedRegionTags(server);
  if(authoredTags.isEmpty())return waitContext("AUTHORED_TAGS_NOT_READY",inputs);
  var tagFence=store.residentAggregateKeys(Set.of(AggregateKind.WORLD),4096);
  if(tagFence.isEmpty()||!tagFence.get().session().equals(session)||!tagFence.get().cut().equals(cut))
   return waitContext("AUTHORED_TAGS_CUT_CHANGED",inputs);
  var scopedTags=new HashMap<RegionId,Set<TagId>>();
  authoredTags.get().forEach((id,tags)->{if(memory.get().regionRecords().containsKey(id))scopedTags.put(id,tags);});
  var anchorCoverage=life.runtimeRegionAnchorCoverage(server);
  var metadata=store.metadataSnapshot();
  if(metadata.isEmpty()||!metadata.get().cut().equals(cut)||!metadata.get().generations().isEmpty()&&anchorCoverage.isEmpty())
   return waitContext("ANCHOR_CENSUS_NOT_CURRENT",inputs);
  var projected=regional.project(live.get(),memory.get(),cut.value(),scopedTags,anchorCoverage);
  if(projected.deferred())return waitContext("REGIONAL_DEFERRED",inputs+" regionRecords="+memory.get().regionRecords().size()
   +" regionMemories="+memory.get().regions().size()+" anchors="+memory.get().anchors().size());
  contextDiagnostic="READY "+inputs+" regions="+projected.regionCount()+" camps="+projected.campCount();
  return Optional.of(new Context(projected.snapshot(),memory.get(),wa.value(),cut,production()?
   fr.proutlost.director.runtime.AdminWorldControlHandler.configuration(wa.value(),content.profiles()):config()));
 }
 /** A newer lease/audit/world cut does not imply changed memory. Verify every
  * selected head at the current cut instead of waiting for equal scan cursors.
  */
 private Optional<Sequence> currentSelectedMemory(MemoryView memory,Sequence projectedCut){
  var keys=DirectorNativeCatalogueInput.memoryKeys(memory);var settings=config();
  int maximum=Math.addExact(512,Math.multiplyExact(settings.values().get("memory.resident_player_limit").value().intValueExact(),
   Math.addExact(settings.values().get("memory.player_recent_event_entries").value().intValueExact(),1)));
  if(keys.size()>maximum)return Optional.empty();
  var snapshot=store.metadataSnapshot();if(snapshot.isEmpty())return Optional.empty();
  if(scopedHydration!=null&&scopedHydration.isDone()){scopedCapture=scopedHydration.join();scopedHydration=null;}
  if(scopedCapture==null||!scopedCapture.states().keySet().equals(keys)||!scopedCapture.sameHeads(store,snapshot.get())){
   scopedCapture=null;
   if(scopedHydration==null)scopedHydration=ScopedAggregateCapture.load(store,snapshot.get(),keys,maximum).toCompletableFuture();
   return Optional.empty();
  }
  var actual=fr.proutlost.director.memory.MemoryProjection.of(memory.scopes(),scopedCapture.states().values());
  return DirectorNativeCatalogueInput.verifiedMemoryAtCut(actual,memory,projectedCut,snapshot.get().cut())
   ?Optional.of(snapshot.get().cut()):Optional.empty();
 }
 private Optional<RegionId> region(ServerPlayer player,Context context){
  var p=context.snapshot.context().players().get(new PlayerId(player.getUUID()));if(p==null)return Optional.empty();
  var matches=p.regionIds().stream().filter(context.snapshot.context().regions()::containsKey)
   .filter(id->context.snapshot.context().regions().get(id).region().status()==RegionStatus.ACTIVE).toList();
  return matches.size()==1?Optional.of(matches.getFirst()):Optional.empty();
 }
 private static BlockPosition point(ServerPlayer player){
  var p=player.blockPosition();return new BlockPosition(new DimensionId(player.serverLevel().dimension().location().toString()),p.getX(),p.getY(),p.getZ());
 }
 private Optional<SessionIdentity> client(ServerPlayer player){
  return DirectorNativeNetworkBootstrap.qualifiedRecipient(server,new PlayerId(player.getUUID()),PerceptionKind.SYMBOL,
   DirectorGameplayAssets.WARNING,DirectorGameplayAssets.WARNING_HASH);
 }
 private String invalid(PendingStart request,ServerPlayer player){
  if(player==null||!player.isAlive()||!player.hasPermissions(4))return "OPERATOR_LEFT_OR_OP4_LOST";
  if(server.overworld().getGameTime()>=request.deadline())return "REQUEST_EXPIRED";
  if(!store.session().equals(request.storeSession()))return "WAL_SESSION_CHANGED";
  if(!client(player).filter(request.clientSession()::equals).isPresent())return "J06_SESSION_CHANGED";
  if(!point(player).dimension().equals(request.target().dimension()))return "DIMENSION_CHANGED";
  if(DirectorStandaloneMod.directorLifecycle().runtimePrimaryRegion(server,point(player)).filter(request.region()::equals).isEmpty())return "REGION_CHANGED_OR_UNKNOWN";
  if(!(player.pick(6,0,false) instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK
    ||!hit.getBlockPos().equals(new net.minecraft.core.BlockPos(request.target().x(),request.target().y(),request.target().z()))
    ||hit.getDirection()!=request.face())return "PICK_CHANGED";
  var p=hit.getBlockPos();var level=player.serverLevel();
  if(level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)==null)return "TARGET_UNLOADED";
  if(!BlockTransformBinding.stateBlob(level.getBlockState(p)).reference().hash().equals(request.targetHash()))return "TARGET_HASH_CHANGED";
  return null;
 }
 private void answer(PendingStart request,String message){
  var player=server.getPlayerList().getPlayer(request.key().actor());
  if(player!=null)player.sendSystemMessage(Component.literal("[World Director] "+message));
 }
 public String begin(ServerPlayer player,String name){
  requireLaboratory();
  actor();if(!player.hasPermissions(4)||!player.isAlive())return "OP4 vivant requis.";
  if(!scanned||failed||store.readOnlySafe())return "Chaines en attente de recuperation J04.";
  try{definition(name);}catch(RuntimeException unknown){return "Chaine inconnue.";}
  var key=new PendingKey(player.getUUID(),name);var old=pendingStarts.get(key);
  if(old!=null)return "PENDING_MANUAL_CHAIN "+name+"; demande existante non prolongee, cible/session conservees.";
  if(activeFlightCount()+pendingCapacity()>=2)return "Deux chaines actives ou demandes LAB occupent deja la capacite; consulter leur etat.";
  var session=client(player);if(session.isEmpty())return "Session J06 actuelle et symbole WARN negocie requis.";
  if(!(player.pick(6,0,false) instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK)return "Visez une cible reelle a six blocs.";
  var pos=hit.getBlockPos();var level=player.serverLevel();
  if(level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null)return "Cible non chargee.";
  var region=DirectorStandaloneMod.directorLifecycle().runtimePrimaryRegion(server,point(player));
  if(region.isEmpty())return "WAIT_ONE_REAL_DURABLE_REGION";
  var target=new BlockPosition(point(player).dimension(),pos.getX(),pos.getY(),pos.getZ());
  var request=new PendingStart(key,store.session(),session.get(),region.get(),target,hit.getDirection(),
   BlockTransformBinding.stateBlob(level.getBlockState(pos)).reference().hash(),Math.addExact(server.overworld().getGameTime(),1200),Optional.empty());
  pendingStarts.put(key,request);
  System.out.println("DIRECTOR_LAB_CHAIN_MANUAL_REQUEST_QUEUED actor="+key.actor()+" recipe="+name+" target="+target+" hash="+request.targetHash().value()+" deadline="+request.deadline());
  return "PENDING_MANUAL_CHAIN "+name+"; maximum 60 s. Gardez meme cible/face; visee, session ou region modifiee annule la demande.";
 }
 private void pending(){
  for(var iterator=pendingStarts.entrySet().iterator();iterator.hasNext();){
   var request=iterator.next().getValue();var player=server.getPlayerList().getPlayer(request.key().actor());
   var reason=invalid(request,player);
   if(reason!=null){iterator.remove();answer(request,"Demande de chaine annulee: "+reason);continue;}
   if(!driver.available())continue;
   var context=context();if(context.isEmpty())return;
   var current=region(player,context.get());
   if(current.isEmpty())continue;
   if(!current.get().equals(request.region())){iterator.remove();answer(request,"Demande de chaine annulee: REGION_CHANGED");continue;}
   if(request.resumeChain().isPresent()){
    var result=resumeAtContext(player,request,context.get());
    if(result.startsWith("WAIT_")){var waiting=flights.get(request.resumeChain().orElseThrow());if(waiting!=null)waiting.waiting=result;continue;}
    iterator.remove();answer(request,result);continue;
   }
   if(activeFlightCount()>=2){iterator.remove();answer(request,"Capacite de deux chaines LAB actives atteinte.");continue;}
   iterator.remove();answer(request,start(player,request,context.get()));
  }
 }
 private String start(ServerPlayer player,PendingStart request,Context context){
  // Called only in the post-memory/post-J01 actor phase with the ORIGINAL exact context guards.
  var name=request.key().name();var definition=definition(name);
  var f=new Flight();f.id=new ChainId(UUID.randomUUID());f.actor=player.getUUID();f.region=request.region();f.definition=definition;f.origin=request;
  var state=new ChainInstance(new AggregateHeader(1,f.id.value(),new Revision(1),new Sequence(0),now()),f.id,reference(definition),definition.mode(),
   ChainState.WAITING,Set.of(definition.entryState()),Set.of(),Set.of(f.region),List.of(),List.of(),Optional.empty(),new UnitValue(1),now());
  f.runner=new ChainRunner(store,definition,state,new ScopeRef(ScopeKind.REGION,f.region.value()));
  f.queued=name.equals("camp_short_arc")?CAMP:null;
  f.initial=append(List.of(new DurableAggregate(new ChainAggregate(state)),new DurableFact(meta(START,f,Map.of()))));flights.put(f.id,f);
  System.out.println("DIRECTOR_LAB_CHAIN_BEGIN chain="+f.id.value()+" recipe="+definition.id().value()+" region="+f.region.value()+" manual=true context_cut="+context.cut().value()+" request_session="+request.clientSession().connectionEpoch().value());
  return "Chaine LAB "+name+" "+f.id.value()+"; conditions catalogue exactes, aucun tag/participant invente.";
 }
 private static String boundedDiagnostic(Object value,int maximum){
  var text=String.valueOf(value);return text.length()<=maximum?text:text.substring(0,maximum)+"...";
 }
 private Optional<CandidateGenerator.Generated> candidate(Flight f,EventDefinition event,Context context,List<RequestView> requests){
  var zero=new BudgetView(BudgetMath.zero(),BudgetMath.zero(),Map.of());
  var state=new DecisionState(context.world,zero,new OwnershipView(Map.of()),List.of(),List.of(),List.of(),List.of(),List.of(f.runner.snapshot()),List.of());
  var input=new DecisionInput(context.snapshot,context.memory,conditionCatalogue(event,f.definition),context.config(),requests,List.of(f.runner.snapshot()),f.id.value().getLeastSignificantBits(),state,Optional.empty());
  var generated=new CandidateGenerator().generate(input,new DecisionRandom(input.seed())).candidates();
  var selected=generated.stream().filter(c->c.definition().id().equals(event.id()))
   .filter(c->c.completeRoles()&&c.region().equals(Optional.of(f.region)))
   .filter(c->c.roles().roles().values().stream().anyMatch(ps->ps.contains(new PlayerId(f.actor)))).findFirst();
  var actor=context.snapshot.context().players().get(new PlayerId(f.actor));
  var scope=new ScopeRef(ScopeKind.REGION,f.region.value());var sensors=context.snapshot.context().scopes().get(scope);
  var regional=context.snapshot.context().regions().get(f.region);
  var memories=context.memory.regions().stream().filter(m->m.regionId().equals(f.region)).toList();
  var flag=new ResourceId("proutlost:camp_abandoned");
  String actualFlag=memories.size()==1?Boolean.toString(memories.getFirst().persistentStateRefs().contains(flag)):"UNKNOWN";
  var roles=generated.stream().filter(c->c.region().equals(Optional.of(f.region))).limit(4)
   .map(c->"complete="+c.completeRoles()+":"+boundedDiagnostic(c.roles(),240)).toList();
  eligibilityDiagnostic=boundedDiagnostic("cut="+context.cut.value()+" chain="+f.id.value()+" event="+event.id().value()+" actor="+f.actor
   +" live="+(actor==null?"ABSENT":actor.eligibility()+" regions="+boundedDiagnostic(actor.regionIds(),170))
   +" candidate="+selected.isPresent()+" generatedRoles="+roles
   +" present="+(regional==null?"REGION_ABSENT":boundedDiagnostic(regional.presentPlayers(),240))
   +" CAMPglobalMissing="+context.snapshot.missingData().contains(SensorKind.CAMP_ACTIVITY)
   +" CAMPlocalAvailable="+(sensors!=null&&sensors.availableSensors().contains(SensorKind.CAMP_ACTIVITY))
   +" CAMPlocalMissing="+(sensors==null||sensors.missingSensors().contains(SensorKind.CAMP_ACTIVITY))
   +" regionMemoryCount="+memories.size()+" camp_abandoned="+actualFlag
   +" maxCampConfidence="+(regional==null?"UNKNOWN":regional.camps().stream().mapToDouble(c->c.confidence().value()).max().orElse(-1)),1600);
  return selected;
 }
 private ConditionEvaluator.Truth condition(Flight f,Condition condition,EventDefinition event,Context context){
  eligibilityDiagnostic="cut="+context.cut.value()+" candidate=NOT_EVALUATED";
  var actual=requestInput(context);var requests=actual.map(DecisionInput::requests).orElse(List.of());
  var outcome=fr.proutlost.director.runtime.ChainConditionKnowledge.evaluate(condition,knownScars.contains(f.region),!production()||actual.isPresent(),()->{
   var candidate=candidate(f,event,context,requests);if(candidate.isEmpty())return ConditionEvaluator.Truth.UNKNOWN;
   var state=new DecisionState(context.world,new BudgetView(BudgetMath.zero(),BudgetMath.zero(),Map.of()),new OwnershipView(Map.of()),List.of(),List.of(),List.of(),List.of(),List.of(f.runner.snapshot()),List.of());
   var input=new DecisionInput(context.snapshot,context.memory,conditionCatalogue(event,f.definition),context.config(),requests,List.of(f.runner.snapshot()),f.id.value().getLeastSignificantBits(),state,Optional.empty());
   var evaluator=new ConditionEvaluator();var truth=evaluator.evaluate(condition,candidate.get(),input);
   List<Condition> leaves=condition instanceof AllConditions all?all.conditions():List.of(condition);
   eligibilityDiagnostic=boundedDiagnostic(eligibilityDiagnostic+" leaves="+leaves.stream().limit(6)
    .map(leaf->leaf.getClass().getSimpleName()+"="+evaluator.evaluate(leaf,candidate.get(),input)).toList(),1800);
   return truth;
  },ConditionEvaluator.Truth.UNKNOWN);
  eligibilityDiagnostic=boundedDiagnostic("condition="+condition.getClass().getSimpleName()+" truth="+outcome+" "+eligibilityDiagnostic,2000);
  return outcome;
 }
 private ScopeRef chainScope(Flight f){
  return new ScopeRef(f.definition.scope(),f.definition.scope()==ScopeKind.WORLD?world.value():f.region.value());
 }
 private boolean participant(ServerPlayer player){
  return player!=null&&player.isAlive()&&!player.isSpectator()&&(production()||player.hasPermissions(4));
 }
 private boolean admissionsOpen(Context context){
  return !production()||context.world().runControl()==RunControl.RUNNING&&context.world().healthMode()==HealthMode.NORMAL;
 }
 /** The operator-approved chain references the type; the actual producer must
  * have committed confirmed scope evidence. Technical and fixture records are not observations. */
 private boolean productionSource(DirectorFact fact){
  return !production()||ChainRunner.productionSource(fact);
 }
 /** Bind the already assembled catalogue input, including its exact J04 request
  * census. This grants no admission or world effect authority. */
 public void productionInput(java.util.function.Supplier<Optional<DecisionInput>> source){
  actor();if(!production()||productionInputBound)throw new IllegalStateException("production chain input binding");
  productionInput=Objects.requireNonNull(source);productionInputBound=true;
 }
 private Optional<DecisionInput> requestInput(Context context){
  if(!production()||!productionInputBound)return Optional.empty();
  var current=productionInput.get();if(current.isEmpty())return Optional.empty();
  var input=current.orElseThrow();var state=input.state().world();var bound=store.session();
  var fence=store.residentAggregateKeys(Set.of(AggregateKind.WORLD),4096);
  if(fence.isEmpty()||!fence.orElseThrow().session().equals(bound)||!fence.orElseThrow().cut().equals(context.cut())
    ||!state.worldId().equals(world)||!state.header().equals(context.world().header())
    ||!state.bootEpoch().equals(bound.bootEpoch())||!state.storeGeneration().equals(bound.storeGeneration())
    ||!input.config().equals(context.config())||!input.catalog().equals(content.catalog())
    ||!input.context().capturedTick().equals(context.snapshot().capturedTick())
    ||!input.context().scopeRevisions().equals(context.snapshot().scopeRevisions()))return Optional.empty();
  return current;
 }
 private boolean sourceScope(Flight f,DirectorFact fact){
  if(!fact.subjectRefs().contains(chainScope(f)))return false;
  // WORLD causality does not authorize a silent move to another regional lineage.
  var regions=fact.subjectRefs().stream().filter(s->s.kind()==ScopeKind.REGION).toList();
  return regions.isEmpty()||regions.stream().anyMatch(s->s.id().equals(f.region.value()));
 }
 private DecisionInput chainInput(Flight f,Context context,List<RequestView> requests){
  var state=new DecisionState(context.world(),new BudgetView(BudgetMath.zero(),BudgetMath.zero(),Map.of()),new OwnershipView(Map.of()),
   List.of(),List.of(),List.of(),List.of(),List.of(f.runner.snapshot()),List.of());
  return new DecisionInput(context.snapshot(),context.memory(),content.catalog(),context.config(),requests,
   List.of(f.runner.snapshot()),f.id.value().getLeastSignificantBits(),state,Optional.empty());
 }
 private ConditionEvaluator.Truth transitionCondition(Flight f,ChainTransition transition,Context context){
  if(!production()){
   var event=event(transition.eventId().orElse(new DefinitionId("proutlost_director:candidate/local_mist")));
   var truth=condition(f,transition.condition(),event,context);
   return truth==ConditionEvaluator.Truth.TRUE&&transition.eventId().isPresent()?condition(f,event.eligibility(),event,context):truth;
  }
  var region=context.snapshot().context().regions().get(f.region);
  if(region==null||region.region().status()!=RegionStatus.ACTIVE)return ConditionEvaluator.Truth.UNKNOWN;
  var players=region.presentPlayers().stream().filter(id->{
   var observed=context.snapshot().context().players().get(id);
   return observed!=null&&observed.eligibility()==PlayerEligibility.ELIGIBLE&&observed.regionIds().contains(f.region);
  }).sorted(Comparator.comparing(PlayerId::value)).toList();
  var scopes=new HashSet<ScopeRef>();scopes.add(new ScopeRef(ScopeKind.WORLD,world.value()));scopes.add(new ScopeRef(ScopeKind.REGION,f.region.value()));
  players.forEach(id->scopes.add(new ScopeRef(ScopeKind.PLAYER,id.value())));
  var actual=requestInput(context);var requests=actual.map(DecisionInput::requests).orElse(List.of());
  var truth=fr.proutlost.director.runtime.ChainConditionKnowledge.evaluate(transition.condition(),knownScars.contains(f.region),actual.isPresent(),
   ()->new ConditionEvaluator().evaluateScope(transition.condition(),Optional.of(f.region),new RoleAssignments(Map.of("participants",players)),
     new ScopeSet(world,scopes),chainInput(f,context,requests)),ConditionEvaluator.Truth.UNKNOWN);
  if(truth==ConditionEvaluator.Truth.TRUE&&transition.eventId().isPresent()){
   var event=event(transition.eventId().orElseThrow());
   if(event.calibration()!=Calibration.APPROVED||!driver.supports(event.id()))return ConditionEvaluator.Truth.FALSE;
   truth=condition(f,event.eligibility(),event,context);
  }
  return truth;
 }
 private List<DurableRecord> settlement(Flight f,ChainInstance next,Context context){
  if(next.state()!=ChainState.SETTLED_AS_WORLD_STATE)return List.of();
  var memories=context.memory().regions().stream().filter(m->m.regionId().equals(f.region)).toList();
  if(memories.size()!=1||next.regionalStateRef().isEmpty())throw new MemoryLaneBusy();
  var change=ChainStateMemoryProjection.settlement(context.memory(),f.region,next,now());
  if(change.isEmpty()){
   var memory=memories.getFirst();
   if(!memory.persistentStateRefs().contains(next.regionalStateRef().orElseThrow())||!memory.durableFactRefs().containsAll(next.consumedFactIds()))throw new MemoryLaneBusy();
   return List.of();
  }
  if(!driver.acquireMemoryLane(f))throw new MemoryLaneBusy();
  f.memoryLane=true;f.expectedMemory=change.get().after();return List.of(change.get().record());
 }
 /** Bounded catalogue/fact cursor. Only a committed source can create the first
  * branch; no fixture primer, command, or synthetic context enters this path. */
 private void productionStarts(){
  if(!production()||now().value()<nextProductionStart||activeFlightCount()>=2)return;
  long availableBegan=chainDiagnostics?System.nanoTime():0;boolean available=driver.available();
  if(chainDiagnostics)startAvailabilityNanos=Math.max(0,System.nanoTime()-availableBegan);
  if(!available)return;
  nextProductionStart=now().value()+20;
  // This exact eligibility filter grants nothing. An empty approved set cannot
  // consume a context; retain every control/freshness check for a real candidate.
  var definitions=content.catalog().chains().values().stream().filter(d->d.calibration()==Calibration.APPROVED)
   .sorted(Comparator.comparing(d->d.id().value())).toList();
  if(chainDiagnostics)lastApprovedChainCount=definitions.size();
  if(definitions.isEmpty()){productionDiagnostic="NO_APPROVED_CHAIN";return;}
  if(chainDiagnostics)startContextReads++;
  var current=context();if(current.isEmpty()||!admissionsOpen(current.get())){productionDiagnostic="WAIT_PRODUCTION_CONTEXT_OR_CONTROL";return;}
  var context=current.get();
  var definition=definitions.get(Math.floorMod(productionDefinitionCursor,definitions.size()));
  var entries=definition.transitions().stream().filter(t->t.from().equals(definition.entryState())).toList();
  var remaining=productionFactCursor==null?facts:facts.tailMap(productionFactCursor,false);
  var iterator=remaining.values().iterator();int examined=0;
  while(iterator.hasNext()&&examined++<4){
   var fact=iterator.next();productionFactCursor=fact.factId();
   if(!productionSource(fact)||entries.stream().noneMatch(t->t.onFact().equals(fact.factType()))
      ||fact.payload().containsKey("chain")||fact.payload().containsKey("parent_chain"))continue;
   var factRegions=fact.subjectRefs().stream().filter(s->s.kind()==ScopeKind.REGION).map(s->new RegionId(s.id())).distinct().toList();
   List<RegionId> regions;
   if(definition.scope()==ScopeKind.REGION)regions=factRegions;
   else{
    if(!fact.subjectRefs().contains(new ScopeRef(ScopeKind.WORLD,world.value())))continue;
    regions=factRegions.isEmpty()?context.snapshot().context().regions().keySet().stream()
     .filter(r->!context.snapshot().context().regions().get(r).presentPlayers().isEmpty()).toList():factRegions;
    if(regions.size()!=1){productionDiagnostic="WAIT_WORLD_LINEAGE_UNAMBIGUOUS";continue;}
   }
   for(var region:regions.stream().limit(64).sorted(Comparator.comparing(RegionId::value)).toList()){
    if(flights.values().stream().anyMatch(f->f.definition.id().equals(definition.id())&&f.region.equals(region)&&occupiesActiveSlot(f)))continue;
    var regional=context.snapshot().context().regions().get(region);if(regional==null||regional.region().status()!=RegionStatus.ACTIVE)continue;
    var scope=new ScopeRef(definition.scope(),definition.scope()==ScopeKind.WORLD?world.value():region.value());
    var identity="production-chain-v1/"+world.value()+"/"+reference(definition).hash().value()+"/"+scope.kind()+"/"+scope.id()+"/"+region.value()+"/"+fact.factId().value();
    var id=new ChainId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)));if(flights.containsKey(id)||restored.containsKey(id))continue;
    for(var playerId:regional.presentPlayers().stream().sorted(Comparator.comparing(PlayerId::value)).toList()){
     var observed=context.snapshot().context().players().get(playerId);var player=server.getPlayerList().getPlayer(playerId.value());
     if(observed==null||observed.eligibility()!=PlayerEligibility.ELIGIBLE||!observed.regionIds().contains(region)||!participant(player)||client(player).isEmpty())continue;
     var f=new Flight();f.id=id;f.actor=playerId.value();f.region=region;f.definition=definition;
     f.runner=ChainRunner.initial(store,definition,id,scope,Set.of(region),now());
     var scores=new HashMap<ResourceId,Double>();
     for(var transition:entries)if(transition.onFact().equals(fact.factType())&&transitionCondition(f,transition,context)==ConditionEvaluator.Truth.TRUE)scores.put(transition.id(),1.0);
     if(scores.isEmpty())continue;
     try{
      if(f.runner.propose(fact,scores,id.value().getLeastSignificantBits()^fact.factId().value().getMostSignificantBits(),now(),new TransactionId(UUID.randomUUID()),next->{
       var records=new ArrayList<DurableRecord>();records.add(new DurableFact(meta(START,f,Map.of("source_fact",text(fact.factId().value()),"activation",text("APPROVED_CAUSAL_FACT")))));
       records.addAll(settlement(f,next,context));return List.copyOf(records);
      }).isPresent()){
       flights.put(id,f);f.pendingBranch=true;f.waiting="INITIAL_BRANCH_J04_ACK";productionDiagnostic="INITIAL_BRANCH_J04_ACK "+id.value();return;
      }
     }catch(MemoryLaneBusy busy){productionDiagnostic="WAIT_MEMORY_OWNER";return;}
    }
   }
  }
  if(!iterator.hasNext()){productionFactCursor=null;productionDefinitionCursor=(productionDefinitionCursor+1)%definitions.size();}
  productionDiagnostic="WAIT_REAL_SOURCE_FACT_OR_CONDITION";
 }
 /** Recovery may admit a new future child only after exact historical outcomes.
  * An unresolved old intent remains fenced; explicit admin suspension is retained. */
 private void productionRecovery(Flight f){
  if(!production()||!f.recovered||f.cancelled)return;
  var current=context();if(current.isEmpty()||!admissionsOpen(current.get()))return;
  if(f.definition.recovery()==RecoveryMode.ABORT_SAFE){
   f.cancelled=true;f.suspensionCommitted=false;f.suspension=append(List.of(new DurableFact(meta(SUSPEND,f,Map.of("reason",text("RECOVERY_ABORT_SAFE"))))));return;
  }
  var decision=recovery(f);if(!decision.allowed()){f.waiting=decision.reason();return;}
  if(f.recoveryResume==null){
   f.recoveryResume=append(List.of(new DurableFact(meta(RESUME,f,Map.of("reason",text("PRODUCTION_FUTURE_ONLY"),"branch_revision",text(f.runner.snapshot().header().revision().value()))))));
   f.waiting="RECOVERY_RESUME_J04_ACK";return;
  }
  if(!durable(f.recoveryResume))return;
  f.recoveryResume=null;f.current=null;f.recovered=false;f.queued=decision.next().orElse(null);f.waiting="RECOVERED_FUTURE_ONLY";
 }
 private void queueDispatch(Flight f,DefinitionId definition,boolean primer){
  var dispatch=new Dispatch();dispatch.chain=f.id;dispatch.event=UUID.randomUUID();dispatch.definition=definition;dispatch.primer=primer;
  dispatch.tick=now();dispatch.branchRevision=Optional.of(f.runner.snapshot().header().revision());
  dispatch.tx=append(List.of(new DurableFact(meta(DISPATCH,f,Map.of("event",text(dispatch.event),"definition",text(definition.value()),"primer",new JsonValue.Bool(primer),
   "phase",text("INTENT"),"natural_eligibility_bypassed",new JsonValue.Bool(primer),
   "branch_revision",text(dispatch.branchRevision.orElseThrow().value()))))));
  f.current=dispatch;dispatches.put(dispatch.event,dispatch);f.queued=null;f.waiting="DISPATCH_INTENT_ACK";
 }
 /** Called by the real native provider only inside its final RESULT/END batch.
  * The caller supplies actual RESULT/received-header evidence; no network command can call this method. */
 public List<DurableRecord> completionRecords(UUID event,DefinitionId definition,Map<String,JsonValue> evidence){
  actor();var dispatch=dispatches.get(event);if(dispatch==null||dispatch.recovered||!dispatch.definition.equals(definition))return List.of();
  var f=flights.get(dispatch.chain);if(f==null||f.cancelled||evidence.isEmpty())return List.of();
  var values=new TreeMap<>(evidence);values.put("chain",text(f.id.value()));values.put("source_event",text(event));values.put("definition",text(definition.value()));
  values.put("actor",text(f.actor));values.put("manual_lab",new JsonValue.Bool(!production()));values.put("human_perception_qualified",new JsonValue.Bool(false));
  values.put("provider_mode",text(production()?ProviderMode.PRODUCTION:ProviderMode.TEST_ONLY));
  var id=new FactId(UUID.nameUUIDFromBytes(("lab-chain-completion-v1/"+f.id.value()+"/"+event+"/"+definition.value()).getBytes(StandardCharsets.UTF_8)));
  var fact=new DirectorFact(id,new FactType(event(definition).completion().factType().value()),
   List.of(new ScopeRef(ScopeKind.WORLD,world.value()),new ScopeRef(ScopeKind.REGION,f.region.value())),Optional.of(new EventInstanceId(event)),EvidenceStatus.CONFIRMED,values,true,Optional.empty());
  return List.of(new DurableFact(fact));
 }
 private long pendingCapacity(){
  return pendingStarts.values().stream().filter(p->p.resumeChain().isEmpty()
   ||!occupiesActiveSlot(flights.get(p.resumeChain().orElseThrow()))).count();
 }
 private boolean completed(Dispatch dispatch){
  var expected=new FactType(event(dispatch.definition).completion().factType().value());
  return facts.values().stream().anyMatch(fact->fact.eventId().equals(Optional.of(new EventInstanceId(dispatch.event)))
   &&fact.factType().equals(expected)&&fact.subjectRefs().contains(new ScopeRef(ScopeKind.REGION,flights.containsKey(dispatch.chain)
     ?flights.get(dispatch.chain).region.value():UUID.fromString(text(starts.get(dispatch.chain),"region"))))
   &&belongsTo(fact,dispatch.chain));
 }
 private boolean provenAborted(Dispatch dispatch){
  var start=starts.get(dispatch.chain);var flight=flights.get(dispatch.chain);
  if(start==null&&flight==null)return false;
  var region=flight!=null?flight.region:new RegionId(UUID.fromString(text(start,"region")));
  var actor=flight!=null?flight.actor:UUID.fromString(text(start,"actor"));
  for(var fact:facts.values()){
   if(!fact.durable()||fact.evidenceStatus()!=EvidenceStatus.CONFIRMED
      ||!fact.eventId().equals(Optional.of(new EventInstanceId(dispatch.event)))||!belongsTo(fact,dispatch.chain)
      ||!fact.subjectRefs().contains(new ScopeRef(ScopeKind.WORLD,world.value()))
      ||!fact.subjectRefs().contains(new ScopeRef(ScopeKind.REGION,region.value()))
      ||!new JsonValue.Text(dispatch.definition.value()).equals(fact.payload().get("definition")))continue;
   if(fact.factType().equals(new FactType("proutlost_director:catalogue_event_aborted"))){
    if(new JsonValue.Text("CATALOGUE_SELECTED").equals(fact.payload().get("source"))
       &&(new JsonValue.Bool(true).equals(fact.payload().get("no_physical_effect"))
          ||new JsonValue.Bool(true).equals(fact.payload().get("recovery_complete"))))return true;
   }
   if(!fact.factType().equals(new FactType("proutlost_director:catalogue_request_aborted"))
      ||!new JsonValue.Text("CATALOGUE_REQUEST").equals(fact.payload().get("source"))
      ||!new JsonValue.Text("EXECUTOR_PRESTART_CANCEL").equals(fact.payload().get("proof_source"))
      ||!new JsonValue.Bool(true).equals(fact.payload().get("no_driver_start"))
      ||!new JsonValue.Bool(true).equals(fact.payload().get("no_physical_effect"))
      ||!new JsonValue.Text(dispatch.event.toString()).equals(fact.payload().get("requested_event"))
      ||!new JsonValue.Text(actor.toString()).equals(fact.payload().get("actor"))
      ||!new JsonValue.Text(region.value().toString()).equals(fact.payload().get("region"))
      ||!new JsonValue.Text(dispatch.chain.value().toString()).equals(fact.payload().get("parent_chain")))continue;
   if(!(fact.payload().get("boot_epoch") instanceof JsonValue.Text bootText)||!(fact.payload().get("wal_session") instanceof JsonValue.Text walText))continue;
   try{
    long boot=Long.parseLong(bootText.value());UUID.fromString(walText.value());if(boot<0)continue;
    if(new JsonValue.Bool(true).equals(fact.payload().get("audit_not_submitted"))){
     if(!fact.payload().containsKey("decision")&&!fact.payload().containsKey("audit_status"))return true;
    }else if(new JsonValue.Text("NO_PLAN_DURABLE").equals(fact.payload().get("audit_status"))
        &&fact.payload().get("decision") instanceof JsonValue.Text decision
        &&durableAudits.contains(new DecisionId(UUID.fromString(decision.value()))))return true;
   }catch(IllegalArgumentException|ClassCastException invalid){/* Malformed proof remains unknown. */}
  }
  return false;
 }
 private static boolean belongsTo(DirectorFact fact,ChainId chain){
  for(var key:List.of("chain","parent_chain"))if(fact.payload().get(key) instanceof JsonValue.Text owner&&!owner.value().equals(chain.value().toString()))return false;
  return true;
 }
 private boolean consumed(ChainInstance chain,DirectorFact fact){
  if(chain.consumedFactIds().contains(fact.factId()))return true;
  return fact.eventId().isPresent()&&facts.values().stream().anyMatch(prior->chain.consumedFactIds().contains(prior.factId())
    &&prior.eventId().equals(fact.eventId())&&prior.factType().equals(fact.factType()));
 }
 private ChainDispatchRecovery.Decision recovery(Flight f){
  var intents=dispatches.values().stream().filter(d->d.chain.equals(f.id))
   .map(d->new ChainDispatchRecovery.Intent(new EventInstanceId(d.event),d.definition,d.tick,d.branchRevision)).toList();
  var completed=new HashSet<EventInstanceId>();
  dispatches.values().stream().filter(d->d.chain.equals(f.id)&&completed(d)).forEach(d->completed.add(new EventInstanceId(d.event)));
  var aborted=new HashSet<EventInstanceId>();
  dispatches.values().stream().filter(d->d.chain.equals(f.id)&&provenAborted(d)).forEach(d->aborted.add(new EventInstanceId(d.event)));
  var entry=!production()&&f.definition.id().value().endsWith("/camp_short_arc")?Optional.of(CAMP):Optional.<DefinitionId>empty();
  return ChainDispatchRecovery.classify(f.definition,f.runner.snapshot(),intents,completed,aborted,entry);
 }
 /** New explicit authorization, retained only in RAM until its exact J04 RESUME ACK.
  * Historical intents never call a provider again. A new UUID is issued only for a future branch.
  */
 public String resume(ServerPlayer player,UUID id){
  requireLaboratory();
  actor();if(!player.hasPermissions(4)||!player.isAlive())return "OP4 vivant requis.";
  if(!scanned||failed||store.readOnlySafe())return "Chaines en attente de recuperation J04.";
  var chain=new ChainId(id);var f=flights.get(chain);if(f==null)return "Chaine inconnue.";
  if(!f.actor.equals(player.getUUID()))return "Seul l'operateur initial peut autoriser cette reprise.";
  if(!f.recovered&&!f.cancelled)return "Chaine deja active; aucun ancien enfant ne sera rejoue.";
  if(f.resumption!=null)return "Reprise deja en attente de son ACK J04.";
  var key=new PendingKey(player.getUUID(),"resume/"+id);
  if(pendingStarts.containsKey(key))return "Reprise deja demandee; TTL/cible/session conserves.";
  if(!occupiesActiveSlot(f)&&activeFlightCount()+pendingCapacity()>=2)return "Deux chaines actives ou demandes occupent la capacite.";
  var session=client(player);if(session.isEmpty())return "Session J06 actuelle et symbole WARN negocie requis.";
  if(!(player.pick(6,0,false) instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK)return "Visez une cible reelle a six blocs.";
  var pos=hit.getBlockPos();var level=player.serverLevel();
  if(level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null)return "Cible non chargee.";
  if(DirectorStandaloneMod.directorLifecycle().runtimePrimaryRegion(server,point(player)).filter(f.region::equals).isEmpty())return "WAIT_ORIGINAL_REAL_REGION";
  var target=new BlockPosition(point(player).dimension(),pos.getX(),pos.getY(),pos.getZ());
  var request=new PendingStart(key,store.session(),session.get(),f.region,target,hit.getDirection(),
   BlockTransformBinding.stateBlob(level.getBlockState(pos)).reference().hash(),Math.addExact(server.overworld().getGameTime(),1200),Optional.of(chain));
  pendingStarts.put(key,request);return "PENDING_RESUME "+id+"; meme cible/face/session, maximum 60 s.";
 }
 private String resumeAtContext(ServerPlayer player,PendingStart request,Context context){
  var f=flights.get(request.resumeChain().orElseThrow());
  if(f==null||!f.actor.equals(player.getUUID()))return "Chaine/operateur initial indisponible.";
  if(!f.recovered&&!f.cancelled)return "Chaine deja active.";
  if(f.resumption!=null||f.suspension!=null||f.pendingBranch||f.initial!=null)return "WAIT_PREVIOUS_CHAIN_METADATA_ACK";
  var decision=recovery(f);if(!decision.allowed())return decision.reason();
  if(!occupiesActiveSlot(f)&&activeFlightCount()>=2)return "Capacite active atteinte.";
  if(decision.next().isPresent()){
   var event=event(decision.next().orElseThrow());
   if(!driver.supports(event.id()))return "WAIT_REAL_PROVIDER "+event.id().value();
   if(condition(f,event.eligibility(),event,context)!=ConditionEvaluator.Truth.TRUE)return "WAIT_RESUME_REAL_ELIGIBILITY";
  }
  f.afterResume=decision.next().orElse(null);f.resumeRequest=request;f.resumeCancelled=false;
  f.resumption=append(List.of(new DurableFact(meta(RESUME,f,Map.of("reason",text(decision.reason()),
   "next",text(f.afterResume==null?"NONE":f.afterResume.value()),
   "connection_epoch",text(request.clientSession().connectionEpoch().value()),
   "previous_intents_replayed",new JsonValue.Bool(false))))));
  f.waiting="RESUME_J04_ACK";return "Reprise future demandee sous J04; aucun ancien INTENT rejoue.";
 }
 private boolean memoryVisible(RegionMemory expected){
  var life=DirectorStandaloneMod.directorLifecycle();var projection=life.memoryProjectionView(server);var view=life.runtimeMemoryView(server);
  if(projection.isEmpty()||!projection.get().complete()||projection.get().failClosed()||view.isEmpty())return false;
  var rows=view.get().regions().stream().filter(m->m.regionId().equals(expected.regionId())).toList();
  return rows.size()==1&&rows.getFirst().header().revision().compareTo(expected.header().revision())>=0
   &&rows.getFirst().persistentStateRefs().equals(expected.persistentStateRefs())
   &&rows.getFirst().durableFactRefs().containsAll(expected.durableFactRefs());
 }
 /** Only actual current mutation rows at the same published memory cut may update scar flags. */
 private boolean pollMemory(){
  // A terminal branch owns the lane until its already committed memory row is visible.
  if(flights.values().stream().anyMatch(f->f.memoryLane))return true;
  if(memoryWrite!=null){
   if(!durable(memoryWrite.tx)||!memoryVisible(memoryWrite.expected))return false;
   driver.releaseMemoryLane(memoryWrite.owner);memoryWrite=null;return false;
  }
  if(scarRead!=null){
   if(!scarRead.future.isDone())return false;
   var pending=scarRead;scarRead=null;
   var read=pending.future.getNow(null);ReadContracts.validate(read,pending.query,pending.session);
   if(!store.session().equals(pending.session)||read.status()!=ReadStatus.AVAILABLE
      ||read.page().orElseThrow().lastDurableSequence().compareTo(pending.context.cut())!=0){
    driver.releaseMemoryLane(scarMemoryOwner);knownScars.remove(pending.region);return false;
   }
   var rows=read.page().orElseThrow().aggregates();var mutations=new ArrayList<MutationRecord>();
   for(var row:rows){if(!(row.state() instanceof MutationAggregate mutation)){driver.releaseMemoryLane(scarMemoryOwner);return false;}mutations.add(mutation.value());}
   var value=ChainStateMemoryProjection.scarValue(pending.region,pending.sources,mutations);
   if(value.isEmpty()){knownScars.remove(pending.region);driver.releaseMemoryLane(scarMemoryOwner);return true;}
   var change=ChainStateMemoryProjection.scar(pending.context.memory(),pending.region,pending.sources,mutations,now());
   if(change.isPresent()){
    knownScars.remove(pending.region);memoryWrite=new MemoryWrite();memoryWrite.owner=scarMemoryOwner;memoryWrite.expected=change.get().after();
    memoryWrite.tx=append(List.of(change.get().record()));return false;
   }
   var memories=pending.context.memory().regions().stream().filter(m->m.regionId().equals(pending.region)).toList();
   boolean matches=memories.size()==1&&memories.getFirst().persistentStateRefs().contains(SCAR)==value.get()
     &&memories.getFirst().durableFactRefs().containsAll(pending.sources.stream().map(DirectorFact::factId).toList());
   if(matches)knownScars.add(pending.region);else knownScars.remove(pending.region);
   driver.releaseMemoryLane(scarMemoryOwner);return true;
  }
  var regions=flights.values().stream().filter(f->!f.cancelled&&f.definition.id().value().endsWith("/living_scar"))
   .filter(f->!f.runner.snapshot().currentNodes().stream().allMatch(f.definition.terminalStates()::contains))
   .map(f->f.region).distinct().toList();
  knownScars.retainAll(regions);
  for(int n=0;n<regions.size();n++){
   int index=(scarCursor+n)%regions.size();var region=regions.get(index);
   var sources=facts.values().stream().filter(f->f.factType().equals(LANDSLIDE)
     &&f.subjectRefs().contains(new ScopeRef(ScopeKind.REGION,region.value()))).toList();
   if(sources.isEmpty()){knownScars.remove(region);continue;}
   var keys=new HashSet<AggregateKey>();
   for(var fact:sources){
    if(!(fact.payload().get("mutation_refs") instanceof JsonValue.Array refs)){keys.clear();break;}
    for(var ref:refs.values()){
     if(!(ref instanceof JsonValue.Text id)){keys.clear();break;}
     keys.add(new AggregateKey(AggregateKind.MUTATION,UUID.fromString(id.value())));
    }
   }
   if(keys.isEmpty()||keys.size()>64){knownScars.remove(region);continue;}
   var current=context();if(current.isEmpty())return false;
   if(!driver.acquireMemoryLane(scarMemoryOwner))return false;
   var pending=new ScarRead();pending.context=current.get();pending.region=region;pending.sources=sources;pending.session=store.session();
   pending.query=new StoreQuery(world,Set.copyOf(keys),new Sequence(0),Optional.empty(),keys.size());
   pending.future=store.read(pending.query).toCompletableFuture();scarRead=pending;scarCursor=(index+1)%regions.size();return false;
  }
  return true;
 }
 private DispatchGuard dispatchGuard(Flight f,ServerPlayer player){
  var session=client(player);if(session.isEmpty())return null;
  var origin=f.origin;
  return new DispatchGuard(store.session(),session.get(),f.region,point(player).dimension(),
    origin==null?Math.addExact(server.overworld().getGameTime(),1200):origin.deadline(),origin);
 }
 private String invalid(DispatchGuard guard,Flight f){
  var player=server.getPlayerList().getPlayer(f.actor);
  if(guard==null)return "DISPATCH_GUARD_MISSING";
  if(guard.origin()!=null)return invalid(guard.origin(),player);
  if(!participant(player))return "PARTICIPANT_LEFT_OR_INELIGIBLE";
  if(server.overworld().getGameTime()>=guard.deadline())return "REQUEST_EXPIRED";
  if(!store.session().equals(guard.storeSession()))return "WAL_SESSION_CHANGED";
  if(client(player).filter(guard.clientSession()::equals).isEmpty())return "J06_SESSION_CHANGED";
  if(!point(player).dimension().equals(guard.dimension()))return "DIMENSION_CHANGED";
  if(DirectorStandaloneMod.directorLifecycle().runtimePrimaryRegion(server,point(player)).filter(guard.region()::equals).isEmpty())return "REGION_CHANGED_OR_UNKNOWN";
  return null;
 }
 private void launchResult(Flight f,Dispatch dispatch,Launch result){
  if(result.pending()){dispatch.driverPending=true;f.waiting="WAIT_DRIVER_PENDING_START";return;}
  dispatch.driverPending=false;dispatch.guard=null;
  append(List.of(new DurableFact(meta(DISPATCH,f,Map.of("event",text(dispatch.event),"definition",text(dispatch.definition.value()),
    "phase",text(result.accepted()?"STARTED":"REFUSED"),"message",text(result.message()))))));
  if(result.accepted())f.origin=null;
  f.waiting=result.accepted()?"WAIT_REAL_COMPLETION":"WAIT_DRIVER_REFUSED_NO_RETRY";
  System.out.println("DIRECTOR_LAB_CHAIN_DISPATCH chain="+f.id.value()+" event="+dispatch.event+" definition="+dispatch.definition.value()+" accepted="+result.accepted());
 }
 /** Observe the final cache before origin/hash/available checks: the actual start may already
  * have changed its own target. While still pending, no future start survives invalid consent.
  */
 private boolean driverPending(Flight f){
  var dispatch=f.current;if(dispatch==null||!dispatch.driverPending)return false;
  var cached=driver.pendingStart(dispatch.event);
  if(cached.isPresent()&&!cached.get().pending()){launchResult(f,dispatch,cached.get());return false;}
  String reason=f.cancelled?"EXPLICIT_OP4_SUSPEND":invalid(dispatch.guard,f);
  if(reason!=null){
   if(!dispatch.cancelRequested){driver.cancelPending(dispatch.event,reason);dispatch.cancelRequested=true;}
   if(!f.cancelled){f.cancelled=true;f.suspensionCommitted=false;f.suspension=append(List.of(new DurableFact(meta(SUSPEND,f,Map.of("reason",text(reason))))));}
   f.waiting="WAIT_PENDING_START_CANCELLED";return false;
  }
  f.waiting="WAIT_DRIVER_PENDING_START";return true;
 }
 private void step(Flight f){
  if(f.resumption!=null){
   if(!durable(f.resumption)){f.waiting="RESUME_J04_ACK";return;}
   f.resumption=null;
   if(f.resumeCancelled){f.resumeCancelled=false;f.resumeRequest=null;f.afterResume=null;}
   else{
    var player=server.getPlayerList().getPlayer(f.actor);var request=f.resumeRequest;var reason=invalid(request,player);f.resumeRequest=null;
    if(reason!=null){f.cancelled=true;f.suspensionCommitted=false;f.suspension=append(List.of(new DurableFact(meta(SUSPEND,f,Map.of("reason",text(reason))))));return;}
    f.cancelled=false;f.suspensionCommitted=false;f.recovered=false;f.current=null;f.queued=f.afterResume;f.afterResume=null;
    f.origin=f.queued==null?null:request;
    f.waiting="RESUMED_FUTURE_ONLY";
   }
  }
  if(f.initial!=null){if(!durable(f.initial))return;f.initial=null;}
  if(f.pendingBranch){
   if(f.committedBranch==null)f.committedBranch=f.runner.pollCommitted().orElse(null);
   if(f.committedBranch==null)return;
   if(f.expectedMemory!=null&&!memoryVisible(f.expectedMemory)){f.waiting="WAIT_ACTUAL_MEMORY_PROJECTION";return;}
   if(f.memoryLane){driver.releaseMemoryLane(f);f.memoryLane=false;f.expectedMemory=null;}
   f.pendingBranch=false;f.queued=f.committedBranch.nextEvent().orElse(null);f.committedBranch=null;
   f.waiting=f.queued==null?"TERMINAL_OR_WAIT_SOURCE_FACT":"NEXT_EVENT_PENDING";
   System.out.println("DIRECTOR_LAB_CHAIN_BRANCH_COMMITTED chain="+f.id.value()+" nodes="+f.runner.snapshot().currentNodes()+" next="+f.queued);
  }
  if(driverPending(f))return;
  if(f.cancelled){if(f.suspension!=null){if(!durable(f.suspension)){f.waiting="SUSPEND_J04_ACK";return;}f.suspensionCommitted=true;f.suspension=null;}f.waiting="SUSPENDED_CHILD_EFFECTS_KEEP_NORMAL_CLEANUP";return;}
  if(f.current!=null){
   var dispatch=f.current;
   if(dispatch.recovered||f.recovered){f.waiting="WAIT_RESTART_MANUAL_NO_REPLAY";return;}
   if(dispatch.tx!=null){
    if(!durable(dispatch.tx))return;var player=server.getPlayerList().getPlayer(f.actor);
    if(!participant(player)){f.waiting="WAIT_DISPATCH_REAL_PARTICIPANT";return;}
    if(!driver.available()){f.waiting="WAIT_LAB_CHILD_LANE";return;}
    if(f.origin!=null){
     var reason=invalid(f.origin,player);
     if(reason!=null){
      f.cancelled=true;f.suspension=append(List.of(new DurableFact(meta(SUSPEND,f,Map.of("reason",text(reason))))));
      answer(f.origin,"Chaine suspendue avant effet: "+reason);f.waiting="SUSPEND_J04_ACK";return;
     }
    }
    var current=context();
    if(current.isEmpty()||!admissionsOpen(current.get())||region(player,current.get()).filter(f.region::equals).isEmpty()){f.waiting="WAIT_DISPATCH_REAL_REGION_OR_CONTROL";return;}
    if(!dispatch.primer){
     var event=event(dispatch.definition);
     if(condition(f,event.eligibility(),event,current.get())!=ConditionEvaluator.Truth.TRUE){f.waiting="WAIT_DISPATCH_REAL_ELIGIBILITY";return;}
     var history=f.runner.snapshot().branchHistory();
     if(!history.isEmpty()){
      var selected=history.getLast().transitionId();
      var transition=f.definition.transitions().stream().filter(t->t.id().equals(selected)).findFirst().orElseThrow();
      if(transitionCondition(f,transition,current.get())!=ConditionEvaluator.Truth.TRUE){f.waiting="WAIT_DISPATCH_REAL_TRANSITION";return;}
     }
    }
    var guard=dispatchGuard(f,player);if(guard==null){f.waiting="WAIT_DISPATCH_REAL_J06_SESSION";return;}
    dispatch.guard=guard;dispatch.tx=null;dispatch.driverPending=true;
    launchResult(f,dispatch,driver.start(player,dispatch.definition,dispatch.event,f.region,f.id));return;
   }
   boolean finished=completed(dispatch),aborted=provenAborted(dispatch);
   if(finished&&aborted)throw new IllegalStateException("conflicting durable child completion/abort");
   if(aborted){
    f.cancelled=true;f.suspensionCommitted=false;f.suspension=append(List.of(new DurableFact(meta(SUSPEND,f,Map.of("reason",text("CHILD_PROVEN_ABORTED_EXPLICIT_FUTURE_RETRY"),"event",text(dispatch.event))))));
    f.waiting="SUSPEND_PROVEN_ABORT_ACK";return;
   }
   if(!finished)return;
   f.current=null;
  }
  if(f.queued!=null){
   if(f.recovered){f.waiting="WAIT_RESTART_MANUAL_NO_REPLAY";return;}
   if(production()){var current=context();if(current.isEmpty()||!admissionsOpen(current.get())){f.waiting="WAIT_PRODUCTION_CONTROL";return;}}
   var player=server.getPlayerList().getPlayer(f.actor);if(!participant(player)){f.waiting="WAIT_REAL_PARTICIPANT";return;}
   if(!driver.supports(f.queued)){f.waiting="WAIT_REAL_PROVIDER "+f.queued.value();return;}
   // New requests always use the original event eligibility; legacy manual primers remain read-only history.
   queueDispatch(f,f.queued,false);return;
  }
  if(now().value()<f.nextPoll)return;f.nextPoll=now().value()+20;
  var context=context();if(context.isEmpty()){f.waiting="WAIT_J01_J04_CONTEXT";return;}
  if(!admissionsOpen(context.get())){f.waiting="WAIT_PRODUCTION_CONTROL";return;}
  if(!context.get().snapshot.context().regions().containsKey(f.region)){f.waiting="WAIT_REAL_REGION";return;}
  var chain=f.runner.snapshot();if(chain.currentNodes().stream().allMatch(f.definition.terminalStates()::contains)){f.waiting="TERMINAL "+chain.state();return;}
  f.waiting="WAIT_REAL_SOURCE_FACT";
  for(var fact:facts.values()){
   if(consumed(chain,fact)||!sourceScope(f,fact)||!productionSource(fact))continue;
   if(!belongsTo(fact,f.id))continue;
   var scores=new HashMap<ResourceId,Double>();
   for(var transition:f.definition.transitions())if(chain.currentNodes().contains(transition.from())&&transition.onFact().equals(fact.factType())){
    var truth=transitionCondition(f,transition,context.get());
    if(truth==ConditionEvaluator.Truth.TRUE)scores.put(transition.id(),1.0);
    else f.waiting="WAIT_REAL_CONDITION "+transition.id().value()+" "+truth;
   }
   if(scores.isEmpty())continue;
   long seed=f.id.value().getLeastSignificantBits()^fact.factId().value().getMostSignificantBits();
   try{
    if(f.runner.propose(fact,scores,seed,now(),new TransactionId(UUID.randomUUID()),next->{
     return settlement(f,next,context.get());
    }).isPresent()){f.pendingBranch=true;f.waiting="BRANCH_J04_ACK";return;}
   }catch(MemoryLaneBusy busy){f.waiting="WAIT_MEMORY_OWNER";return;}
  }
 }
 public boolean controlRecoveryReady(){actor();return scanned&&!failed&&!store.readOnlySafe();}
 public Set<EventInstanceId> controlEventTargets(Optional<EventInstanceId> target){
  actor();var result=new HashSet<EventInstanceId>();
  for(var f:flights.values())if((target.isEmpty()?occupiesActiveSlot(f):f.current!=null&&f.current.event.equals(target.orElseThrow().value()))&&f.current!=null)
   result.add(new EventInstanceId(f.current.event));
  return Set.copyOf(result);
 }
 /** Suspend only the selected child's parent or all current chains, using the existing durable suspension lane. */
 public Optional<java.util.function.Supplier<AdminStatus>> requestStop(Optional<EventInstanceId> target){
  actor();if(!controlRecoveryReady())return Optional.empty();
  var affected=flights.values().stream().filter(f->target.isEmpty()?occupiesActiveSlot(f):
   f.current!=null&&f.current.event.equals(target.orElseThrow().value())).toList();
  boolean pendingOnly=target.isEmpty()&&!pendingStarts.isEmpty();
  if(affected.isEmpty()&&!pendingOnly)return Optional.empty();
  if(target.isEmpty())pendingStarts.clear();
  for(var f:affected){
   pendingStarts.values().removeIf(p->p.resumeChain().equals(Optional.of(f.id)));
   if(f.resumption!=null)f.resumeCancelled=true;
   if(f.current!=null&&f.current.driverPending){
    var cached=driver.pendingStart(f.current.event);
    if(cached.isPresent()&&!cached.get().pending())launchResult(f,f.current,cached.get());
    else if(!f.current.cancelRequested){driver.cancelPending(f.current.event,"CANONICAL_ADMIN_STOP");f.current.cancelRequested=true;}
   }
   if(!f.cancelled||!f.suspensionCommitted&&f.suspension==null){
    f.cancelled=true;f.suspensionCommitted=false;
    f.suspension=append(List.of(new DurableFact(meta(SUSPEND,f,Map.of("reason",text("CANONICAL_ADMIN_STOP"))))));
   }
  }
  return Optional.of(()->{
   actor();if(failed)return AdminStatus.FAILED_SAFE;
   for(var f:affected)if(!f.cancelled||!f.suspensionCommitted||f.resumption!=null||f.pendingBranch||f.current!=null&&f.current.driverPending)return AdminStatus.PENDING;
   return AdminStatus.SUCCEEDED;
  });
 }
 public String suspend(ServerPlayer player,UUID chain){
  requireLaboratory();
  actor();if(!player.hasPermissions(4))return "OP4 requis.";var f=flights.get(new ChainId(chain));if(f==null)return "Chaine inconnue.";
  pendingStarts.values().removeIf(p->p.resumeChain().equals(Optional.of(f.id)));
  if(f.resumption!=null)f.resumeCancelled=true;
  if(f.current!=null&&f.current.driverPending){
   var cached=driver.pendingStart(f.current.event);
   if(cached.isPresent()&&!cached.get().pending())launchResult(f,f.current,cached.get());
   else if(!f.current.cancelRequested){driver.cancelPending(f.current.event,"EXPLICIT_OP4_SUSPEND");f.current.cancelRequested=true;}
  }
  f.cancelled=true;f.suspensionCommitted=false;f.suspension=append(List.of(new DurableFact(meta(SUSPEND,f,Map.of("reason",text("EXPLICIT_OP4_SUSPEND"))))));return "Suspension demandee sous J04; chaque effet garde son nettoyage durable natif.";
 }
 public void poll(){
  actor();if(failed||!scanned)return;
  long began=chainDiagnostics?System.nanoTime():0,beganCpu=chainDiagnostics?fr.proutlost.director.runtime.DecisionWorkBudget.threadCpuNanos():-1;
  long afterIncremental=began,afterDriver=began,afterMemory=began,afterFlights=began;
  try{if(store.readOnlySafe())throw new IllegalStateException("chain WAL unavailable");incremental();
   if(chainDiagnostics)afterIncremental=System.nanoTime();
   // Consent validation must precede any metadata wait; NaturalRuntime polls after this lane.
   for(var f:flights.values())if(f.current!=null&&f.current.driverPending)driverPending(f);
   if(chainDiagnostics)afterDriver=System.nanoTime();
   boolean memoryReady=pollMemory();if(chainDiagnostics)afterMemory=System.nanoTime();if(!memoryReady){afterFlights=afterMemory;return;}
   pending();for(var f:flights.values()){productionRecovery(f);step(f);}
   if(chainDiagnostics)afterFlights=System.nanoTime();
   productionStarts();
  }
  catch(RuntimeException error){failed=true;
   for(var f:flights.values())if(f.current!=null&&f.current.driverPending&&!f.current.cancelRequested){driver.cancelPending(f.current.event,"CHAIN_RECOVERY_REQUIRED");f.current.cancelRequested=true;}
   System.out.println("DIRECTOR_LAB_CHAIN_RECOVERY_REQUIRED "+error);error.printStackTrace();
  }finally{diagnoseChain(began,beganCpu,afterIncremental,afterDriver,afterMemory,afterFlights);}
 }
 private void incremental(){
  if(scan==null){
   var key=new AggregateKey(AggregateKind.WORLD,world.value());var read=store.view(new StoreQuery(world,Set.of(key),new Sequence(0),Optional.empty(),1));
   if(read.status()!=ReadStatus.AVAILABLE||read.page().get().lastDurableSequence().compareTo(scannedCut)<=0)return;
   scanQuery=new StoreQuery(world,Set.of(),new Sequence(scannedCut.value()+1),cursor,64);scan=store.read(scanQuery).toCompletableFuture();return;
  }
  if(!scan.isDone())return;var read=scan.getNow(null);scan=null;ReadContracts.validate(read,scanQuery,store.session());
  if(read.status()==ReadStatus.STALE){cursor=Optional.empty();return;}
  if(read.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("chain incremental "+read.status());
  var page=read.page().get();acceptRows(page.records());cursor=page.nextCursor();if(!page.hasMore())scannedCut=page.lastDurableSequence();
 }
 private void acceptRows(List<DurableRecord> records){
  for(var record:records){
   if(record instanceof DurableAudit audit&&audit.value().chosen().isEmpty())durableAudits.add(audit.value().decisionId());
   if(record instanceof DurableAggregate a&&a.state() instanceof ChainAggregate c){restored.merge(c.value().chainId(),c.value(),(old,next)->old.header().revision().compareTo(next.header().revision())<0?next:old);offset=Math.max(offset,c.header().updatedTick().value()-server.overworld().getGameTime());}
   if(record instanceof DurableFact d){
    var fact=d.value();
    if(fact.factType().equals(START))starts.put(new ChainId(UUID.fromString(text(fact,"chain"))),fact);
    else if(fact.factType().equals(SUSPEND))suspended.add(new ChainId(UUID.fromString(text(fact,"chain"))));
    else if(fact.factType().equals(RESUME))suspended.remove(new ChainId(UUID.fromString(text(fact,"chain"))));
    else if(fact.factType().equals(DISPATCH)&&text(fact,"phase").equals("INTENT")){
     var event=UUID.fromString(text(fact,"event"));if(!dispatches.containsKey(event)){
      var p=new Dispatch();p.event=event;p.chain=new ChainId(UUID.fromString(text(fact,"chain")));p.definition=new DefinitionId(text(fact,"definition"));p.recovered=true;
      p.tick=new Tick(Long.parseLong(text(fact,"tick")));p.primer=fact.payload().get("primer") instanceof JsonValue.Bool value&&value.value();
      if(fact.payload().get("branch_revision") instanceof JsonValue.Text revision)p.branchRevision=Optional.of(new Revision(Long.parseLong(revision.value())));
      dispatches.put(event,p);
     }
    }else if(fact.durable()&&fact.evidenceStatus()==EvidenceStatus.CONFIRMED&&productionSource(fact)
      &&fact.subjectRefs().stream().anyMatch(scope->scope.kind()==ScopeKind.REGION||scope.kind()==ScopeKind.WORLD))facts.putIfAbsent(fact.factId(),fact);
    if(fact.payload().get("tick") instanceof JsonValue.Text t)offset=Math.max(offset,Long.parseLong(t.value())-server.overworld().getGameTime());
   }
  }
 }
 public void resetRecoveryScan(){actor();scanned=false;scan=null;cursor=Optional.empty();scannedCut=new Sequence(0);pendingStarts.clear();flights.clear();restored.clear();starts.clear();facts.clear();dispatches.clear();suspended.clear();knownScars.clear();durableAudits.clear();offset=0;nextProductionStart=0;productionDefinitionCursor=0;productionFactCursor=null;}
 public void acceptRecoveryRows(List<DurableRecord> records,boolean last,Sequence cut){
  actor();acceptRows(records);if(!last)return;scannedCut=cut;scanned=true;
  for(var entry:starts.entrySet()){
   var saved=entry.getValue();var state=restored.get(entry.getKey());if(state==null)throw new IllegalStateException("chain START without aggregate");
   var f=new Flight();f.id=entry.getKey();f.actor=UUID.fromString(text(saved,"actor"));f.region=new RegionId(UUID.fromString(text(saved,"region")));
   f.definition=definition(production()?text(saved,"recipe"):text(saved,"recipe").substring("proutlost_director:candidate/".length()));f.recovered=true;f.cancelled=suspended.contains(f.id);f.suspensionCommitted=f.cancelled;
   f.runner=new ChainRunner(store,f.definition,state,chainScope(f));f.waiting="WAIT_RESTART_MANUAL_NO_REPLAY";
   dispatches.values().stream().filter(d->d.chain.equals(f.id)&&!completed(d)&&!provenAborted(d)).findFirst().ifPresent(d->f.current=d);
   flights.put(f.id,f);
  }
 }
 public String status(){actor();if(failed)return "CHAIN_RECOVERY_REQUIRED";if(!scanned)return "CHAIN_RECOVERING";
  return (flights.isEmpty()?"CHAIN_IDLE pending="+pendingStarts.size()+" context="+contextDiagnostic:"CHAIN active="+activeFlightCount()+" pending="+pendingStarts.size()+" history="+flights.size()+" | "+String.join(" | ",flights.values().stream().map(f->f.id.value()+" "+f.definition.id().value()+" "+f.runner.snapshot().currentNodes()+" "+f.waiting+" consumed="+f.runner.snapshot().consumedFactIds().size()).toList()))+" | eligibility="+eligibilityDiagnostic+" | production="+productionDiagnostic;}
 @Override public void close(){actor();pendingStarts.clear();scopedHydration=null;scopedCapture=null;closed=true;}
}
