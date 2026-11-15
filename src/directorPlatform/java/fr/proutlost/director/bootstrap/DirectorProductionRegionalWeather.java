package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.effects.weathercore.*;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.model.RegionalWeatherJournal.Request;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Official composition: existing J04, actual REGION/profile heads, the global
 * event arbiter and the installed WeatherCore adapter. No LAB permissions,
 * replacement narrative, private production arbiter or independent store. */
public final class DirectorProductionRegionalWeather implements DirectorRegionalWeatherRuntime.AdmissionGuard,AutoCloseable {
 private final MinecraftServer server;private final JournalStore store;private final StoreSession session;
 private final DirectorProductionRuntime production;private final DirectorNativeGameplay gameplay;private final DirectorRuntimeContent content;
 private final DirectorRegionalWeatherRuntime runtime;private final WeatherCoreRegionalBinding survey;
 private final Map<EventInstanceId,Selected> selected=new LinkedHashMap<>();private boolean stopping,closed;private Object metadataOwner;
 private static final class Selected {SelectedEventStart start;Request request;PrepareRecord birthPrepare;CompletableFuture<CommitAck> ack;DurableBatch batch;boolean lane,completed,success;
  Set<PlayerId> originalAudience=Set.of();boolean submitted;EventInstance sealedEvent;CompletableFuture<BlobReadResult> context;NativeCatalogueColdCompletion.Identity cold;NativeCatalogueColdCompletion.Read coldRead;
 }
 private record Frame(DirectorWorldState world,EventInstance event,RegionRecord region,ConfigView profile,Sequence cut){}
 public DirectorProductionRegionalWeather(MinecraftServer server,JournalStore store,DirectorProductionRuntime production,DirectorNativeGameplay gameplay,DirectorRuntimeContent content){
  this.server=Objects.requireNonNull(server);this.store=Objects.requireNonNull(store);this.production=Objects.requireNonNull(production);this.gameplay=Objects.requireNonNull(gameplay);this.content=Objects.requireNonNull(content);session=store.session();actor();
  if(!content.production())throw new IllegalArgumentException("official production composition required");
  runtime=new DirectorRegionalWeatherRuntime(server,session.worldId(),store,this,4);
  survey=WeatherCoreIntegration.createRegionalBinding(this::level,gameplay::physicalOwnershipSnapshot,k->{throw new IllegalStateException("survey grants no apply revision");},4);
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("production regional actor");}
 private static void require(boolean yes,String why){if(!yes)throw new IllegalArgumentException(why);}
 private ServerLevel level(DimensionId id){actor();for(var level:server.getAllLevels())if(level.dimension().location().toString().equals(id.value()))return level;throw new IllegalArgumentException("regional dimension unavailable");}
 private static boolean terminal(EventState state){return Set.of(EventState.SUCCEEDED,EventState.PARTIAL,EventState.ABORTED_SAFE,EventState.FAILED_SAFE).contains(state);}
 private record Heads(List<AggregateState> states,Sequence cut){}
 private List<AggregateState> heads(Set<AggregateKey> keys){return readHeads(keys).states();}
 private Heads readHeads(Set<AggregateKey> keys){
  var q=new StoreQuery(session.worldId(),keys,new Sequence(0),Optional.empty(),Math.max(1,keys.size()));var r=store.view(q);ReadContracts.validate(r,q,session);
  if(!session.equals(store.session())||store.readOnlySafe()||r.status()!=ReadStatus.AVAILABLE||r.page().orElseThrow().hasMore())throw new SelectedEventStart.ContextUnavailable("current regional J04 heads unavailable");
  return new Heads(r.page().orElseThrow().aggregates().stream().map(RestoredAggregate::state).toList(),r.page().orElseThrow().lastDurableSequence());
 }
 private EventInstance event(EventInstanceId event){return heads(Set.of(new AggregateKey(AggregateKind.EVENT,event.value()))).stream().filter(v->v instanceof EventAggregate).map(v->((EventAggregate)v).value()).findFirst().orElseThrow(()->new SelectedEventStart.ContextUnavailable("real selected EVENT unavailable"));}
 private Frame frame(Request request){
  actor();var e=event(request.event());require(e.regionIds().size()==1,"one actual REGION required for regional atmosphere");var region=e.regionIds().iterator().next();
  var keys=Set.of(new AggregateKey(AggregateKind.WORLD,session.worldId().value()),new AggregateKey(AggregateKind.EVENT,e.instanceId().value()),new AggregateKey(AggregateKind.REGION,region.value()));
  var page=readHeads(keys);var rows=page.states();require(rows.size()==3,"complete current weather REGION/EVENT/WORLD required");
  var w=rows.stream().filter(v->v instanceof WorldAggregate).map(v->((WorldAggregate)v).value()).findFirst().orElseThrow();
  var r=rows.stream().filter(v->v instanceof RegionAggregate).map(v->((RegionAggregate)v).value()).findFirst().orElseThrow();
  var current=rows.stream().filter(v->v instanceof EventAggregate).map(v->((EventAggregate)v).value()).findFirst().orElseThrow();
  require(e.equals(current)&&w.worldId().equals(session.worldId())&&w.bootEpoch().equals(session.bootEpoch())&&w.storeGeneration().equals(session.storeGeneration()),"regional actual session/cut changed");
  var profile=AdminWorldControlHandler.configuration(w,content.profiles());require(profile.calibration()==Calibration.APPROVED&&profile.provenanceHash().equals(w.configHash()),"actual approved active profile required");
  var index=store.residentAggregateKeys(Set.of(AggregateKind.WORLD),1).orElseThrow();require(index.cut().equals(page.cut()),"regional head cut advanced");return new Frame(w,current,r,profile,page.cut());
 }
 private EventDefinition definition(Frame frame){
  var value=Objects.requireNonNull(content.catalog().events().get(frame.event().definition().id()),"original production weather definition missing");
  require(frame.event().definition().equals(new DefinitionRef(value.id(),value.contentRevision(),BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(value))))&&content.catalog().hash().equals(frame.world().catalogHash()),"weather actual definition/catalogue hash differs");return value;
 }
 private Fingerprint authorization(Request request,Frame frame){
  var fields=new TreeMap<String,JsonValue>();fields.put("world",new JsonValue.Text(frame.world().worldId().value().toString()));
  fields.put("profile",new JsonValue.Text(frame.world().intensityProfileId().value()));fields.put("config",new JsonValue.Text(frame.world().configHash().value()));fields.put("catalog",new JsonValue.Text(frame.world().catalogHash().value()));
  fields.put("definition",CanonicalJsonV1.decode(new ModelCodec<>(DefinitionRef.class).encode(frame.event().definition())));fields.put("region",new JsonValue.Text(frame.region().regionId().value().toString()));
  fields.put("physical_region",new JsonValue.Text(RegionalWeatherScopeIdentity.fingerprint(frame.region()).value()));fields.put("event",new JsonValue.Text(request.event().value().toString()));fields.put("operation",new JsonValue.Text(request.operation().value().toString()));
  fields.put("footprint",CanonicalJsonV1.decode(new ModelCodec<>(Footprint.class).encode(request.footprint())));fields.put("duration",new JsonValue.Text(Long.toString(request.duration().value())));
  return BlobContent.hash(CanonicalJsonV1.encode(new JsonValue.ObjectValue(fields)));
 }
 private final Map<EventInstanceId,String> guardRefusals=new LinkedHashMap<>();
 private boolean refused(Request request,String reason){
  var old=guardRefusals.put(request.event(),reason);if(!Objects.equals(old,reason))System.out.println("DIRECTOR_PRODUCTION_REGIONAL_GUARD_REFUSED event="+request.event().value()+" reason="+reason);
  while(guardRefusals.size()>128)guardRefusals.remove(guardRefusals.keySet().iterator().next());return false;
 }
 private boolean polling;private DecisionInput cachedInput;private Sequence cachedInputCut;private Object cachedInputOwner;private long cachedInputTick=-1;
 private DecisionInput currentInput(){
  var index=store.residentAggregateKeys(Set.of(AggregateKind.WORLD),1).orElseThrow(()->new SelectedEventStart.ContextUnavailable("regional actual context index absent"));
  if(!index.session().equals(session))throw new SelectedEventStart.ContextUnavailable("regional input session revoked");
  var at=clock().value();
  if(polling&&cachedInput!=null&&cachedInputOwner==metadataOwner&&cachedInputTick==at&&index.cut().equals(cachedInputCut))return cachedInput;
  var actual=gameplay.coordinator().metadataReadbackInput(metadataOwner).orElseThrow(()->new SelectedEventStart.ContextUnavailable("fresh regional one-cut context unavailable"));
  var after=store.residentAggregateKeys(Set.of(AggregateKind.WORLD),1).orElseThrow();
  if(!after.session().equals(session)||!after.cut().equals(index.cut()))throw new SelectedEventStart.ContextUnavailable("regional context cut advanced during read");
  if(polling){cachedInput=actual;cachedInputCut=after.cut();cachedInputOwner=metadataOwner;cachedInputTick=at;}return actual;
 }
 private boolean approved(Request request,boolean resumed){return approved(request,resumed,false);}
 private boolean approved(Request request,boolean resumed,boolean awaitResume){
  actor();try{
   if(stopping||!(production.phase()==DirectorProductionRuntime.Phase.RUNNING||awaitResume&&production.phase()==DirectorProductionRuntime.Phase.PAUSED)||!session.equals(store.session())||store.readOnlySafe())return refused(request,"ROOT_SESSION_OR_PHASE");var f=frame(request);var definition=definition(f);
   if(terminal(f.event().state())||!(f.world().runControl()==RunControl.RUNNING||awaitResume&&f.world().runControl()==RunControl.PAUSED)||f.world().healthMode()!=HealthMode.NORMAL||f.region().status()!=RegionStatus.ACTIVE||!f.region().dimension().equals(request.footprint().dimension()))return refused(request,"WORLD_REGION_HEALTH");
   if(definition.steps().size()!=1||!(definition.steps().getFirst().args() instanceof RegionalWeatherLease args)||!args.profile().equals(WeatherCoreRegionalBinding.PROFILE)
      ||!args.dimension().equals(request.footprint().dimension())||!args.duration().equals(request.duration())||!RegionalWeatherScopeIdentity.matches(f.region(),args)
      ||!DirectorNativeCatalogueExecution.operation(request.event(),definition.steps().getFirst().id()).equals(request.operation())||!authorization(request,f).equals(request.authorization()))return refused(request,"ORIGINAL_DEFINITION_OR_AUTHORIZATION");
   if(request.duration().value()>f.profile().values().get("event.mist.duration_ticks").value().longValueExact()
      ||definition.budget().regionalIntensity()<=0||definition.budget().regionalIntensity()>f.profile().values().get("budget.region_intensity").value().doubleValue())return refused(request,"ACTIVE_PROFILE_BOUND");
   var descriptor=content.catalog().primitives().get(WeatherCoreRegionalBinding.PRIMITIVE);
   if(descriptor==null||descriptor.mode()!=ProviderMode.PRODUCTION||!descriptor.capabilities().contains(WeatherCoreRegionalBinding.CAPABILITY))return refused(request,"ACTUAL_CAPABILITY");
   if(awaitResume){if(f.world().runControl()!=RunControl.PAUSED||production.phase()!=DirectorProductionRuntime.Phase.PAUSED)return refused(request,"AWAIT_EXPLICIT_RESUME");}
   else{
    var pinned=selected.get(request.event());if(pinned==null||!coldIdentity(pinned))throw new SelectedEventStart.ContextUnavailable("original regional selection still hydrating");
    var plan=pinned.start!=null?pinned.start.plan():pinned.cold.plan();
    if(pinned.start!=null&&runtime.receipt(request.event()).filter(r->!r.nativeBirthConfirmed()).isPresent()
      &&Math.addExact(clock().value(),request.duration().value())>Math.addExact(pinned.start.captured().context().capturedTick().value(),pinned.start.definition().timing().maxDurationTicks().value()))return refused(request,"ORIGINAL_LIFETIME_NO_LONGER_FITS");

    var actual=currentInput();
    Optional<UUID> group=Optional.empty();var participants=new HashSet<PlayerId>();plan.roles().roles().values().forEach(participants::addAll);
    var affected=actualAudience(request.footprint());var pinnedAudience=new HashSet<PlayerId>(pinned.originalAudience);pinnedAudience.addAll(participants);
    plan.scopes().scopes().stream().filter(s->s.kind()==ScopeKind.PLAYER).forEach(s->pinnedAudience.add(new PlayerId(s.id())));
    if(!pinnedAudience.containsAll(affected))return refused(request,"UNPINNED_SHARED_AUDIENCE");
    var guardedAudience=new HashSet<PlayerId>(participants);guardedAudience.addAll(affected);

    if(definition.target().scope()==TargetScope.GROUP){var groups=actual.context().context().groups().values().stream().filter(g->g.members().containsAll(participants)&&g.regions().contains(f.region().regionId())).toList();if(groups.size()!=1)return refused(request,"ACTUAL_GROUP");group=Optional.of(groups.getFirst().groupId());}
    var candidate=new fr.proutlost.director.engine.CandidateGenerator.Generated(plan.candidateId(),definition,plan.definition(),Optional.of(f.region().regionId()),group,plan.roles(),plan.scopes(),true);
    if(definition.eligibility() instanceof RequirementPending||new fr.proutlost.director.engine.ConditionEvaluator().evaluate(definition.eligibility(),candidate,actual)!=fr.proutlost.director.engine.ConditionEvaluator.Truth.TRUE
      ||!new fr.proutlost.director.engine.EligibilityEngine(ProviderMode.PRODUCTION).validateAudience(candidate,guardedAudience,actual,request.event()).valid())return refused(request,"CURRENT_ELIGIBILITY_OR_AUDIENCE");
    if(resumed)PhysicalSiteExecutionGuards.requireRearm(session.worldId(),request.event());
   }
   return available(request);
  }catch(SelectedEventStart.ContextUnavailable waiting){throw waiting;}catch(RuntimeException unknown){return refused(request,"VALIDATION_EXCEPTION");}
 }
 @Override public List<UniqueUseRecord> uniqueness(Request request,ReservationToken token,boolean cleanup,boolean rearm){
  if(cleanup||rearm)return List.of();var value=Objects.requireNonNull(selected.get(request.event()),"actual selected regional identity absent");
  return Objects.requireNonNull(value.start,"historical request cannot reserve a new birth").reservation(token,clock());
 }
 @Override public CatalogueEventLedger.Contribution effectResult(Request request,PrepareRecord prepare,SliceResult actual,boolean cleanup,boolean rearm){
  if(cleanup||rearm)return new CatalogueEventLedger.Contribution(List.of(),List.of());var value=Objects.requireNonNull(selected.get(request.event()));
  value.birthPrepare=prepare;return Objects.requireNonNull(value.start).effectContribution(prepare,actual,clock());
 }
 @Override public ProviderMode mode(){return ProviderMode.PRODUCTION;}
 @Override public Epoch initialOwnerEpoch(Request r){return new Epoch(0);}
 @Override public Set<CapabilityId> qualifiedCapabilities(){actor();return Set.of(WeatherCoreRegionalBinding.CAPABILITY);}
 @Override public boolean mayStart(Request r){return selected.containsKey(r.event())&&approved(r,false)&&content.envelopes().forwardAllowed(r.event());}
 @Override public boolean mayApply(Request r){return approved(r,false);}
 @Override public boolean mayResume(Request r){return approved(r,true);}
 @Override public boolean mayAwaitResume(Request r){return approved(r,true,true);}
 @Override public boolean available(Request r){actor();var l=level(r.footprint().dimension());return r.footprint().loadedChunks().stream().allMatch(c->l.getChunkSource().getChunkNow(c.x(),c.z())!=null);}
 @Override public Revision regionalRevision(Request r){return frame(r).region().membershipRevision();}
 @Override public PlanningSnapshot capture(Request request,OwnershipView regionalOwners){
  actor();var f=frame(request);var actual=currentInput();
  var sample=actual.context();var regionScope=new ScopeRef(ScopeKind.REGION,f.region().regionId().value());var worldScope=new ScopeRef(ScopeKind.WORLD,session.worldId().value());
  if(!RegionalWeatherScopeIdentity.sameDurableWorld(f.world(),actual.state().world(),clock())||!f.region().equals(actual.memory().regionRecords().get(f.region().regionId()))
      ||!f.profile().equals(actual.config())||!f.region().header().revision().equals(sample.scopeRevisions().get(regionScope))
      ||!f.world().header().revision().equals(sample.scopeRevisions().get(worldScope)))throw new SelectedEventStart.ContextUnavailable("regional WORLD/REGION/profile cut changed");
  var key=WeatherCoreRegionalBinding.resource(request.footprint().dimension(),request.event());
  if(regionalOwners.records().containsKey(key)&&!regionalOwners.records().get(key).equals(actual.state().ownership().records().get(key)))throw new SelectedEventStart.ContextUnavailable("actual regional owner cut changed");
  if(!store.residentAggregateKeys(Set.of(AggregateKind.WORLD),1).orElseThrow().cut().equals(f.cut()))throw new SelectedEventStart.ContextUnavailable("regional J04 cut advanced");
  // The general J01 sample observes player/anchor chunks. This native regional
  // operation also observes its own bounded footprint, on the same server actor.
  // getChunkNow is a presence read: it neither loads nor generates a chunk.
  var observedChunks=new HashSet<>(sample.loadedChunks());var nativeLevel=level(request.footprint().dimension());
  for(var chunk:request.footprint().loadedChunks()){
   if(nativeLevel.getChunkSource().getChunkNow(chunk.x(),chunk.z())==null)throw new SelectedEventStart.ContextUnavailable("regional native footprint chunk unavailable");
   observedChunks.add(chunk);
  }
  if(!store.residentAggregateKeys(Set.of(AggregateKind.WORLD),1).orElseThrow().cut().equals(f.cut()))throw new SelectedEventStart.ContextUnavailable("regional J04 cut advanced during actual chunk survey");
  var observed=new SnapshotBatch(sample.requestId(),sample.scopes(),sample.capturedTick(),sample.scopeRevisions(),sample.context(),sample.missingData(),observedChunks,sample.serverHealth());
  return new PlanningSnapshot(observed,actual.state().ownership(),actual.state().budgets(),new BlobBundle(List.of()));
 }
 @Override public Optional<DirectorRegionalWeatherRuntime.Reservation> reservation(Request request,PreparedEffect plan,boolean cleanup){
  actor();if(!cleanup&&!approved(request,true))return Optional.empty();var live=capture(request,new OwnershipView(Map.of()));
  var cost=cleanup?plan.budgetEstimate():definition(frame(request)).budget();
  return content.envelopes().regionalReservation(request,plan,live,cleanup,cost);
 }
 @Override public fr.proutlost.director.api.port.WorldEffectPort effectPort(PreparedEffect p,BlobBundle b,ServerEffectPort actual,boolean cleanup){return content.attach(p,b,actual,!cleanup&&((RegionalWeatherLease)p.intent().typedArgs()).profile().equals(WeatherCoreRegionalBinding.PROFILE));}
 @Override public Tick clock(){return new Tick(production.clock());}
 @Override public boolean acquireMetadataLane(Object owner){boolean held=gameplay.acquireMetadataLane(owner);if(held)metadataOwner=owner;return held;}
 @Override public void releaseMetadataLane(Object owner){gameplay.releaseMetadataLane(owner);if(metadataOwner==owner)metadataOwner=null;}
 /** Shared native atmosphere affects every real client inside its box. Its full
  * audience is pinned by normal J03 planning, then rechecked at apply/rearm. */
 private Set<PlayerId> actualAudience(Footprint footprint){
  actor();var out=new HashSet<PlayerId>();
  for(var player:level(footprint.dimension()).players()){
   var p=player.blockPosition();
   if(footprint.bounds().stream().anyMatch(b->p.getX()>=b.minX()&&p.getX()<b.maxXExclusive()&&p.getY()>=b.minY()&&p.getY()<b.maxYExclusive()&&p.getZ()>=b.minZ()&&p.getZ()<b.maxZExclusive()))
    out.add(new PlayerId(player.getUUID()));
  }
  return Set.copyOf(out);
 }
 private Footprint footprint(Candidate candidate,DecisionInput input,RegionalWeatherLease args){
  var regions=candidate.scopes().scopes().stream().filter(s->s.kind()==ScopeKind.REGION).map(s->new RegionId(s.id())).toList();require(regions.size()==1,"one selected regional weather scope");
  var region=Objects.requireNonNull(input.memory().regionRecords().get(regions.getFirst()),"real regional record missing");var shape=region.supportShape();
  require(region.dimension().equals(args.dimension())&&shape.boxes().size()==1&&shape.discs().isEmpty()&&shape.routes().isEmpty()&&RegionalWeatherScopeIdentity.matches(region,args),"regional weather requires explicit square box/current REGION revision");
  var b=shape.boxes().getFirst();var l=level(args.dimension());var box=new Aabb(args.dimension(),b.minX(),l.getMinBuildHeight(),b.minZ(),b.maxXExclusive(),l.getMaxBuildHeight(),b.maxZExclusive());
  require(box.maxXExclusive()-box.minX()==box.maxZExclusive()-box.minZ()&&box.maxXExclusive()-box.minX()<=1024,"bounded square regional atmosphere");
  var chunks=new HashSet<ChunkRef>();for(int x=box.minX()>>4;x<=((box.maxXExclusive()-1)>>4);x++)for(int z=box.minZ()>>4;z<=((box.maxZExclusive()-1)>>4);z++){chunks.add(new ChunkRef(args.dimension(),x,z));require(chunks.size()<=64,"regional observed chunk bound");}
  return new Footprint(args.dimension(),chunks,Set.of(),List.of(box));
 }
 public Map<DefinitionId,DirectorNativeCatalogueExecution.Driver> drivers(Map<DefinitionId,DirectorNativeCatalogueExecution.Driver> existing){
  actor();var result=new LinkedHashMap<>(existing);
  content.catalog().events().forEach((id,definition)->{if(definition.steps().stream().anyMatch(s->s.args() instanceof RegionalWeatherLease)){
   require(definition.steps().size()==1&&definition.steps().getFirst().primitive().equals(WeatherCoreRegionalBinding.PRIMITIVE),"regional composition must declare its single exact installed step");
   result.put(id,new DirectorNativeCatalogueExecution.Driver(){
    public boolean available(){return !stopping&&!runtime.recovering()&&production.snapshot().isPresent()&&selected.values().stream().filter(v->!v.completed).count()<4;}
    public DirectorNativeCatalogueExecution.Preview preview(Candidate c,DecisionInput input,EventInstanceId event){
     var step=definition.steps().getFirst();var fp=footprint(c,input,(RegionalWeatherLease)step.args());var intent=NativeCataloguePlanning.intent(c,definition,step,event,fp,input);
     var captured=survey.surveyBirth(intent,NativeCataloguePlanning.raw(input));return NativeCataloguePlanning.captured(c,input,event,List.of(captured),actualAudience(fp));
    }
    public DirectorNativeCatalogueExecution.StartResult begin(SelectedEventStart start){
     require(start.mode()==ProviderMode.PRODUCTION&&start.definition().equals(definition),"actual production selected regional event required");
     var old=selected.get(start.eventId());if(old!=null){require(old.start!=null&&old.start.plan().equals(start.plan()),"regional start identity changed");return new DirectorNativeCatalogueExecution.StartResult(start.eventId(),true,"ALREADY_SELECTED");}
     if(selected.size()>=128)selected.entrySet().removeIf(e->e.getValue().completed&&!e.getValue().lane&&store.physicalEventReadback(e.getKey(),4096).filter(PhysicalEventReadback::released).isPresent());
     require(selected.size()<128,"regional selected metadata bound");var value=new Selected();value.start=start;
     value.originalAudience=RegionalWeatherScopeIdentity.affectedAudience(start.captured().context(),start.plan().effects().getFirst().expectedFootprint());
     selected.put(start.eventId(),value);
     // Acceptance owns only this bounded queue entry. Fresh J04 capture, approval,
     // PREPARE and native apply remain in the existing regional actor coordinator.
     return new DirectorNativeCatalogueExecution.StartResult(start.eventId(),true,"PRODUCTION_REGIONAL_CONTEXT_QUEUE");
    }
    public Optional<DirectorNativeCatalogueExecution.EventProgress> progress(EventInstanceId e){var queued=selected.get(e);if(queued!=null&&queued.request==null)return Optional.of(new DirectorNativeCatalogueExecution.EventProgress(e,DirectorNativeCatalogueExecution.EventStage.RUNNING,"WAIT_CURRENT_REGIONAL_AUTHORIZATION"));return runtime.receipt(e).map(r->{var state=selected.get(e);var stage=r.stage()==DirectorRegionalWeatherRuntime.Stage.RECOVERY_REQUIRED?DirectorNativeCatalogueExecution.EventStage.RECOVERING:
     state!=null&&state.completed?(state.success?DirectorNativeCatalogueExecution.EventStage.COMPLETED:DirectorNativeCatalogueExecution.EventStage.ABORTED):DirectorNativeCatalogueExecution.EventStage.RUNNING;return new DirectorNativeCatalogueExecution.EventProgress(e,stage,r.diagnostic());});}
   });
  }});return Map.copyOf(result);
 }
 private boolean coldIdentity(Selected value){
  if(value.start!=null||value.cold!=null)return true;
  if(value.context==null){value.sealedEvent=event(value.request.event());
   require(value.sealedEvent.contextRef().codecId().equals(DirectorNativeEventLifecycle.CONTEXT),"original production EVENT context required");
   value.context=store.readBlobs(new BlobReadRequest(session.worldId(),session.storeGeneration(),Set.of(value.sealedEvent.contextRef()))).toCompletableFuture();return false;
  }
  if(!value.context.isDone())return false;var read=value.context.getNow(null);require(read!=null&&read.status()==AckStatus.DURABLE&&read.errors().isEmpty()&&read.contents().size()==1&&read.contents().getFirst().reference().equals(value.sealedEvent.contextRef()),"cold regional original context readback differs");
  var fields=((JsonValue.ObjectValue)CanonicalJsonV1.decode(read.contents().getFirst().bytes())).values();
  value.originalAudience=RegionalWeatherScopeIdentity.affectedAudience(new ModelCodec<>(SnapshotBatch.class).decode(NativeCatalogueColdCompletion.decodedBytes(fields.get("snapshot"))),value.request.footprint());
  require(fields.keySet().equals(Set.of("source","world","audit_decision","catalog_generation","definition","plan","snapshot"))&&fields.get("source").equals(new JsonValue.Text("ACTUAL_AUDITED_CATALOGUE_SELECTION")),"cold original selected EVENT fields differ");
  var definition=new ModelCodec<>(EventDefinition.class).decode(NativeCatalogueColdCompletion.decodedBytes(fields.get("definition")));
  var plan=new ModelCodec<>(ExecutionPlan.class).decode(NativeCatalogueColdCompletion.decodedBytes(fields.get("plan")));
  var selection=new CatalogueEventLedger.Selection(definition,plan);
  require(selection.event().equals(value.request.event())&&plan.definition().equals(value.sealedEvent.definition())&&plan.scopes().worldId().equals(session.worldId())&&plan.effects().size()==1
   &&plan.effects().getFirst().operationToken().equals(value.request.operation())&&plan.effects().getFirst().expectedFootprint().equals(value.request.footprint())&&plan.effects().getFirst().typedArgs() instanceof RegionalWeatherLease args&&args.duration().equals(value.request.duration()),"cold regional original plan differs");
  var current=currentInput();
  value.cold=new NativeCatalogueColdCompletion.Identity(definition,plan,new DecisionId(UUID.fromString(((JsonValue.Text)fields.get("audit_decision")).value())),value.sealedEvent.chainId(),NativeCatalogueLedgerState.keysBeforeApply(selection,current));
  value.coldRead=new NativeCatalogueColdCompletion.Read(store,value.cold);return true;
 }
 private List<DurableRecord> coldAbort(Selected value,Map<String,JsonValue> evidence){
  var payload=new HashMap<>(evidence);payload.put("definition",new JsonValue.Text(value.cold.definition().id().value()));payload.put("reason",new JsonValue.Text("REGIONAL_OWNED_CLEANUP_WITHOUT_COMPLETE_DURATION"));payload.put("source",new JsonValue.Text("CATALOGUE_SELECTED"));payload.put("provider_mode",new JsonValue.Text("PRODUCTION"));payload.put("decision",new JsonValue.Text(value.cold.decision().value().toString()));
  var id=new FactId(UUID.nameUUIDFromBytes(("catalogue-aborted-v1\0"+value.request.event().value()).getBytes(StandardCharsets.UTF_8)));
  return List.of(new DurableFact(new DirectorFact(id,new FactType("proutlost_director:catalogue_event_aborted"),value.cold.plan().scopes().scopes().stream().sorted(Comparator.comparing(ScopeRef::toString)).toList(),Optional.of(value.request.event()),EvidenceStatus.CONFIRMED,payload,true,Optional.empty())));
 }
 public void poll(){actor();
  if(production.phase()==DirectorProductionRuntime.Phase.RECOVERING||production.snapshot().isEmpty()){
   runtime.pollRecoveryOnly();return; // Existing J04 history/result classification only. No birth, REARM or cleanup apply.
  }
  polling=true;try{
  for(var value:List.copyOf(selected.values()))if(value.start!=null&&!value.submitted&&!value.completed){
   try{
    var intent=value.start.plan().effects().getFirst();var args=(RegionalWeatherLease)intent.typedArgs();
    var draft=new Request(value.start.eventId(),intent.operationToken(),intent.expectedFootprint(),args.duration(),new Fingerprint("0".repeat(64)));
    var request=new Request(draft.event(),draft.operation(),draft.footprint(),draft.duration(),authorization(draft,frame(draft)));
    runtime.submit(request);value.request=request;value.submitted=true;
   }catch(SelectedEventStart.ContextUnavailable waiting){continue;}
  }
  for(var value:List.copyOf(selected.values()))if(value.start!=null&&value.submitted&&!value.completed){
   long deadline=Math.addExact(value.start.captured().context().capturedTick().value(),value.start.definition().timing().maxDurationTicks().value());
   if(stopping||Math.addExact(clock().value(),value.request.duration().value())>deadline&&runtime.receipt(value.request.event()).filter(r->!r.nativeBirthConfirmed()&&r.forwardInvocationsThisBoot()==0).isPresent())runtime.stop(value.request.event());
  }
  runtime.poll();
  for(var request:runtime.requests())if(!selected.containsKey(request.event())&&runtime.receipt(request.event()).filter(DirectorRegionalWeatherRuntime.Receipt::restoredFromCold).isPresent()){
   require(selected.size()<128,"cold regional end metadata cache bound");var value=new Selected();value.request=request;value.submitted=true;selected.put(request.event(),value);
  }
  for(var value:List.copyOf(selected.values())){
  if(value.completed||value.request==null)continue;
  try{
  if(!coldIdentity(value))continue;var receipt=runtime.receipt(value.request.event());if(receipt.isEmpty()||receipt.orElseThrow().stage()!=DirectorRegionalWeatherRuntime.Stage.COMPLETE)continue;
  if(value.ack==null){if(!value.lane){if(!acquireMetadataLane(value))continue;value.lane=true;}
   var actual=receipt.orElseThrow();value.success=actual.nativeBirthConfirmed()&&!actual.uncertainHistoricalOutcome()&&actual.nativeUuid().isPresent()&&actual.nativeDeadline()>0&&level(value.request.footprint().dimension()).getGameTime()>=actual.nativeDeadline();
   var evidence=Map.<String,JsonValue>of("weathercore_native_uuid",new JsonValue.Text(actual.nativeUuid().map(UUID::toString).orElse("NONE")),"native_deadline",new JsonValue.Text(Long.toString(actual.nativeDeadline())),"cleanup_proved",new JsonValue.Bool(true),"recovery_complete",new JsonValue.Bool(true));
   var rows=new ArrayList<DurableRecord>();var blobs=new ArrayList<BlobContent>();Map<ScopeRef,Revision> cas=Map.of();
   if(value.start!=null){
    if(value.success){var contribution=value.start.completionContribution(Objects.requireNonNull(value.birthPrepare),evidence,clock());rows.addAll(contribution.records());blobs.addAll(contribution.blobs());}
    else rows.addAll(value.start.abortRecords("REGIONAL_OWNED_CLEANUP_WITHOUT_COMPLETE_DURATION",evidence));
   }else{
    var current=value.coldRead.poll();if(current.isEmpty())continue;var view=current.orElseThrow();cas=view.scopeRevisions();
    if(value.success){var contribution=NativeCatalogueColdCompletion.completed(value.cold,view,evidence,false,clock());rows.addAll(contribution.records());blobs.addAll(contribution.blobs());}
    else rows.addAll(coldAbort(value,evidence));
   }
   value.batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),session.storeGeneration(),cas,rows,blobs);value.ack=store.append(value.batch).toCompletableFuture();continue;
  }
  if(!value.ack.isDone())continue;var ack=value.ack.getNow(null);require(ack!=null&&ack.ackStatus()==AckStatus.DURABLE&&ack.transactionId().equals(value.batch.transactionId())&&ack.transactionHash().equals(value.batch.transactionHash())&&ack.storeGeneration().equals(session.storeGeneration())&&ack.durableSequence().value()>0&&ack.forcedBlobs().equals(DurableBlobReferences.collect(value.batch.records())),"actual regional completion ACK uncertain");
  value.completed=true;releaseMetadataLane(value);value.lane=false;
  }catch(SelectedEventStart.ContextUnavailable waiting){if(value.ack==null&&value.lane){releaseMetadataLane(value);value.lane=false;}}
 }
 }finally{polling=false;cachedInput=null;cachedInputCut=null;cachedInputOwner=null;cachedInputTick=-1;}
 }
 public void stopAll(){actor();stopping=true;runtime.stopAll();}
 public DirectorRegionalWeatherRuntime runtime(){actor();return runtime;}
 @Override public void close(){actor();runtime.close();closed=true;}
}
