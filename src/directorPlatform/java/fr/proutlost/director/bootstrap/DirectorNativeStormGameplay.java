package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.ServerLevelData;

/** DRAFT. Explicit OP4 LAB STORM: real rain lease plus one real native wind sound per client.
 * Native rain is GLOBAL to overworld. Native thunder is NEVER enabled by this lane.
 * The local rectangle is a visible test-site marker, not a physical weather boundary.
 * All actual living overworld clients must remain at this site and acknowledge WARN.
 * All weather writes use J04; an old boot never replays weather or restores a baseline.
 */
public final class DirectorNativeStormGameplay implements AutoCloseable {
 private DirectorRuntimeContent content;
 private fr.proutlost.director.adapter.neoforge.server.effects.weathercore.WeatherCoreGlobalBinding survey;
 private ProviderMode providerMode(){return content==null?ProviderMode.TEST_ONLY:content.mode();}
 private void requireLaboratory(){if(content!=null)content.requireLaboratory();}

 private static final PrimitiveId WEATHER=new PrimitiveId("proutlost:global_weather_lease"),WARNING=new PrimitiveId("proutlost:warning"),WIND=new PrimitiveId("proutlost:target_sound");
 private static final FactType START=new FactType("proutlost_director:lab_storm_start"),END=new FactType("proutlost_director:lab_storm_end"),PHYSICS=new FactType("proutlost_director:lab_storm_physics");
 private static final ModelCodec<PreparedEffect> PLAN=new ModelCodec<>(PreparedEffect.class);
 private static final ModelCodec<OwnershipRecord> OWNER=new ModelCodec<>(OwnershipRecord.class);
 private static final ModelCodec<Footprint> FOOTPRINT=new ModelCodec<>(Footprint.class);
 private static final ModelCodec<SliceResult> RESULT=new ModelCodec<>(SliceResult.class);
 private static final BudgetVector CAP=new BudgetVector(100,100,100,100,100,2048,4194304,100,1048576,100);
 private static final DurationTicks LEAD=new DurationTicks(400),TTL=new DurationTicks(400);
 private final MinecraftServer server;private final WorldId world;private final JournalStore store;
 private final Map<ResourceKey,OwnershipRecord> owners;private final WeatherOverrideObserver observer;
 private final Function<GlobalWeatherLease,CatalogSnapshot> validatedCatalog;
 private final DurableDisasterRunner phases;private final WeatherBinding weather;
 private final Map<UUID,DirectorFact> starts=new LinkedHashMap<>();
 private final Map<DisasterId,DisasterInstance> disasters=new HashMap<>();
 private final Map<DisasterId,Tick> warnEntries=new HashMap<>();
 private final Map<ResourceKey,OwnershipRecord> journalOwners=new HashMap<>();
 private final Map<MutationId,MutationRecord> journalMutations=new HashMap<>();
 private final Map<OperationToken,PrepareRecord> prepares=new LinkedHashMap<>();
 private final Map<OperationToken,ResultRecord> results=new HashMap<>();
 private final Map<UUID,List<DirectorFact>> facts=new HashMap<>();
 private final Set<OperationToken> durablePrepares=new HashSet<>();
 private final Set<UUID> ends=new HashSet<>();
 private CompletableFuture<StoreReadResult> scanning;private Optional<String> cursor=Optional.empty();
 private boolean scanned,shared,failed,closed;private long floor,clockFloor,offset;private Flight flight;
 private enum Phase { INITIAL,WARN_ACK,MARKERS,PROOF_ACK,LEAD,SEAL,IMPACT_ACK,WIND,WIND_ACK,IMPACT,MUTATION_READ,MUTATION_ACK,JOIN_ACK,HOLD,
  STABILIZE_ACK,CLEANUP,INVERSE,RELEASE_ACK,CLEAN_JOIN_ACK,RESOLVE_ACK,END_ACK,CONFLICT }
 private static final class Tx {DurableBatch batch;StoreSession session;CompletableFuture<CommitAck> ack;}
 private static final class Execution {
  EventRunner runner;TransactionId tx;PrepareRecord prepare;PreparedEffect plan;LiveValidationSnapshot live;DecisionInput selectedInput;
  ResourceArbiter arbiter;ReservationToken token;AdmissionService admission;DecisionId decision;Guarded guard;
  SliceResult actual;BooleanSupplier safelyUninvoked;boolean done,released,retain,aborted;Tx abort;
 }
 private static final class Marker {
  PlayerId player;String corner;PerceptionTransform transform;NativeWarningBinding binding;EffectIntent intent;Execution work;
 }
 private static final class Wind {
  PlayerId player;NativePerceptionBinding binding;EffectIntent intent;Execution work;long receiptDeadline;
 }
 private static final class Flight {
  UUID id,player;DisasterId disaster;DefinitionId definition;GlobalWeatherLease args;CatalogSnapshot catalog;
  SelectedEventStart selection;ExecutionPlan selectedPlan;EventDefinition selectedDefinition;DecisionId selectedDecision;Optional<ChainId> parentChain=Optional.empty();

  Footprint site;Set<PlayerId> audience;Epoch audienceRevision=new Epoch(1);OwnershipRecord initial,next;
  PreparedEffect forward;BlobContent stopPlan;BlobRef inversePost;DisasterInstance state;Phase phase=Phase.INITIAL;Tx tx;
  EffectPreparation captured;Execution work,impact;List<Marker> markers=new ArrayList<>();int markerIndex;List<Wind> winds=new ArrayList<>();int windIndex;
  List<DirectorFact> consequences=new ArrayList<>();MutationRecord mutation;
  CompletableFuture<StoreReadResult> mutationRead;StoreQuery mutationQuery;
  boolean stop,recovery,applied,inverseApplied,afterCleanup,conflict,physicalPrepared;long deadline=-1,markerDeadline,capturedAt=-1,retiming=-1;
  String cleanup="NO_IMPACT";long boot;
 }
 private final class Guarded implements ServerEffectPort.Binding {
  final Flight f;final boolean forward;boolean invoked;
  Guarded(Flight f,boolean forward){this.f=f;this.forward=forward;}
  public ProviderMode mode(){return weather.mode();}
  public Set<PrimitiveId> supportedPrimitives(){return weather.supportedPrimitives();}
  public Set<CapabilityId> capabilities(){return weather.capabilities();}
  public EffectPreparation capture(EffectIntent i,PlanningSnapshot p){return weather.capture(i,p);}
  public boolean revalidate(PreparedEffect p,BlobBundle b){
   return (!forward||contextSafe(f))&&sameLease(f)&&weather.revalidate(p,b);
  }
  public SliceResult apply(PreparedEffect p,BlobBundle b){
   if(!revalidate(p,b))throw new IllegalStateException("storm owner/WARN/audience changed");
   invoked=true;
   return observer.directorWrite(dimension(),()->weather.apply(p,b));
  }
  public ReconcileResult reconcile(RecoveryProbe p){return weather.reconcile(p);}
 }
 private final class WindGuard implements ServerEffectPort.Binding {
  final Flight f;final NativePerceptionBinding delegate;boolean invoked;
  WindGuard(Flight f,NativePerceptionBinding delegate){this.f=f;this.delegate=delegate;}
  public ProviderMode mode(){return delegate.mode();}
  public Set<PrimitiveId> supportedPrimitives(){return delegate.supportedPrimitives();}
  public Set<CapabilityId> capabilities(){return delegate.capabilities();}
  public EffectPreparation capture(EffectIntent i,PlanningSnapshot p){return delegate.capture(i,p);}
  public boolean revalidate(PreparedEffect p,BlobBundle b){return contextSafe(f)&&delegate.revalidate(p,b);}
  public SliceResult apply(PreparedEffect p,BlobBundle b){if(!revalidate(p,b))throw new IllegalStateException("wind WARN/audience changed");invoked=true;return delegate.apply(p,b);}
  public ReconcileResult reconcile(RecoveryProbe p){return delegate.reconcile(p);}
 }
 /** validatedCatalog MUST run the additive J02 loader/validator. A raw typed snapshot is not qualification. */
 public DirectorNativeStormGameplay(MinecraftServer server,WorldId world,JournalStore store,Map<ResourceKey,OwnershipRecord> owners,
   WeatherOverrideObserver observer,Function<GlobalWeatherLease,CatalogSnapshot> validatedCatalog){
  this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.store=Objects.requireNonNull(store);
  this.owners=Objects.requireNonNull(owners);this.observer=Objects.requireNonNull(observer);this.validatedCatalog=Objects.requireNonNull(validatedCatalog);
  phases=new DurableDisasterRunner(store,4,d->new ScopeRef(ScopeKind.EVENT,d.disasterId().value()));
  weather=new WeatherBinding(d->{if(!d.equals(dimension()))throw new IllegalArgumentException("LAB overworld only");return level();},
    ()->new OwnershipView(owners),observer::revision,4);actor();
 }
 public DirectorNativeStormGameplay(MinecraftServer server,WorldId world,JournalStore store,Map<ResourceKey,OwnershipRecord> owners,
     WeatherOverrideObserver observer,DirectorRuntimeContent content){
  this(server,world,store,owners,observer,args->{throw new IllegalStateException("no laboratory catalogue in production");});
  this.content=Objects.requireNonNull(content);if(!content.production())throw new IllegalArgumentException("official production content required");
  survey=new fr.proutlost.director.adapter.neoforge.server.effects.weathercore.WeatherCoreGlobalBinding(d->{if(!d.equals(dimension()))throw new IllegalArgumentException("global dimension");return level();},()->new OwnershipView(owners),observer::revision,4);
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("storm server actor required");}
 private ServerLevel level(){return server.overworld();}
 private DimensionId dimension(){return new DimensionId(level().dimension().location().toString());}
 private ResourceKey key(){return new ResourceKey(OwnershipDomain.WEATHER,dimension(),"weather");}
 private Tick now(){return new Tick(content!=null&&content.production()?fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server):Math.addExact(server.overworld().getGameTime(),offset));}
 private Tick nativeNow(){return new Tick(level().getGameTime());}
 private ServerLevelData data(){return (ServerLevelData)level().getLevelData();}
 private Fingerprint rawHash(){return weather.observedFingerprint(dimension());}
 private static String encode(byte[] b){return Base64.getEncoder().encodeToString(b);}
 private static JsonValue.Text text(Object x){return new JsonValue.Text(x.toString());}
 private static String text(DirectorFact f,String k){return ((JsonValue.Text)f.payload().get(k)).value();}
 private static byte[] decode(DirectorFact f,String k){return NativeDossierJson.decode(f.payload().get(k));}
 private DirectorFact fact(FactType type,Flight f,Map<String,JsonValue> values){
  return new DirectorFact(new FactId(UUID.randomUUID()),type,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),
    Optional.of(new EventInstanceId(f.id)),EvidenceStatus.CONFIRMED,values,true,Optional.empty());
 }
 private Tx append(List<DurableRecord> rows,List<BlobContent> blobs){
  var t=new Tx();t.session=store.session();t.batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),t.session.storeGeneration(),Map.of(),rows,blobs);
  t.ack=store.append(t.batch).toCompletableFuture();return t;
 }
 private Tx append(List<DurableRecord> rows){return append(rows,List.of());}
 private boolean durable(Tx t){
  if(t==null||!t.ack.isDone())return false;var a=t.ack.getNow(null);
  if(a==null||a.ackStatus()!=AckStatus.DURABLE||!store.session().equals(t.session)||!a.transactionId().equals(t.batch.transactionId())
    ||!a.transactionHash().equals(t.batch.transactionHash())||!a.storeGeneration().equals(t.session.storeGeneration())
    ||a.durableSequence().value()<=0||a.permitAttempt().isPresent()||!a.forcedBlobs().equals(DurableBlobReferences.collect(t.batch.records())))
   throw new IllegalStateException("storm metadata ACK mismatch");
  return true;
 }
 private boolean loaded(Flight f){return f.site.loadedChunks().stream().allMatch(c->level().getChunkSource().getChunkNow(c.x(),c.z())!=null);}
 private Set<PlayerId> audience(){
  var out=new HashSet<PlayerId>();for(var p:server.getPlayerList().getPlayers())if(p.isAlive()&&p.serverLevel()==level())out.add(new PlayerId(p.getUUID()));return Set.copyOf(out);
 }
 private boolean siteReady(Flight f){
  var b=f.site.bounds().getFirst();var operator=server.getPlayerList().getPlayer(f.player);
  if(operator==null||!operator.isAlive()||(f.selectedPlan==null&&!operator.hasPermissions(4))||operator.serverLevel()!=level())return false;
  for(var id:f.audience){var p=server.getPlayerList().getPlayer(id.value());
   if(p==null||!p.isAlive()||p.serverLevel()!=level()||p.getX()<b.minX()||p.getX()>=b.maxXExclusive()
      ||p.getZ()<b.minZ()||p.getZ()>=b.maxZExclusive()||Math.abs(p.getY()-b.minY())>4)return false;
  }return true;
 }
 private boolean sameLease(Flight f){return f.initial.equals(owners.get(key()))&&f.args.externalRevision().equals(observer.revision(dimension()));}
 private boolean markersLive(Flight f){return !f.markers.isEmpty()&&f.markers.stream().allMatch(m->m.binding.receivedAndLive());}
 private boolean contextSafe(Flight f){return !f.stop&&selectedControl(f)&&loaded(f)&&siteReady(f)&&audience().equals(f.audience)&&sameLease(f)&&markersLive(f);}
 private boolean selectedControl(Flight f){
  if(f.selectedPlan==null)return true;
  var session=store.session();var q=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.WORLD,world.value())),new Sequence(0),Optional.empty(),1);
  var read=store.view(q);ReadContracts.validate(read,q,session);
  return !store.readOnlySafe()&&read.status()==ReadStatus.AVAILABLE&&read.page().orElseThrow().aggregates().stream().anyMatch(a->a.state() instanceof WorldAggregate w
   &&w.value().runControl()==RunControl.RUNNING&&w.value().healthMode()==HealthMode.NORMAL&&w.value().catalogHash().equals(content.catalog().hash())
   &&AdminWorldControlHandler.configuration(w.value(),content.profiles()).calibration()==Calibration.APPROVED);
 }

 private Footprint site(ServerPlayer player){
  int x=player.blockPosition().getX(),y=player.blockPosition().getY(),z=player.blockPosition().getZ();
  var chunks=new HashSet<ChunkRef>();for(int cx=(x-5)>>4;cx<=((x+5)>>4);cx++)for(int cz=(z-5)>>4;cz<=((z+5)>>4);cz++){
   if(level().getChunkSource().getChunkNow(cx,cz)==null)throw new IllegalArgumentException("storm test-site chunk unavailable");
   chunks.add(new ChunkRef(dimension(),cx,cz));
  }
  return new Footprint(dimension(),chunks,Set.of(),List.of(new Aabb(dimension(),x-5,y,z-5,x+6,y+5,z+6)));
 }
 private Footprint physicalFootprint(){return new Footprint(dimension(),Set.of(),Set.of(),List.of());} // Dimension-wide, not bounded by test-site corners.
 private PlanningSnapshot snapshot(Flight f){
  var view=new HashMap<>(owners);if(f.phase==Phase.INITIAL)view.put(key(),f.initial);
  if(f.selection!=null){
   var input=f.selection.refresh();var sample=input.context();var actualChunks=new HashSet<>(sample.loadedChunks());
   if(!loaded(f))throw new SelectedEventStart.ContextUnavailable("global warning footprint unavailable");actualChunks.addAll(f.site.loadedChunks());
   var observed=new SnapshotBatch(sample.requestId(),sample.scopes(),sample.capturedTick(),sample.scopeRevisions(),sample.context(),sample.missingData(),actualChunks,sample.serverHealth());
   return new PlanningSnapshot(observed,new OwnershipView(view),input.state().budgets(),new BlobBundle(List.of()));
  }
  return new PlanningSnapshot(new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),now(),Map.of(),
    new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),Set.of(),f.site.loadedChunks(),HealthMode.NORMAL),
    new OwnershipView(view),new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));
 }
 private EffectIntent intent(Flight f,boolean inverse){
  if(!inverse&&f.selectedPlan!=null)return f.selectedPlan.effects().stream().filter(i->i.primitive().equals(WEATHER)).findFirst().orElseThrow();
  var a=inverse?new GlobalWeatherLease(dimension(),new ResourceId("proutlost_director:restore"),f.args.duration(),f.args.externalRevision()):f.args;
  return new EffectIntent(new OperationToken(UUID.randomUUID()),WEATHER,1,a,physicalFootprint(),new EventInstanceId(f.id));
 }
 private EffectPreparation capture(Flight f,boolean inverse){
  // Refresh may defer during pause; do not allocate a fresh-token restore pin first.
  var context=snapshot(f);
  var i=intent(f,inverse);if(inverse)weather.registerRestore(i,f.stopPlan);
  return weather.capture(i,context);
 }
 private void setPlan(Flight f,EffectPreparation captured){
  f.captured=captured;f.forward=captured.prepared().orElseThrow();f.capturedAt=nativeNow().value();
  var ref=f.forward.durableBlobs().stopPlan();f.stopPlan=captured.blobs().contents().stream().filter(b->b.reference().equals(ref)).findFirst().orElseThrow();
 }
 private DirectorFact dossier(Flight f){
  var values=new TreeMap<String,JsonValue>();values.putAll(Map.ofEntries(Map.entry("player",text(f.player)),Map.entry("disaster",text(f.disaster.value())),Map.entry("definition",text(f.definition.value())),
    Map.entry("plan",NativeDossierJson.encode(PLAN.encode(f.forward))),Map.entry("owner",NativeDossierJson.encode(OWNER.encode(f.initial))),Map.entry("site",NativeDossierJson.encode(FOOTPRINT.encode(f.site))),
    Map.entry("stop_codec",text(f.stopPlan.reference().codecId().value())),Map.entry("stop_version",text(f.stopPlan.reference().codecVersion())),
    Map.entry("stop_bytes",text(encode(f.stopPlan.bytes()))),Map.entry("captured_native_tick",text(f.capturedAt)),Map.entry("boot",text(f.boot)),Map.entry("tick",text(now().value())),
    Map.entry("weather_scope",text("GLOBAL_OVERWORLD_TEST_SITE_IS_NOT_PHYSICAL_BOUNDARY"))));
  if(f.selectedPlan!=null){values.put("selected_plan",NativeDossierJson.encode(new ModelCodec<>(ExecutionPlan.class).encode(f.selectedPlan)));values.put("selected_definition",NativeDossierJson.encode(new ModelCodec<>(EventDefinition.class).encode(f.selectedDefinition)));values.put("selected_decision",text(f.selectedDecision.value()));f.parentChain.ifPresent(id->values.put("parent_chain",text(id.value())));}
  return fact(START,f,values);
 }
 private EventDefinition definition(Flight f){return f.selectedDefinition!=null?f.selectedDefinition:f.catalog.events().get(f.definition);}
 private TargetSound wind(Flight f){return (TargetSound)definition(f).steps().stream().filter(s->s.args() instanceof TargetSound).findFirst().orElseThrow().args();}
 private Warning warning(Flight f){return (Warning)definition(f).steps().stream().filter(s->s.args() instanceof Warning).findFirst().orElseThrow().args();}
 public String begin(ServerPlayer player){requireLaboratory();
  actor();if(!scanned||failed||store.readOnlySafe()||flight!=null||!starts.isEmpty())return "STORM LAB attente/recuperation.";
  if(player.serverLevel()!=level()||!player.hasPermissions(4)||!player.isAlive())return "OP4 vivant dans overworld requis.";
  if(!level().getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE))return "doWeatherCycle=true requis; aucune regle modifiee.";
  var f=new Flight();f.id=UUID.randomUUID();f.disaster=new DisasterId(UUID.randomUUID());f.player=player.getUUID();f.boot=store.session().bootEpoch().value();
  f.args=new GlobalWeatherLease(dimension(),new ResourceId("minecraft:rain"),TTL,observer.revision(dimension()));
  // This call must load and validate JSON via the additive real J02 support, or throw before any WAL/world write.
  f.catalog=Objects.requireNonNull(validatedCatalog.apply(f.args),"J02 STORM loader missing");
  var defs=f.catalog.events().values().stream().filter(e->e.calibration()==Calibration.DEFAULT_FOR_TESTING
    &&e.steps().stream().anyMatch(s->s.primitive().equals(WEATHER)&&s.args().equals(f.args))).toList();
  if(defs.size()!=1)throw new IllegalArgumentException("one exact J02 LAB storm definition required");f.definition=defs.getFirst().id();
  warning(f);wind(f);f.site=site(player);f.audience=audience();
  if(f.audience.isEmpty()||f.audience.size()>4||!siteReady(f))throw new IllegalArgumentException("all one-to-four overworld clients must be at the actual test site");
  var old=owners.get(key());if(old!=null&&old.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE)throw new IllegalArgumentException("weather already leased");
  f.initial=new OwnershipRecord(new AggregateHeader(1,old==null?UUID.randomUUID():old.header().id(),old==null?new Revision(1):old.header().revision().plus(1),new Sequence(0),now()),
    key(),Set.of(new OwnerToken(UUID.randomUUID())),old==null?new Epoch(1):old.epoch().plus(1),OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),
    rawHash(),old==null?new Epoch(0):old.playerOverrideEpoch(),Optional.empty());
  try{setPlan(f,capture(f,false));}
  catch(IllegalStateException refused){if(String.valueOf(refused.getMessage()).contains("RAIN needs an observed"))return "RAIN LAB differee: vrai delai sans tonnerre/fade insuffisant; aucune meteo modifiee.";throw refused;}
  var mask=BlobContent.capture(new ResourceId("proutlost_director:storm_test_site_v1"),1,FOOTPRINT.encode(f.site));
  var unreadable=new WarningEvidence(f.audience,now(),LEAD,BlobContent.hash(new byte[0]),f.audienceRevision,false,false);
  f.state=new DisasterInstance(new AggregateHeader(1,f.disaster.value(),new Revision(1),new Sequence(0),now()),f.disaster,new EventInstanceId(f.id),f.definition,DisasterStage.WATCH,
    Map.of(new ResourceId("proutlost_director:manual_lab_request"),new UnitValue(1)),new UnitValue(1),f.site,unreadable,f.audience,List.of(),mask.reference(),Set.of(),"WATCH_GLOBAL_WEATHER_NO_EFFECT",Set.of());
  for(var recipient:f.audience)if(!fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,recipient,5))
   throw new SelectedEventStart.ContextUnavailable("warning tranche session capacity/handshake pending");
  buildMarkers(f);buildWinds(f);var start=dossier(f);f.tx=append(List.of(new DurableOwnership(f.initial),new DurableFact(start),new DurableAggregate(new DisasterAggregate(f.state))),List.of(mask));
  starts.put(f.id,start);flight=f;return "STORM LAB manuelle : vrais WARN20s, rafale sonore native recue, pluie physique20s sans tonnerre, cleanup J04. Risque naturel non revendique.";
 }

 public boolean selectedAvailable(){actor();return scanned&&!failed&&!store.readOnlySafe()&&flight==null&&starts.isEmpty();}
 public Optional<UUID> activeEvent(){actor();return flight==null?Optional.empty():Optional.of(flight.id);}
 public DirectorNativeCatalogueExecution.Preview preview(Candidate candidate,DecisionInput input,EventInstanceId event){
  actor();var definition=Objects.requireNonNull(input.catalog().events().get(candidate.definition().id()));
  if(definition.steps().size()!=3||!(definition.steps().get(0).args() instanceof Warning)||!(definition.steps().get(1).args() instanceof TargetSound)
    ||!(definition.steps().get(2).args() instanceof GlobalWeatherLease lease)||!lease.weather().value().equals("minecraft:rain")
    ||!lease.dimension().equals(dimension()))
   throw new IllegalArgumentException("exact installed WARN/wind/global-rain composition and live revision required");
  var ids=NativeCataloguePlanning.players(candidate);if(ids.isEmpty())throw new IllegalArgumentException("actual witness absent");
  var player=Objects.requireNonNull(server.getPlayerList().getPlayer(ids.stream().sorted().findFirst().orElseThrow().value()));
  var fp=site(player);var all=audience();if(!all.containsAll(ids)||all.size()>4)throw new IllegalArgumentException("bounded whole global audience");
  var b=fp.bounds().getFirst();
  for(var id:all){var p=Objects.requireNonNull(server.getPlayerList().getPlayer(id.value()));
   if(p.getX()<b.minX()||p.getX()>=b.maxXExclusive()||p.getZ()<b.minZ()||p.getZ()>=b.maxZExclusive()||Math.abs(p.getY()-b.minY())>4)
    throw new IllegalArgumentException("actual global WARN audience outside surveyed site");}
  var preparations=new ArrayList<EffectPreparation>();var raw=NativeCataloguePlanning.raw(input);var sampledRevision=observer.revision(dimension());
  for(var step:definition.steps()){
   var intent=NativeCataloguePlanning.intent(candidate,definition,step,event,step.args() instanceof GlobalWeatherLease?physicalFootprint():fp,input);
   if(step.args() instanceof Warning w)preparations.add(new NativeWarningBinding(server,new PlayerId(player.getUUID()),new PerceptionTransform(b.minX()+.25,b.minY()+.8,b.minZ()+.25,0,0,2),1200,w.profile(),this::now).capture(intent,raw));
   else if(step.args() instanceof TargetSound sound)preparations.add(new NativePerceptionBinding(server,new PlayerId(player.getUUID()),WIND,sound,new PerceptionTransform(player.getX(),player.getY()+1,player.getZ(),0,0,1),80).capture(intent,raw));
   else {
    var authored=(GlobalWeatherLease)intent.typedArgs();
    var sampled=new GlobalWeatherLease(authored.dimension(),authored.weather(),authored.duration(),sampledRevision);
    intent=new EffectIntent(intent.operationToken(),intent.primitive(),intent.version(),sampled,intent.expectedFootprint(),intent.instanceId());
    preparations.add(survey.surveyBirth(intent,raw));
   }
  }
  return NativeCataloguePlanning.captured(candidate,input,event,preparations,all);
 }
 private Flight selectedFlight(SelectedEventStart selected){
  actor();if(content==null||!content.production()||selected.mode()!=ProviderMode.PRODUCTION||!selected.definition().equals(content.definition(selected.definition().id()))||!selectedAvailable())
   throw new IllegalStateException("official selected global lane unavailable");
  var input=selected.refresh();var plan=selected.plan();var f=new Flight();f.id=selected.eventId().value();
  if(starts.containsKey(f.id)||ends.contains(f.id))throw new IllegalArgumentException("global event already consumed");
  f.selection=selected;f.selectedPlan=plan;f.selectedDefinition=selected.definition();f.selectedDecision=selected.proposal().proposalId();f.parentChain=selected.parentChain();
  f.definition=selected.definition().id();f.catalog=input.catalog();f.args=(GlobalWeatherLease)plan.effects().stream().filter(i->i.primitive().equals(WEATHER)).findFirst().orElseThrow().typedArgs();
  if(plan.effects().size()!=3)throw new IllegalArgumentException("GLOBAL_SELECTED_STEP_COUNT "+plan.effects().size());
  if(!f.args.weather().value().equals("minecraft:rain"))throw new IllegalArgumentException("GLOBAL_SELECTED_TYPE_REFUSED");
  if(warning(f).minimumLeadTicks().value()!=LEAD.value())throw new IllegalArgumentException("GLOBAL_SELECTED_WARN_LEAD_REFUSED");
  if(!f.args.dimension().equals(dimension()))throw new IllegalArgumentException("GLOBAL_SELECTED_DIMENSION_REFUSED");
  var actualRevision=observer.revision(dimension());if(!f.args.externalRevision().equals(actualRevision))throw new IllegalArgumentException("GLOBAL_STALE_EXTERNAL_REVISION expected="+f.args.externalRevision()+" actual="+actualRevision);
  if(!BudgetMath.fits(definition(f).budget(),SelectedEventAdmission.available(selected)))throw new IllegalArgumentException("GLOBAL_ACTIVE_PROFILE_BUDGET_REFUSED");
  f.player=selected.participants().stream().sorted().findFirst().orElseThrow().value();f.boot=store.session().bootEpoch().value();
  f.site=plan.effects().stream().filter(i->i.primitive().equals(WARNING)).findFirst().orElseThrow().expectedFootprint();f.audience=audience();
  var originalAudience=new HashSet<PlayerId>();
  selected.captured().context().context().players().forEach((id,observed)->{if(observed.position().position().dimension().equals(dimension()))originalAudience.add(id);});
  if(!originalAudience.equals(f.audience))throw new IllegalArgumentException("global audience changed after its actual J03 survey");
  if(f.audience.isEmpty()||f.audience.size()>4||!f.audience.containsAll(selected.participants())||!siteReady(f)||!selectedControl(f))
   throw new IllegalArgumentException("selected global actual audience/WORLD guard refused");
  if(!level().getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE))throw new IllegalArgumentException("actual weather cycle must remain enabled");
  var old=owners.get(key());if(old!=null&&old.disposition()!=OwnershipDisposition.RELEASED)throw new IllegalArgumentException("foreign/retained global weather owner");
  return f;
 }
 private DirectorNativeCatalogueExecution.GateResult selectedPreflight(SelectedEventStart selected){
  try{selectedFlight(selected);return new DirectorNativeCatalogueExecution.GateResult(DirectorNativeCatalogueExecution.GateStatus.READY,"GLOBAL_CURRENT_READS_ONLY_NO_APPLY_AUTHORITY");}
  catch(SelectedEventStart.ContextUnavailable unavailable){return new DirectorNativeCatalogueExecution.GateResult(DirectorNativeCatalogueExecution.GateStatus.WAITING,"GLOBAL_WAIT_CURRENT_CONTEXT "+unavailable.getMessage());}
  catch(IllegalArgumentException|IllegalStateException refused){return new DirectorNativeCatalogueExecution.GateResult(DirectorNativeCatalogueExecution.GateStatus.REJECTED,"GLOBAL_NO_NATIVE_CALL "+refused.getMessage());}
 }
 public String beginSelected(SelectedEventStart selected){
  var f=selectedFlight(selected);f.disaster=new DisasterId(UUID.randomUUID());var old=owners.get(key());
  f.initial=new OwnershipRecord(new AggregateHeader(1,old==null?UUID.randomUUID():old.header().id(),old==null?new Revision(1):old.header().revision().plus(1),new Sequence(0),now()),key(),Set.of(new OwnerToken(UUID.randomUUID())),old==null?new Epoch(1):old.epoch().plus(1),OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),rawHash(),old==null?new Epoch(0):old.playerOverrideEpoch(),Optional.empty());
  setPlan(f,capture(f,false));var mask=BlobContent.capture(new ResourceId("proutlost_director:storm_test_site_v1"),1,FOOTPRINT.encode(f.site));
  var unreadable=new WarningEvidence(f.audience,now(),LEAD,BlobContent.hash(new byte[0]),f.audienceRevision,false,false);
  f.state=new DisasterInstance(new AggregateHeader(1,f.disaster.value(),new Revision(1),new Sequence(0),now()),f.disaster,new EventInstanceId(f.id),f.definition,DisasterStage.WATCH,
    Map.of(),new UnitValue(1),f.site,unreadable,f.audience,List.of(),mask.reference(),Set.of(),"WATCH_SELECTED_GLOBAL_NO_EFFECT",Set.of());
  for(var recipient:f.audience)if(!fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,recipient,5))
   throw new SelectedEventStart.ContextUnavailable("actual global warning renderer capacity");
  buildMarkers(f);buildWinds(f);var start=dossier(f);f.tx=append(List.of(new DurableOwnership(f.initial),new DurableFact(start),new DurableAggregate(new DisasterAggregate(f.state))),List.of(mask));starts.put(f.id,start);flight=f;
  return "SELECTED_GLOBAL_WARN_WIND_RAIN_J04_QUEUED";
 }
 public Map<DefinitionId,DirectorNativeCatalogueExecution.Driver> drivers(Map<DefinitionId,DirectorNativeCatalogueExecution.Driver> existing){
  actor();var result=new LinkedHashMap<>(existing);
  content.catalog().events().forEach((id,definition)->{if(definition.steps().stream().anyMatch(s->s.args() instanceof GlobalWeatherLease)){
   result.put(id,new DirectorNativeCatalogueExecution.Driver(){
    public boolean available(){return selectedAvailable();}
    public DirectorNativeCatalogueExecution.Preview preview(Candidate c,DecisionInput i,EventInstanceId e){return DirectorNativeStormGameplay.this.preview(c,i,e);}
    public DirectorNativeCatalogueExecution.GateResult preflight(SelectedEventStart selected){return selectedPreflight(selected);}
    public DirectorNativeCatalogueExecution.StartResult begin(SelectedEventStart start){var text=beginSelected(start);return new DirectorNativeCatalogueExecution.StartResult(start.eventId(),activeEvent().filter(start.eventId().value()::equals).isPresent(),text);}
    public Optional<DirectorNativeCatalogueExecution.EventProgress> progress(EventInstanceId id){return activeEvent().filter(id.value()::equals).map(v->new DirectorNativeCatalogueExecution.EventProgress(id,failed?DirectorNativeCatalogueExecution.EventStage.RECOVERING:DirectorNativeCatalogueExecution.EventStage.RUNNING,status()));}
   });
  }});return Map.copyOf(result);
 }
 public boolean controlRecoveryReady(){actor();return scanned&&!failed&&!store.readOnlySafe();}
 public Optional<java.util.function.Supplier<AdminStatus>> requestStop(Optional<EventInstanceId> target){
  actor();var f=flight;if(f==null||target.filter(id->!id.value().equals(f.id)).isPresent())return Optional.empty();f.stop=true;var id=f.id;
  return Optional.of(()->ends.contains(id)?AdminStatus.SUCCEEDED:failed||f.phase==Phase.CONFLICT?AdminStatus.FAILED_SAFE:AdminStatus.PENDING);
 }
 private ResourceArbiter arbiter(){
  return new ResourceArbiter(world,new Epoch(Math.max(floor,store.session().bootEpoch().value())),1000,4,new ResourceArbiter.Compatibility(){
   public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}
  });
 }
 private Execution run(Flight f,EffectPreparation captured,ServerEffectPort.Binding binding,boolean impact){
  var e=new Execution();if(impact&&f.selection!=null)e.selectedInput=f.selection.refresh();e.plan=captured.prepared().orElseThrow();var p=e.plan;var i=p.intent();e.arbiter=arbiter();e.retain=impact;
  if(binding instanceof Guarded g){e.guard=g;e.safelyUninvoked=()->!g.invoked;}
  if(binding instanceof WindGuard g)e.safelyUninvoked=g.delegate::definitivelyNeverSent;
  if(binding instanceof NativeWarningBinding warning)e.safelyUninvoked=warning::definitivelyNeverSent;
  var scope=new ScopeRef(ScopeKind.WORLD,world.value());var epochs=new HashMap<ResourceKey,Epoch>();p.claims().forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
  if(impact){
   var def=definition(f);var roles=f.selectedPlan==null?new RoleAssignments(Map.of("witness",f.audience.stream().sorted(Comparator.comparing(id->id.value().toString())).toList())):f.selectedPlan.roles();
   var reference=new DefinitionRef(def.id(),def.contentRevision(),BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(def)));
   var intents=new ArrayList<EffectIntent>();f.markers.forEach(m->intents.add(m.intent));f.winds.forEach(w->intents.add(w.intent));intents.add(i);
   var scopes=new HashSet<ScopeRef>();scopes.add(scope);f.audience.forEach(id->scopes.add(new ScopeRef(ScopeKind.PLAYER,id.value())));
   var plan=new ExecutionPlan(f.selectedPlan==null?new CandidateId(UUID.randomUUID()):f.selectedPlan.candidateId(),reference,f.selectedPlan==null?intents:f.selectedPlan.effects(),roles,f.selectedPlan==null?new ScopeSet(world,scopes):f.selectedPlan.scopes(),p.preconditions());
   var request=new AdmissionRequest(f.selectedDecision==null?new DecisionId(UUID.randomUUID()):f.selectedDecision,p.preconditions(),p.claims(),roles,plan);
   var validator=new AdmissionService.HardValidator(){
    public ProviderMode mode(){return providerMode();}
    public AdmissionService.Assessment assess(AdmissionRequest proposed,Tick at){
     boolean valid=proposed.equals(request)&&contextSafe(f)&&phases.view(f.disaster).stage()==DisasterStage.IMPACT
      &&i.typedArgs().equals(f.args)&&binding.revalidate(p,captured.blobs());
     return new AdmissionService.Assessment(new ValidationResult(valid,valid?List.of():List.of(ReasonCode.STALE_CONTEXT),p.preconditions()),p.claims(),
      new ResourceArbiter.Rank(false,false,false,Priority.DANGER,0,1,42),Map.of(scope,def.budget()));
    }
   };
   e.admission=new AdmissionService(()->f.catalog,validator,e.arbiter,this::now,new DurationTicks(1600),4,providerMode());e.decision=request.proposalId();
   if((f.selection==null?e.admission.admit(request):f.selection.admit(e.admission,request)).status()!=AdmissionStatus.DEFERRED)throw new IllegalStateException("storm J03 queue refused");
   var answer=e.admission.resolveWindow(epochs,Map.of(scope,f.selection==null?CAP:SelectedEventAdmission.available(f.selection))).get(e.decision);
   if(answer==null||answer.status()!=AdmissionStatus.ACCEPTED)throw new IllegalStateException("storm J03 refused "+answer);e.token=answer.token().orElseThrow();
   System.out.println("DIRECTOR_STORM_J02_J03_MANUAL_ACCEPTED "+f.id+" automatic_risk=false");
  }else{
   var c=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),i.instanceId(),new ResourceArbiter.Rank(false,false,false,Priority.DANGER,0,1,42),
    now().plus(1600),p.claims(),Map.of(scope,p.budgetEstimate()));
   e.token=e.arbiter.arbitrate(List.of(c),now(),epochs,Map.of(scope,CAP)).getFirst().accepted().orElseThrow();
  }
  floor=Math.max(floor,e.token.generation().value());
  e.live=new LiveValidationSnapshot(p.preconditions(),i.expectedFootprint().loadedChunks(),HealthMode.NORMAL,now(),e.token.generation());
  var authority=new CommitPermit.Authority(store,4);
  var port=new ServerEffectPort(authority,x->{throw new IllegalStateException("already captured");},x->e.live,Map.of(i.primitive(),binding),binding.capabilities(),4,
   x->new StabilizationLiveSnapshot(store.session(),e.live,new OwnershipView(owners)),m->{},(m,r)->{});
  port.hydrate(p,captured.blobs());
  var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),i.operationToken(),i.expectedFootprint(),p.preconditions(),List.of(i),p.budgetEstimate());
  e.prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),i.operationToken(),e.token.generation(),p.preconditions(),e.token.expiryTick(),slice,e.token,
   captured.validation(),e.selectedInput==null?List.of():f.selection.reservation(e.token,now()),i.typedArgs() instanceof TargetSound?Set.of(NoninvertibleEffect.SOUND_EXPOSURE):Set.of(),List.of(),p.durableBlobs());
  e.tx=e.prepare.transactionId();prepares.put(i.operationToken(),e.prepare);e.runner=new EventRunner(authority,store,content==null?port:content.attach(p,captured.blobs(),port,f.selection!=null&&(impact||i.primitive().equals(WARNING)||i.primitive().equals(WIND))),e.arbiter,4);
  if(content!=null)content.effects().inspectReservations(new EventInstanceId(f.id),e.arbiter::snapshot);
  var batch=new DurableBatch(e.tx,store.session().storeGeneration(),e.prepare.readSet().revisions(),List.of(new DurablePrepare(e.prepare)),
   captured.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(e.prepare));
  e.runner.submit(batch,p.expectedPost(),e.live,actual->{
   e.actual=actual;var rows=new ArrayList<DurableRecord>();rows.add(new DurableResult(new ResultRecord(e.tx,i.operationToken(),actual,Set.of(),List.of(),List.of())));
   if(i.primitive().equals(WEATHER)){
    if(actual.outcome()!=Outcome.SUCCEEDED)throw new IllegalStateException("storm native weather outcome "+actual.outcome());
    if(impact){f.applied=true;f.deadline=Math.addExact(nativeNow().value(),f.args.duration().value());f.retiming=Math.subtractExact(nativeNow().value(),f.capturedAt);}
    else{f.inverseApplied=true;f.cleanup="EXACT_AGED_BASELINE_RESTORED";}
    f.mutation=mutation(f,e,impact?MutationState.VERIFIED:MutationState.ROLLED_BACK);rows.add(new DurableMutation(f.mutation));
    var values=new TreeMap<String,JsonValue>();values.put("direction",text(impact?"IMPACT":"INVERSE"));values.put("operation",text(i.operationToken().value()));
    values.put("result",text(encode(RESULT.encode(actual))));values.put("native_tick",text(nativeNow().value()));values.put("native_deadline",text(f.deadline));
    values.put("tick",text(now().value()));values.put("scope",text("GLOBAL_OVERWORLD"));values.put("natural_damage_reversed",new JsonValue.Bool(false));
    if(impact){values.put("observed_non_thunder_timer_delay_ticks",text(f.retiming));values.put("observed_thunder_counter_after",text(data().getThunderTime()));values.put("captured_native_tick",text(f.capturedAt));}
    var observed=fact(PHYSICS,f,values);f.consequences.add(observed);rows.add(new DurableFact(observed));
   }
   var ledgerBlobs=new ArrayList<BlobContent>();
   if(e.selectedInput!=null){var ledger=f.selection.effectContribution(e.prepare,actual,now());rows.addAll(ledger.records());ledgerBlobs.addAll(ledger.blobs());}
   return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,ledgerBlobs);
  });return e;
 }
 private MutationRecord mutation(Flight f,Execution e,MutationState state){
  if(f.mutation!=null){var old=f.mutation;var h=old.header();return new MutationRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),h.lastSequence(),now()),
   old.mutationId(),old.instanceId(),old.sliceId(),state,old.primitiveId(),old.primitiveVersion(),old.footprint(),old.preStateRef(),old.postStateRef(),old.ownerToken(),old.ownerEpoch(),
   old.readSetVersions(),old.noninvertibleEffects(),old.reconciliationEvidence(),old.compensationRef(),new Sequence(0));}
  var p=e.plan;var id=f.initial.creationMutationId();return new MutationRecord(new AggregateHeader(1,id.value(),new Revision(1),new Sequence(0),now()),id,new EventInstanceId(f.id),
   e.prepare.slice().sliceId(),state,WEATHER,1,physicalFootprint(),p.durableBlobs().preStates().get(key()),p.durableBlobs().expectedPostStates().get(key()),
   f.initial.ownerTokens().iterator().next(),f.initial.epoch(),p.preconditions(),Set.of(),List.of(),Optional.empty(),new Sequence(0));
 }
 private void release(Execution e){if(e==null||e.released)return;e.arbiter.release(e.token);if(e.admission!=null)e.admission.released(e.decision,e.token);e.released=true;}
 private boolean applied(Flight f,Execution e){
  if(e.done)return true;
  if(e.aborted){if(!durable(e.abort))return false;e.done=true;results.put(e.prepare.operationToken(),new ResultRecord(e.tx,e.prepare.operationToken(),e.actual,Set.of(),List.of(),List.of()));release(e);return true;}
  e.live=new LiveValidationSnapshot(e.live.readSet(),e.live.loadedChunks(),e.live.health(),now(),e.token.generation());
  var v=e.runner.poll(e.tx,e.live);
  if(v.stage()==EventRunner.Stage.REJECTED)throw new IllegalStateException("storm PREPARE rejected; existence not guessed "+v.failure());
  if(v.stage()!=EventRunner.Stage.PREPARING)durablePrepares.add(e.prepare.operationToken());
  if(v.stage()==EventRunner.Stage.RECOVERY_REQUIRED){
   if(e.safelyUninvoked!=null&&e.safelyUninvoked.getAsBoolean()||e.plan.intent().primitive().equals(WARNING)&&v.result().isEmpty()){
    var outcome=e.safelyUninvoked!=null?Outcome.ABORTED_SAFE:Outcome.DELIVERY_UNCERTAIN;
    System.out.println("DIRECTOR_STORM_TRANCHE_REFUSED event="+f.id+" phase="+f.phase+" failure="+v.failure());
    e.actual=new SliceResult(outcome,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
    e.abort=append(List.of(new DurableResult(new ResultRecord(e.tx,e.prepare.operationToken(),e.actual,Set.of(),List.of(),List.of()))));e.aborted=true;f.stop=true;return false;
   }throw new IllegalStateException("storm possible physical write requires recovery "+v.failure());
  }
  if(v.stage()!=EventRunner.Stage.COMMITTED)return false;
  e.done=true;results.put(e.prepare.operationToken(),new ResultRecord(e.tx,e.prepare.operationToken(),Objects.requireNonNull(e.actual),Set.of(),List.of(),List.of()));
  if(!e.retain)release(e);return true;
 }
 private void buildMarkers(Flight f){
  var b=f.site.bounds().getFirst();var warning=warning(f);
  for(var id:f.audience)for(var corner:List.of("NW","NE","SE","SW")){
   var m=new Marker();m.player=id;m.corner=corner;boolean east=corner.equals("NE")||corner.equals("SE"),south=corner.equals("SW")||corner.equals("SE");
   m.transform=new PerceptionTransform(east?b.maxXExclusive()-.25:b.minX()+.25,b.minY()+.8,south?b.maxZExclusive()-.25:b.minZ()+.25,0,0,2);
   m.binding=new NativeWarningBinding(server,id,m.transform,1200,warning.profile(),this::now);
   var operation=new OperationToken(UUID.randomUUID());
   if(f.selectedPlan!=null){var original=f.selectedPlan.effects().stream().filter(i->i.primitive().equals(WARNING)).findFirst().orElseThrow();operation=f.markers.isEmpty()?original.operationToken():new OperationToken(UUID.nameUUIDFromBytes(("selected-global-marker/"+original.operationToken().value()+"/"+id.value()+"/"+corner).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
   m.intent=new EffectIntent(operation,WARNING,1,warning,f.site,new EventInstanceId(f.id));f.markers.add(m);
  }f.markerDeadline=now().value()+1200;
 }
 private void buildWinds(Flight f){
  var sound=wind(f);
  for(var id:f.audience){
   var p=Objects.requireNonNull(server.getPlayerList().getPlayer(id.value()));var w=new Wind();w.player=id;
   w.binding=new NativePerceptionBinding(server,id,WIND,sound,new PerceptionTransform(p.getX(),p.getY()+1,p.getZ(),0,0,1),80);
   var operation=new OperationToken(UUID.randomUUID());
   if(f.selectedPlan!=null){var original=f.selectedPlan.effects().stream().filter(i->i.primitive().equals(WIND)).findFirst().orElseThrow();operation=f.winds.isEmpty()?original.operationToken():new OperationToken(UUID.nameUUIDFromBytes(("selected-global-wind/"+original.operationToken().value()+"/"+id.value()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
   w.intent=new EffectIntent(operation,WIND,1,sound,f.site,new EventInstanceId(f.id));f.winds.add(w);
  }
 }
 private List<NativeDisasterWarningProof.Marker> markers(Flight f){
  return f.markers.stream().map(m->new NativeDisasterWarningProof.Marker(m.player,m.corner,warning(f).profile(),m.transform,
   m.binding.emittedHeader().orElseThrow(),m.binding.sentAt().orElseThrow())).toList();
 }
 private boolean coreCommitted(Flight f){
  var t=phases.poll(f.disaster);if(t.status()==DurableDisasterRunner.Status.RECOVERY_REQUIRED)throw new IllegalStateException("storm phase uncertain");
  return t.status()==DurableDisasterRunner.Status.COMMITTED;
 }
 private DisasterRunner.Conditions conditions(Flight f,boolean safe,boolean complete,boolean stabilized){
  return new DisasterRunner.Conditions(now(),HealthMode.NORMAL,f.audience,f.audienceRevision,safe,complete,stabilized);
 }
 private void stabilize(Flight f){
  var stage=phases.view(f.disaster).stage();
  if(stage==DisasterStage.STABILIZE||stage==DisasterStage.WATCH){f.phase=Phase.CLEANUP;return;}
  if(stage==DisasterStage.RESOLVED||stage==DisasterStage.ABORTED_SAFE){end(f);return;}
  phases.propose(f.disaster,new TransactionId(UUID.randomUUID()),new UnitValue(1),conditions(f,false,true,false));f.phase=Phase.STABILIZE_ACK;
 }
 private void readMutation(Flight f,boolean clean){
  f.afterCleanup=clean;if(f.mutation==null){join(f);return;}
  f.mutationQuery=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.MUTATION,f.mutation.mutationId().value())),new Sequence(0),Optional.empty(),1);
  f.mutationRead=store.read(f.mutationQuery).toCompletableFuture();f.phase=Phase.MUTATION_READ;
 }
 private void finalizeMutation(Flight f){
  if(!f.mutationRead.isDone())return;var r=f.mutationRead.getNow(null);ReadContracts.validate(r,f.mutationQuery,store.session());
  if(r.status()!=ReadStatus.AVAILABLE||r.page().orElseThrow().hasMore()||r.page().orElseThrow().aggregates().size()!=1)throw new IllegalStateException("storm mutation commit sequence missing");
  var row=r.page().orElseThrow().aggregates().getFirst();if(!(row.state() instanceof MutationAggregate a))throw new IllegalStateException("storm foreign aggregate");
  var old=a.value();if(!old.instanceId().value().equals(f.id)||!old.mutationId().equals(f.initial.creationMutationId()))throw new IllegalStateException("storm mutation lineage");
  var h=old.header();f.mutation=new MutationRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),row.committedSequence(),now()),
   old.mutationId(),old.instanceId(),old.sliceId(),old.state()==MutationState.VERIFIED?MutationState.COMMITTED:old.state(),old.primitiveId(),old.primitiveVersion(),
   old.footprint(),old.preStateRef(),old.postStateRef(),old.ownerToken(),old.ownerEpoch(),old.readSetVersions(),old.noninvertibleEffects(),old.reconciliationEvidence(),old.compensationRef(),row.committedSequence());
  f.tx=append(List.of(new DurableMutation(f.mutation)));f.phase=Phase.MUTATION_ACK;
 }
 private void join(Flight f){
  phases.publishPhysicsReferences(f.disaster,new TransactionId(UUID.randomUUID()),f.mutation==null?List.of():List.of(f.mutation),f.consequences,
   f.afterCleanup?f.cleanup:"GLOBAL_RAIN_AND_REAL_WIND_PHYSICALLY_COMMITTED",now());f.phase=f.afterCleanup?Phase.CLEAN_JOIN_ACK:Phase.JOIN_ACK;
 }
 private boolean nativeExpired(Flight f){
  return nativeNow().value()>=f.deadline&&!data().isRaining()&&!data().isThundering();
 }
 /** Readback only. These are the provider's native POST codecs, never permission to write. */
 private boolean inversePostObserved(BlobRef post){
  if(post==null||post.codecVersion()!=1)return false;
  if(post.codecId().value().equals("proutlost_director:weather_state")||post.codecId().value().equals("proutlost_director:weathercore_global_state_v2"))return rawHash().equals(post.hash());
  if(!post.codecId().value().equals("proutlost_director:weather_deadlines_v1"))return false;
  var d=data();boolean clear=d.getClearWeatherTime()>0;
  if(clear&&(d.isRaining()||d.isThundering()||d.getRainTime()>1||d.getThunderTime()>1))return false;
  long at=nativeNow().value();
  var hash=BlobContent.hash(ByteBuffer.allocate(27).put((byte)(clear?1:0)).putLong(clear?Math.addExact(at,d.getClearWeatherTime()):0)
    .putLong(clear||d.getRainTime()==0?0:Math.addExact(at,d.getRainTime()))
    .putLong(clear||d.getThunderTime()==0?0:Math.addExact(at,d.getThunderTime()))
    .put((byte)(!clear&&d.isRaining()?1:0)).put((byte)(!clear&&d.isThundering()?1:0)).array());
  return hash.equals(post.hash());
 }
 private void cleanup(Flight f){
  if(!f.applied&&!f.physicalPrepared){f.cleanup="NO_WEATHER_IMPACT_WIND_NOT_REPLAYED_OR_REVERSED";releaseMetadata(f,false);return;}
  if(!f.initial.equals(owners.get(key()))){f.conflict=true;f.cleanup="OWNERSHIP_CONFLICT_UNRESOLVED";f.phase=Phase.CONFLICT;return;}
  if(weather.expiredByBackend(f.forward)){f.cleanup="NO_BASELINE_RESTORE_CORE_FINITE_EXPIRY_CONFIRMED";releaseMetadata(f,false);return;}
  if(!f.recovery&&!f.args.externalRevision().equals(observer.revision(dimension()))){
   f.cleanup="NO_BASELINE_RESTORE_EXTERNAL_OVERRIDE";releaseMetadata(f,true);return;
  }
  if(f.recovery){
   if(!level().getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE)||!observer.lastReason(dimension()).equals("BOOT_FENCE")){
    f.cleanup="NO_BASELINE_RESTORE_REBOOT_OBSERVED_EXTERNAL_CONTROL";releaseMetadata(f,true);return;
   }
   if(inversePostObserved(f.inversePost)){
    f.cleanup=f.inverseApplied?"EXACT_AGED_BASELINE_RESTORED_OBSERVED_AFTER_REBOOT":"BASELINE_OBSERVED_AFTER_REBOOT_DELIVERY_UNCERTAIN";releaseMetadata(f,false);return;
   }
  }
  if(!f.recovery){
   try{
    var inverse=capture(f,true);f.work=run(f,inverse,new Guarded(f,false),false);f.phase=Phase.INVERSE;return;
   }catch(IllegalStateException entropy){
    if(!String.valueOf(entropy.getMessage()).contains("counterfactual weather entropy boundary"))throw entropy;
    f.cleanup="NO_BASELINE_RESTORE_ENTROPY_BOUNDARY";
   }
  }else f.cleanup="NO_BASELINE_RESTORE_REBOOT_NO_REPLAY";
  // Do not invent a baseline or new clear-weather lease. Observe this finite native lease end.
  if(!nativeExpired(f))return;
  f.cleanup+="_NATIVE_EXPIRED";releaseMetadata(f,false);
 }
 private void releaseMetadata(Flight f,boolean yielded){
  var current=owners.get(key());
  if(!f.initial.equals(current)){
   if(current!=null&&Set.of(OwnershipDisposition.RELEASED,OwnershipDisposition.YIELDED_TO_PLAYER).contains(current.disposition())
      &&current.header().id().equals(f.initial.header().id())){f.next=current;}
   else throw new IllegalStateException("storm ownership CAS changed");
  }else{
   var h=current.header();f.next=new OwnershipRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),h.lastSequence(),now()),key(),
    current.ownerTokens(),yielded?current.epoch().plus(1):current.epoch(),yielded?OwnershipDisposition.YIELDED_TO_PLAYER:OwnershipDisposition.RELEASED,
    current.creationMutationId(),rawHash(),yielded?current.playerOverrideEpoch().plus(1):current.playerOverrideEpoch(),current.exceptionContractId());
  }
  var rows=new ArrayList<DurableRecord>();rows.add(new DurableOwnership(f.next));
  if(f.mutation!=null&&!yielded&&f.mutation.state()!=MutationState.ROLLED_BACK&&weather.expiredByBackend(f.forward)){
   // This is the real Core marker's exact-UUID finite-expiry/clear receipt.
   // An elapsed timer or foreign replacement alone never closes the mutation.
   f.mutation=mutation(f,new Execution(),MutationState.ROLLED_BACK);rows.add(new DurableMutation(f.mutation));
  }
  if(f.mutation!=null&&yielded){var e=new Execution();f.mutation=mutation(f,e,MutationState.YIELDED);rows.add(new DurableMutation(f.mutation));}
  var observed=fact(PHYSICS,f,Map.of("direction",text("CLEANUP_OBSERVED"),"mode",text(f.cleanup),"native_tick",text(nativeNow().value()),
    "native_deadline",text(f.deadline),"observed_raw_hash",text(rawHash().value()),"tick",text(now().value()),"baseline_restored",new JsonValue.Bool(f.inverseApplied),
    "non_thunder_timer_delay_ticks_unrestored",text(f.inverseApplied?0:f.retiming),
    "natural_damage_reversed",new JsonValue.Bool(false),"scope",text("GLOBAL_OVERWORLD")));
  f.consequences.add(observed);rows.add(new DurableFact(observed));f.tx=append(rows);f.phase=Phase.RELEASE_ACK;
 }
 private void end(Flight f){
  var rows=new ArrayList<DurableRecord>();var completionBlobs=new ArrayList<BlobContent>();
  if(f.selection!=null){
   var evidence=Map.<String,JsonValue>of("native_deadline",text(f.deadline),"cleanup",text(f.cleanup),"recovery_complete",new JsonValue.Bool(true),"weather_replayed",new JsonValue.Bool(false));
   if(WeatherEventCompletion.fullDuration(nativeNow().value(),f.deadline,f.applied,f.conflict,f.mutation)){var c=f.selection.completionContribution(evidence);rows.addAll(c.records());completionBlobs.addAll(c.blobs());}
   else rows.addAll(f.selection.abortRecords("NO_COMPLETE_GLOBAL_WEATHER_RESULT",evidence));
  }else if(f.selectedPlan!=null){
   var payload=new HashMap<String,JsonValue>();payload.put("definition",text(f.definition.value()));payload.put("decision",text(f.selectedDecision.value()));payload.put("source",text("CATALOGUE_SELECTED"));payload.put("cleanup",text(f.cleanup));payload.put("recovery_complete",new JsonValue.Bool(true));
   var success=WeatherEventCompletion.fullDuration(nativeNow().value(),f.deadline,f.applied,f.conflict,f.mutation);
   var type=success?f.selectedDefinition.completion().factType():new FactType("proutlost_director:catalogue_event_aborted");
   var id=new FactId(UUID.nameUUIDFromBytes(((success?"catalogue-completion-v1":"catalogue-aborted-v1")+"\0"+f.id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
   rows.add(new DurableFact(new DirectorFact(id,type,f.selectedPlan.scopes().scopes().stream().sorted(Comparator.comparing(ScopeRef::toString)).toList(),Optional.of(new EventInstanceId(f.id)),EvidenceStatus.CONFIRMED,payload,true,Optional.empty())));
  }
  for(var p:prepares.values())if(p.reservation().instanceId().value().equals(f.id)&&durablePrepares.contains(p.operationToken())&&!results.containsKey(p.operationToken()))
   rows.add(new DurableResult(new ResultRecord(p.transactionId(),p.operationToken(),new SliceResult(Outcome.DELIVERY_UNCERTAIN,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of()),Set.of(),List.of(),List.of())));
  rows.add(new DurableFact(fact(END,f,Map.of("disaster",text(f.disaster.value()),"stage",text(phases.view(f.disaster).stage()),"cleanup",text(f.cleanup),
    "weather_replayed",new JsonValue.Bool(false),"natural_damage_reversed",new JsonValue.Bool(false)))));
  f.tx=append(rows,completionBlobs);f.phase=Phase.END_ACK;
 }

 public String cancel(ServerPlayer player){actor();if(!player.hasPermissions(4))return "OP4 requis.";if(flight==null)return "Aucun STORM LAB actif.";flight.stop=true;return "STORM STABILIZE demande; aucune foudre ni meteo rejouee.";}
 public void poll(){
  actor();if(failed)return;
  try{
   observer.poll();if(store.readOnlySafe())throw new IllegalStateException("storm J04 unavailable; no emergency authority");
   if(!scanned){if(!shared)scan();return;}
   if(flight==null){recover();return;}var f=flight;
   switch(f.phase){
    case INITIAL->{
     if(!durable(f.tx))return;
     var current=owners.get(key());if(current!=null&&current.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE&&!current.equals(f.initial))throw new IllegalStateException("storm initial ownership changed");
     owners.put(key(),f.initial);phases.restore(f.state);
     if(f.stop||!loaded(f)||!siteReady(f)||!audience().equals(f.audience)||!sameLease(f)){f.phase=Phase.CLEANUP;return;}
     phases.propose(f.disaster,new TransactionId(UUID.randomUUID()),new UnitValue(1),conditions(f,true,false,false));f.phase=Phase.WARN_ACK;
    }
    case WARN_ACK->{
     if(!coreCommitted(f)||now().compareTo(phases.warnEnteredAt(f.disaster).orElseThrow())<=0)return;
     if(f.stop||!loaded(f)||!siteReady(f)||!audience().equals(f.audience)||!sameLease(f)){stabilize(f);return;}
     f.markerDeadline=now().value()+1200;f.phase=Phase.MARKERS;
    }
    case MARKERS->{
     if(f.markerIndex<f.markers.size()){
      var m=f.markers.get(f.markerIndex);
      if(m.work==null){
       if(f.stop||!loaded(f)||!siteReady(f)||!audience().equals(f.audience)||!sameLease(f)){stabilize(f);return;}
       EffectPreparation captured;try{captured=m.binding.capture(m.intent,snapshot(f));}catch(SelectedEventStart.ContextUnavailable waiting){return;}
       catch(RuntimeException stale){System.out.println("DIRECTOR_STORM_PREPARE_REFUSED event="+f.id+" phase="+f.phase+" cause="+stale);f.stop=true;stabilize(f);return;}
       m.work=run(f,captured,m.binding,false);return;
      }if(!applied(f,m.work))return;f.markerIndex++;return;
     }
     if(f.stop||!loaded(f)||!siteReady(f)||!audience().equals(f.audience)||!sameLease(f)||now().value()>=f.markerDeadline){stabilize(f);return;}
     if(!markersLive(f))return;
     var proof=NativeDisasterWarningProof.capture(server,phases.view(f.disaster),phases.warnEnteredAt(f.disaster).orElseThrow(),
      f.audience,f.audienceRevision,LEAD,now(),markers(f));
     phases.publishWarning(f.disaster,new TransactionId(UUID.randomUUID()),proof.evidence(),proof.proof(),now());f.phase=Phase.PROOF_ACK;
    }
    case PROOF_ACK->{if(coreCommitted(f))f.phase=Phase.LEAD;}
    case LEAD->{
     if(!contextSafe(f)){stabilize(f);return;}
     if(now().value()-phases.view(f.disaster).warningEvidence().deployedTick().value()<LEAD.value())return;
     try{setPlan(f,capture(f,false));}
     catch(IllegalStateException refused){if(String.valueOf(refused.getMessage()).contains("RAIN needs an observed")){f.stop=true;stabilize(f);return;}throw refused;}
     var sealed=dossier(f);f.tx=append(List.of(new DurableFact(sealed)));starts.put(f.id,sealed);f.phase=Phase.SEAL;
    }
    case SEAL->{
     if(!durable(f.tx))return;if(!contextSafe(f)){stabilize(f);return;}
     phases.propose(f.disaster,new TransactionId(UUID.randomUUID()),new UnitValue(1),conditions(f,true,false,false));f.phase=Phase.IMPACT_ACK;
    }
    case IMPACT_ACK->{
     if(!coreCommitted(f))return;if(!contextSafe(f)){stabilize(f);return;}
     f.phase=Phase.WIND;
    }
    case WIND->{
     if(f.windIndex<f.winds.size()){
      var w=f.winds.get(f.windIndex);
      if(w.work==null){
       if(!contextSafe(f)){stabilize(f);return;}
       EffectPreparation captured;
       try{captured=w.binding.capture(w.intent,snapshot(f));}
       catch(SelectedEventStart.ContextUnavailable waiting){return;}
       catch(RuntimeException stale){System.out.println("DIRECTOR_STORM_PREPARE_REFUSED event="+f.id+" phase="+f.phase+" cause="+stale);f.stop=true;stabilize(f);return;} // No PREPARE/send submitted.
       w.work=run(f,captured,new WindGuard(f,w.binding),false);w.receiptDeadline=now().value()+100;return;
      }
      if(!applied(f,w.work))return;if(w.work.aborted){stabilize(f);return;}
      if(w.binding.received()){f.windIndex++;return;}
      if(!w.binding.available()||now().value()>w.receiptDeadline){f.stop=true;stabilize(f);}return;
     }
     if(!contextSafe(f)){stabilize(f);return;}
     var received=fact(PHYSICS,f,Map.of("direction",text("WIND_RENDERER_RECEIPTS"),"sound",text(wind(f).sound().value()),
      "players",new JsonValue.Array(f.winds.stream().map(w->(JsonValue)text(w.player.value())).toList()),
      "exposures_per_client",text(1),"tick",text(now().value())));
     f.consequences.add(received);f.tx=append(List.of(new DurableFact(received)));f.phase=Phase.WIND_ACK;
    }
    case WIND_ACK->{
     if(!durable(f.tx))return;if(!contextSafe(f)||!weather.revalidate(f.forward,f.captured.blobs())){stabilize(f);return;}
     // WARN and WIND span actor/store cuts. Refresh before PREPARE while pinning
     // the original intent, baseline, poststate and owner capsule exactly.
     var refreshed=capture(f,false);var freshPlan=refreshed.prepared().orElseThrow();
     if(!freshPlan.intent().equals(f.forward.intent())
         ||!freshPlan.durableBlobs().snapshots().equals(f.forward.durableBlobs().snapshots())
         ||!freshPlan.durableBlobs().preStates().equals(f.forward.durableBlobs().preStates())
         ||!freshPlan.durableBlobs().stopPlan().equals(f.forward.durableBlobs().stopPlan())
         ||!freshPlan.expectedPost().equals(f.forward.expectedPost())
         ||!freshPlan.preconditions().epochs().equals(f.forward.preconditions().epochs())){
      f.stop=true;stabilize(f);return;
     }
     var before=f.forward.preconditions().revisions();
     System.out.println("DIRECTOR_STORM_PREPARE_CURRENT_CUT event="+f.id+" previous="+before+" current="+freshPlan.preconditions().revisions()+" physical_identity_unchanged=true");
     f.work=run(f,refreshed,new Guarded(f,true),true);f.impact=f.work;f.physicalPrepared=true;f.phase=Phase.IMPACT;
    }
    case IMPACT->{
     if(!applied(f,f.work))return;
     if(f.work.aborted){f.physicalPrepared=false;stabilize(f);return;}
     if(!f.applied||f.deadline<0)throw new IllegalStateException("storm actual physical tick missing");
     readMutation(f,false);System.out.println("DIRECTOR_STORM_REAL_GLOBAL_IMPACT_COMMITTED "+f.id+" native_deadline="+f.deadline);
    }
    case MUTATION_READ->finalizeMutation(f);
    case MUTATION_ACK->{if(durable(f.tx))join(f);}
    case JOIN_ACK->{if(coreCommitted(f))f.phase=Phase.HOLD;}
    case HOLD->{if(!contextSafe(f)||nativeNow().value()>=f.deadline)stabilize(f);}
    case STABILIZE_ACK->{if(coreCommitted(f))f.phase=Phase.CLEANUP;}
    case CLEANUP->cleanup(f);
    case INVERSE->{
     if(!applied(f,f.work))return;
     if(f.work.aborted){f.phase=Phase.CLEANUP;return;}
     if(!f.inverseApplied)throw new IllegalStateException("storm inverse RESULT missing");releaseMetadata(f,false);
    }
    case RELEASE_ACK->{
     if(!durable(f.tx))return;
     var current=owners.get(key());if(Objects.equals(current,f.initial))owners.put(key(),f.next);
     else if(!Objects.equals(current,f.next))throw new IllegalStateException("storm release ownership CAS changed");
     journalOwners.put(key(),f.next);readMutation(f,true);
    }
    case CLEAN_JOIN_ACK->{
     if(!coreCommitted(f))return;if(f.conflict){f.phase=Phase.CONFLICT;return;}
     var stage=phases.view(f.disaster).stage();
     if(stage==DisasterStage.WATCH||stage==DisasterStage.WARN)phases.abortNoImpact(f.disaster,new TransactionId(UUID.randomUUID()),"NO_IMPACT_WEATHER_LEASE_RELEASED",now());
     else if(stage==DisasterStage.STABILIZE)phases.propose(f.disaster,new TransactionId(UUID.randomUUID()),new UnitValue(1),conditions(f,false,true,true));
     else if(stage==DisasterStage.RESOLVED||stage==DisasterStage.ABORTED_SAFE){end(f);return;}
     else throw new IllegalStateException("storm cleanup before durable STABILIZE");
     f.phase=Phase.RESOLVE_ACK;
    }
    case RESOLVE_ACK->{if(coreCommitted(f))end(f);}
    case END_ACK->{
     if(!durable(f.tx))return;for(var m:f.markers){m.binding.stop();release(m.work);}for(var w:f.winds){w.binding.stop();release(w.work);}release(f.impact);release(f.work);
     phases.retire(f.disaster);starts.remove(f.id);ends.add(f.id);prepares.values().removeIf(p->p.reservation().instanceId().value().equals(f.id));
     weather.releaseReceipt(f.forward);
     System.out.println("DIRECTOR_STORM_RESOLVED_DURABLE "+f.id+" cleanup="+f.cleanup+" native_damage_reversed=false");flight=null;
    }
    case CONFLICT->{/* Keep real conflicting ownership visible; no overwrite, no false RESOLVED. */}
   }
  }catch(SelectedEventStart.ContextUnavailable waiting){return;}catch(RuntimeException x){failed=true;System.out.println("DIRECTOR_STORM_RECOVERY_REQUIRED "+x);x.printStackTrace();}
 }
 private void scan(){
  if(scanning==null){scanning=store.read(new StoreQuery(world,Set.of(),new Sequence(0),cursor,128)).toCompletableFuture();return;}
  if(!scanning.isDone())return;var r=scanning.getNow(null);scanning=null;
  if(r==null||r.status()==ReadStatus.STALE){resetRecoveryScan();return;}
  if(r.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("storm recovery scan "+r.status());
  var page=r.page().orElseThrow();acceptRecoveryRows(page.records(),!page.hasMore());cursor=page.nextCursor();
 }
 public void useSharedRecoveryScan(){actor();shared=true;resetRecoveryScan();}
 public void resetRecoveryScan(){
  actor();if(flight!=null)throw new IllegalStateException("cannot reset active storm recovery");
  scanned=false;scanning=null;cursor=Optional.empty();starts.clear();ends.clear();disasters.clear();warnEntries.clear();journalOwners.clear();journalMutations.clear();
  prepares.clear();results.clear();facts.clear();durablePrepares.clear();floor=0;clockFloor=0;offset=0;
 }
 public void acceptRecoveryRows(List<DurableRecord> rows,boolean last){
  actor();
  for(var row:rows){
   if(row instanceof DurableAggregate a){
    clockFloor=Math.max(clockFloor,a.state().header().updatedTick().value());
    if(a.state() instanceof DisasterAggregate d){var old=disasters.get(d.value().disasterId());if(old==null||old.header().revision().compareTo(d.header().revision())<0)disasters.put(d.value().disasterId(),d.value());}
    if(a.state() instanceof MutationAggregate m)journalMutations.put(m.value().mutationId(),m.value());
   }
   if(row instanceof DurableMutation m){journalMutations.put(m.value().mutationId(),m.value());clockFloor=Math.max(clockFloor,m.value().header().updatedTick().value());}
   if(row instanceof DurableOwnership o){journalOwners.put(o.value().resourceKey(),o.value());clockFloor=Math.max(clockFloor,o.value().header().updatedTick().value());}
   if(row instanceof DurablePrepare d){floor=Math.max(floor,d.value().reservationGeneration().value());prepares.put(d.value().operationToken(),d.value());durablePrepares.add(d.value().operationToken());}
   if(row instanceof DurableResult r)results.put(r.value().operationToken(),r.value());
   if(row instanceof DurableFact d&&d.value().eventId().isPresent()){
    var f=d.value();var id=f.eventId().orElseThrow().value();
    if(f.factType().equals(START))starts.put(id,f);else if(f.factType().equals(END))ends.add(id);
    else if(f.factType().equals(PHYSICS))facts.computeIfAbsent(id,k->new ArrayList<>()).add(f);
    else if(f.factType().equals(DurableDisasterRunner.WARN_ENTRY))warnEntries.put(new DisasterId(UUID.fromString(text(f,"disaster_id"))),new Tick(Long.parseLong(text(f,"entered_tick"))));
    if(Set.of(START,PHYSICS).contains(f.factType())&&f.payload().get("tick") instanceof JsonValue.Text t)clockFloor=Math.max(clockFloor,Long.parseLong(t.value()));
   }
  }
  if(last){
   for(var id:ends)if(prepares.values().stream().noneMatch(p->p.reservation().instanceId().value().equals(id)&&!results.containsKey(p.operationToken())))starts.remove(id);
   prepares.values().removeIf(p->!starts.containsKey(p.reservation().instanceId().value()));durablePrepares.retainAll(prepares.keySet());results.keySet().retainAll(prepares.keySet());
   journalMutations.values().removeIf(m->!starts.containsKey(m.instanceId().value()));facts.keySet().retainAll(starts.keySet());
   offset=Math.max(0,clockFloor-server.overworld().getGameTime());scanned=true;System.out.println("DIRECTOR_STORM_RECOVERY_SCAN pending="+starts.size());
  }
 }
 private void recover(){
  if(starts.isEmpty())return;var entry=starts.entrySet().iterator().next();var saved=entry.getValue();var f=new Flight();
  f.id=entry.getKey();f.player=UUID.fromString(text(saved,"player"));f.disaster=new DisasterId(UUID.fromString(text(saved,"disaster")));f.definition=new DefinitionId(text(saved,"definition"));
  f.forward=PLAN.decode(decode(saved,"plan"));f.args=(GlobalWeatherLease)f.forward.intent().typedArgs();f.initial=OWNER.decode(decode(saved,"owner"));
  if(saved.payload().containsKey("selected_plan")){
   f.selectedPlan=new ModelCodec<>(ExecutionPlan.class).decode(decode(saved,"selected_plan"));f.selectedDefinition=new ModelCodec<>(EventDefinition.class).decode(decode(saved,"selected_definition"));f.selectedDecision=new DecisionId(UUID.fromString(text(saved,"selected_decision")));
   if(saved.payload().get("parent_chain") instanceof JsonValue.Text t)f.parentChain=Optional.of(new ChainId(UUID.fromString(t.value())));
   if(!f.selectedPlan.scopes().worldId().equals(world)||!f.selectedPlan.definition().id().equals(f.definition)||!f.selectedPlan.effects().contains(f.forward.intent())||f.selectedDefinition.calibration()!=Calibration.APPROVED)
    throw new IllegalStateException("cold selected global lineage");
  }
  if(content!=null&&content.production()&&f.selectedPlan==null)throw new IllegalStateException("historical laboratory global owner cannot become production");
  f.site=FOOTPRINT.decode(decode(saved,"site"));f.boot=Long.parseLong(text(saved,"boot"));if(saved.payload().get("captured_native_tick") instanceof JsonValue.Text c)f.capturedAt=Long.parseLong(c.value());
  f.stopPlan=BlobContent.capture(new ResourceId(text(saved,"stop_codec")),Integer.parseInt(text(saved,"stop_version")),decode(saved,"stop_bytes"));
  if(!f.forward.intent().instanceId().value().equals(f.id)||!f.initial.resourceKey().equals(key())||!f.args.dimension().equals(dimension())
    ||!f.stopPlan.reference().equals(f.forward.durableBlobs().stopPlan()))throw new IllegalStateException("storm dossier lineage");
  f.state=Objects.requireNonNull(disasters.get(f.disaster),"storm aggregate missing");
  if(!f.state.eventInstanceId().value().equals(f.id)||!f.state.recipeId().equals(f.definition)||!f.state.envelope().equals(f.site))throw new IllegalStateException("storm aggregate/dossier mismatch");
  f.audience=f.state.affectedAudience();f.audienceRevision=f.state.warningEvidence().audienceRevision();f.recovery=true;f.stop=true;
  f.mutation=journalMutations.get(f.initial.creationMutationId());f.consequences.addAll(facts.getOrDefault(f.id,List.of()));
  boolean unknown=false;
  for(var p:prepares.values())if(p.reservation().instanceId().value().equals(f.id)&&p.slice().operations().stream().anyMatch(i->i.primitive().equals(WEATHER))){
   f.physicalPrepared=true;if(!results.containsKey(p.operationToken()))unknown=true;
   if(p.slice().operations().stream().anyMatch(i->i.typedArgs() instanceof GlobalWeatherLease a&&a.weather().value().equals("proutlost_director:restore")))
    f.inversePost=p.durableBlobs().expectedPostStates().get(key());
  }
  for(var fact:f.consequences)if(fact.payload().get("direction") instanceof JsonValue.Text t){
   if(t.value().equals("IMPACT")){f.applied=true;f.deadline=Math.max(f.deadline,Long.parseLong(text(fact,"native_deadline")));if(fact.payload().get("observed_non_thunder_timer_delay_ticks") instanceof JsonValue.Text d)f.retiming=Long.parseLong(d.value());}
   else if(t.value().equals("INVERSE")){f.inverseApplied=true;f.cleanup="EXACT_AGED_BASELINE_RESTORED";}
   else if(t.value().equals("CLEANUP_OBSERVED")){f.cleanup=text(fact,"mode");
    if(fact.payload().get("baseline_restored") instanceof JsonValue.Bool restored)f.inverseApplied=restored.value();
   }
  }
  // An unmatched physical PREPARE may have applied. Observe a fresh maximum native TTL, never infer success or replay.
  if(unknown||f.deadline<0&&f.physicalPrepared)f.deadline=Math.max(f.deadline,Math.addExact(nativeNow().value(),f.args.duration().value()+1));
  var persisted=Objects.requireNonNull(journalOwners.get(key()),"storm durable weather owner missing");
  if(!persisted.header().id().equals(f.initial.header().id())||!persisted.ownerTokens().equals(f.initial.ownerTokens())
    ||!persisted.creationMutationId().equals(f.initial.creationMutationId()))throw new IllegalStateException("storm recovered owner lineage conflict");
  var live=owners.get(key());if(live!=null&&!live.equals(persisted)&&!live.equals(f.initial))throw new IllegalStateException("storm recovered weather owner conflict");
  owners.put(key(),persisted);
  var warn=warnEntries.get(f.disaster);if(warn==null)phases.restore(f.state);else phases.restore(f.state,warn);flight=f;
  if(Set.of(OwnershipDisposition.RELEASED,OwnershipDisposition.YIELDED_TO_PLAYER).contains(persisted.disposition())){
   f.next=persisted;if(f.cleanup.equals("NO_IMPACT"))f.cleanup="NO_BASELINE_RESTORE_ALREADY_RELEASED_BEFORE_REBOOT";readMutation(f,true);
  }else if(persisted.equals(f.initial))stabilize(f);
  else{f.cleanup="OWNERSHIP_CONFLICT_UNRESOLVED";f.phase=Phase.CONFLICT;}
  System.out.println("DIRECTOR_STORM_RECOVERY_NO_REPLAY "+f.id+" unresolved_physical="+unknown);
 }
 public boolean recovering(){actor();return !scanned&&!failed;}
 public boolean busy(){actor();return !scanned||failed||flight!=null||!starts.isEmpty();}
 public String status(){actor();return failed?"STORM_RAIN_RECOVERY_REQUIRED":!scanned?"STORM_RAIN_RECOVERING":flight==null?"STORM_RAIN_WIND_IDLE":"STORM_RAIN_WIND "+flight.phase+" "+flight.id+" "+flight.cleanup;}
 @Override public void close(){actor();if(flight!=null){for(var m:flight.markers)m.binding.stop();for(var w:flight.winds)w.binding.stop();}closed=true;} // Observer is owned by parent bootstrap.
}
