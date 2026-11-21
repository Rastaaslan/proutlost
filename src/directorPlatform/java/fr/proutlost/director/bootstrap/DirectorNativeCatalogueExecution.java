package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.PlanningPort;
import fr.proutlost.director.api.port.PhysicalSiteResolverPort;
import fr.proutlost.director.api.port.PhysicalSitePinJournalPort;
import fr.proutlost.director.engine.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.ResultTransaction;
import fr.proutlost.director.runtime.ServerHealthTransitions;
import fr.proutlost.director.runtime.DecisionWorkBudget;
import fr.proutlost.director.runtime.BoundedDecisionWorker;
import fr.proutlost.director.runtime.SpeculativeCpuRegistry;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import net.minecraft.server.MinecraftServer;

/** Native capture -> pure J03 evaluation -> existing durable audit -> real event driver.
 * Production activation uses explicitly validated runtime content; legacy constructors stay LAB-only.
 * Captures never apply effects. Every driver still performs J03 admission, J04 PREPARE
 * and live J05 validation before executing its preserved selected operations. */
public final class DirectorNativeCatalogueExecution implements fr.proutlost.director.api.port.DecisionPort,fr.proutlost.director.api.port.ProviderProvenance {
 public record Preview(List<PlanningSnapshot> snapshots,Map<OperationToken,PreparedEffect> effects) {
  public Preview {snapshots=List.copyOf(snapshots);effects=Map.copyOf(effects);}
 }
 public record StartResult(EventInstanceId eventId,boolean accepted,String message) {
  public StartResult {Objects.requireNonNull(eventId);Objects.requireNonNull(message);}
 }
 public enum EventStage { RUNNING,COMPLETED,ABORTED,RECOVERING }
 public record EventProgress(EventInstanceId eventId,EventStage stage,String detail) {
  public EventProgress {Objects.requireNonNull(eventId);Objects.requireNonNull(stage);Objects.requireNonNull(detail);}
 }
 public interface Driver {
  default boolean installed(){return true;}
  boolean available();
  /** Read-only native survey before J03 condition evaluation; never changes WAL state/health/CAS. */
  default DecisionInput enrich(DecisionInput input){return input;}
  Preview preview(Candidate candidate,DecisionInput captured,EventInstanceId eventId);
  /** Read-only rejection before the invocation tombstone; never an apply permit or ACK. */
  default GateResult preflight(SelectedEventStart selected){return new GateResult(GateStatus.READY,"NO_ADDITIONAL_PREFLIGHT");}
  StartResult begin(SelectedEventStart selected);
  Optional<EventProgress> progress(EventInstanceId eventId);
 }
 public enum GateStatus { WAITING,READY,REJECTED }
 public record GateResult(GateStatus status,String detail) {}
 public enum Stage { WAITING_FOR_AUDIT,WAITING_FOR_REQUIRE_BINDING,WAITING_FOR_ABORT,NO_PLAN,REJECTED,STARTED,ALREADY_STARTED }
 public record QueuedIntent(EventInstanceId eventId,DefinitionId definition,Optional<ChainId> parentChain,
     PlayerId actor,Optional<RegionId> region,StoreSession session){
  public QueuedIntent{Objects.requireNonNull(eventId);Objects.requireNonNull(definition);Objects.requireNonNull(parentChain);Objects.requireNonNull(actor);Objects.requireNonNull(region);Objects.requireNonNull(session);}
 }
 public enum AbortStatus { WAITING_FOR_AUDIT,WAITING_FOR_ACK,DURABLE,REJECTED,DRIVER_INVOKED }
 public record AbortResult(AbortStatus status,EventInstanceId eventId,String detail){}
 public record Dispatch(DecisionId decisionId,Stage stage,Optional<EventInstanceId> eventId,String detail) {}
 private record Captured(DecisionInput input,DecisionProposal proposal,Map<OperationToken,PreparedEffect> effects,Map<CandidateId,List<PlanningSnapshot>> planning) {}
 private final MinecraftServer server;private final WorldId world;private final JournalStore store;
 private final CatalogSnapshot catalog;private final Map<DefinitionId,Driver> drivers;
 private final ProviderMode mode;private final Optional<ConfigView> config;private final DirectorRuntimeContent content;
 private final Map<DecisionId,Captured> captures=new LinkedHashMap<>();
 private final SpeculativeCpuRegistry speculativeCpu=new SpeculativeCpuRegistry();
 private PhysicalSiteResolverPort physicalSites;
 private PhysicalSitePinJournalPort physicalSiteJournal;
 private final Map<DecisionId,PhysicalSiteOffer> physicalSiteOffers=new HashMap<>();
 /** Called only by the world-local owner before its first decision. It cannot replace a live adapter. */
 public void physicalSites(PhysicalSiteResolverPort resolver,PhysicalSitePinJournalPort journal){
  actor();Objects.requireNonNull(resolver);Objects.requireNonNull(journal);
  if((physicalSites!=null&&physicalSites!=resolver)||(physicalSiteJournal!=null&&physicalSiteJournal!=journal)||!captures.isEmpty())
   throw new IllegalStateException("physical site owner already bound/decisions already captured");
  physicalSites=resolver;physicalSiteJournal=journal;
 }
 private GateResult physicalSiteGate(DecisionId decision,ExecutionPlan plan){
  if(physicalSites==null||!physicalSites.requires(plan.definition()))return new GateResult(GateStatus.READY,"NO_PHYSICAL_SITE_REQUIREMENT");
  var offer=Objects.requireNonNull(physicalSiteOffers.get(decision),"required full D0 selection was not captured");
  offer.pin().requirePlan(plan);physicalSites.requireCurrent(offer.pin(),plan,Optional.empty());
  var status=physicalSiteJournal.ensurePinned(offer);
  return switch(status){
   case PENDING->new GateResult(GateStatus.WAITING,"WAIT_FULL_D0_SITE_PIN_J04");
   case REFUSED,FAILED_SAFE->new GateResult(GateStatus.REJECTED,"FULL_D0_SITE_PIN_"+status);
   case DURABLE->{if(!physicalSiteJournal.durable(offer.pin()))throw new IllegalStateException("D0 pin ACK without actual readback");
    physicalSiteJournal.bindForward(offer.pin(),plan,physicalSites);
    yield new GateResult(GateStatus.READY,"FULL_D0_SITE_PIN_DURABLE");}
  };
 }
 private SelectedEventStart physicalSite(DecisionId decision,SelectedEventStart selected){
  var offer=physicalSiteOffers.get(decision);return offer==null?selected:selected.withPhysicalSite(offer.pin(),physicalSites,physicalSiteJournal);
 }

 private final Map<DecisionId,StartResult> started=new HashMap<>();
 private final Set<DecisionId> invokedDecisions=new HashSet<>();private final Set<EventInstanceId> invokedEvents=new HashSet<>(),cancelledBeforeDriver=new HashSet<>();
 private final Map<EventInstanceId,QueuedIntent> queuedIntents=new LinkedHashMap<>();
 private final Map<EventInstanceId,DecisionId> latestQueuedDecision=new HashMap<>();
 private static final class PreStartAbort {StoreSession session;DurableBatch batch;CompletableFuture<CommitAck> ack;}
 private final Map<EventInstanceId,PreStartAbort> preStartAborts=new HashMap<>();
 private SelectedEventStart.CompletionHook completion=(selected,evidence)->selected.originalCompletionRecords(evidence);
 private SelectedEventStart.AbortHook abort=(selected,reason,evidence)->selected.originalAbortRecords(reason,evidence);
 private final int maximumCaptures;
 private Supplier<Optional<DecisionInput>> live=Optional::empty;
 private boolean liveBound;
 private Predicate<SelectedEventStart> requirementBound=selected->false;
 private Function<SelectedEventStart,GateResult> abortBindingGate=selected->selected.definition().eligibility() instanceof RequirementPending?
  new GateResult(GateStatus.REJECTED,"real REQUIRE binding bridge absent"):new GateResult(GateStatus.READY,"not REQUIRE");
 private BiFunction<SelectedEventStart,DirectorDecisionJournal,GateResult> gate=(selected,journal)->abortBindingGate.apply(selected);
 private BiConsumer<SelectedEventStart,BooleanSupplier> globalStaging=(selected,authorized)->{};
 public void globalEnvelopeStaging(BiConsumer<SelectedEventStart,BooleanSupplier> stage){actor();globalStaging=Objects.requireNonNull(stage);}
 private final Map<EventInstanceId,EventProgress> terminal=new HashMap<>();
 private final Map<DefinitionId,String> previewDeferrals=new HashMap<>();
 public DirectorNativeCatalogueExecution(MinecraftServer server,WorldId world,JournalStore store,
   CatalogSnapshot catalog,Map<DefinitionId,Driver> drivers) {
  this(server,world,store,catalog,drivers,32);
 }
 public DirectorNativeCatalogueExecution(MinecraftServer server,WorldId world,JournalStore store,
   CatalogSnapshot catalog,Map<DefinitionId,Driver> drivers,int maximumCaptures) {
  this(server,world,store,catalog,drivers,maximumCaptures,ProviderMode.TEST_ONLY,Optional.empty(),null);
 }
 public DirectorNativeCatalogueExecution(MinecraftServer server,WorldId world,JournalStore store,
   DirectorRuntimeContent content,Map<DefinitionId,Driver> drivers) {
  this(server,world,store,content,drivers,32);
 }
 public DirectorNativeCatalogueExecution(MinecraftServer server,WorldId world,JournalStore store,
   DirectorRuntimeContent content,Map<DefinitionId,Driver> drivers,int maximumCaptures) {
  this(server,world,store,Objects.requireNonNull(content).catalog(),drivers,maximumCaptures,content.mode(),Optional.of(content.config()),content);
 }
 private DirectorNativeCatalogueExecution(MinecraftServer server,WorldId world,JournalStore store,
   CatalogSnapshot catalog,Map<DefinitionId,Driver> drivers,int maximumCaptures,ProviderMode mode,Optional<ConfigView> config,DirectorRuntimeContent content) {
  this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.store=Objects.requireNonNull(store);
  this.catalog=Objects.requireNonNull(catalog);this.drivers=Map.copyOf(drivers);
  this.mode=Objects.requireNonNull(mode);this.config=Objects.requireNonNull(config);this.content=content;
  if(maximumCaptures<1||maximumCaptures>128)throw new IllegalArgumentException("catalogue capture bound");
  this.maximumCaptures=maximumCaptures;actor();
  if(!catalog.events().keySet().equals(this.drivers.keySet())||(mode==ProviderMode.TEST_ONLY?catalog.events().size()!=9:catalog.events().size()<9||catalog.events().size()>128))
   throw new IllegalArgumentException("exact nine composition drivers required");
 }
 private void actor(){if(!server.isSameThread())throw new IllegalStateException("catalogue server actor");}
 public CatalogSnapshot catalog(){return catalog;}
 public ProviderMode mode(){return mode;}
 public Set<ProviderMode> supportedModes(){return Set.of(mode);}
 private DecisionInput checkedInput(DecisionInput input){
  Objects.requireNonNull(input);
  if(!world.equals(input.state().world().worldId())||!catalog.equals(input.catalog())
      ||content!=null&&!content.acceptsConfig(input.config()))
   throw new IllegalStateException("actual catalogue input provenance changed");
  return input;
 }
 public void completionHook(SelectedEventStart.CompletionHook hook){actor();completion=Objects.requireNonNull(hook);}
 public void abortHook(SelectedEventStart.AbortHook hook){actor();abort=Objects.requireNonNull(hook);}
 public DecisionInput enrichInput(DecisionInput input){
  actor();var result=checkedInput(input);
  var health=ServerHealthTransitions.worst(result.state().world().healthMode(),result.context().serverHealth());
  var sensing=new LinkedHashSet<Driver>();
  drivers.forEach((id,driver)->{
   var definition=catalog.events().get(id);
   if(health==HealthMode.NORMAL||definition!=null&&ServerHealthTransitions.allowsNewScene(health,definition))sensing.add(driver);
  });
  // Under pressure, retain missing heavy-risk data rather than running surveys
  // for recipes that the measured health fence already makes inadmissible.
  for(var driver:sensing){
   var before=result;result=Objects.requireNonNull(driver.enrich(before));
   if(!result.state().equals(before.state())||!result.memory().equals(before.memory())||!result.catalog().equals(before.catalog())||
      !result.config().equals(before.config())||!result.requests().equals(before.requests())||!result.chains().equals(before.chains())||
      result.seed()!=before.seed()||!result.advisorySnapshot().equals(before.advisorySnapshot())||
      !result.context().scopeRevisions().equals(before.context().scopeRevisions())||
      result.context().serverHealth()!=before.context().serverHealth()||!result.context().scopes().equals(before.context().scopes()))
    throw new IllegalArgumentException("native survey changed durable input identity/state/health");
  }
  return result;
 }
 public boolean installed(){actor();return drivers.keySet().equals(catalog.events().keySet())&&drivers.values().stream().allMatch(Driver::installed);}
 public void requirementBinding(Predicate<SelectedEventStart> check){actor();requirementBound=Objects.requireNonNull(check);}
 public void liveContext(Supplier<Optional<DecisionInput>> source){actor();var actual=Objects.requireNonNull(source);live=()->actual.get().map(this::checkedInput);liveBound=true;}
 public void preAdmissionGate(Function<SelectedEventStart,GateResult> callback){
  actor();var bound=Objects.requireNonNull(callback);gate=(selected,journal)->bound.apply(selected);abortBindingGate=bound;
 }
 public void preAdmissionGate(BiFunction<SelectedEventStart,DirectorDecisionJournal,GateResult> callback){actor();gate=Objects.requireNonNull(callback);}
 /** Metadata-only REQUIRE gate for cancellation. Never begin an EventLifecycle here. */
 public void preStartAbortGate(Function<SelectedEventStart,GateResult> callback){actor();abortBindingGate=Objects.requireNonNull(callback);}
 public boolean ready(){actor();return liveBound&&!store.readOnlySafe()&&drivers.values().stream().allMatch(Driver::available);}
 public boolean ready(DefinitionId id){actor();var driver=drivers.get(id);return liveBound&&!store.readOnlySafe()&&driver!=null&&driver.available();}
 public Optional<DecisionInput> capturedInput(DecisionId id){actor();return Optional.ofNullable(captures.get(id)).map(Captured::input);}
 public Map<CandidateId,List<PlanningSnapshot>> capturedPlanningSnapshots(DecisionId id){actor();return Objects.requireNonNull(captures.get(id),"native capture absent").planning();}
 public Map<OperationToken,PreparedEffect> capturedEffects(DecisionId id){actor();return Objects.requireNonNull(captures.get(id),"native capture absent").effects();}
 public static OperationToken operation(EventInstanceId event,String step) {
  return new OperationToken(UUID.nameUUIDFromBytes(("catalogue-operation-v1\0"+event.value()+"\0"+step).getBytes(StandardCharsets.UTF_8)));
 }
 private EventInstanceId instance(Candidate candidate,DecisionInput input) {
  return new EventInstanceId(UUID.nameUUIDFromBytes(("catalogue-event-v1\0"+world.value()+"\0"+
   input.state().world().engineControl().revision().value()+"\0"+candidate.id().value()).getBytes(StandardCharsets.UTF_8)));
 }
 /** Runtime continuation; data stays frozen while surveys/previews span actor ticks.
  * The old synchronous evaluate entry remains a compatibility/replay entry. The
  * production scheduler uses only this lane and never waits for a CPU future. */
 public static final class EvaluationWork {
  private final StoreSession session;private final Optional<DefinitionId> only;private final Optional<EventInstanceId> requested;
  private final Optional<RegionId> region;private final Set<PlayerId> required;private final DecisionWorkBudget budget;
  private final List<Driver> surveys;private final BoundedDecisionWorker worker;
  private DecisionInput input;private int surveyIndex,candidateIndex,stepIndex;private String stage="SURVEY";
  private DecisionProposal known,result;private BoundedDecisionWorker.Work<FirstPass> first;
  private BoundedDecisionWorker.Work<DecisionProposal> second;
  private final Map<CandidateId,List<PlanningSnapshot>> snapshots=new HashMap<>();
  private final Map<OperationToken,PreparedEffect> effects=new HashMap<>();
  private final Map<OperationToken,PlannedPrimitive> planned=new HashMap<>();
  private final Map<CandidateId,PhysicalSiteSelection> sites=new HashMap<>();
  private final Set<EventInstanceId> requestedUsed=new HashSet<>();
  private Candidate previewCandidate;private EventInstanceId previewEvent,previewInstance;private Preview preview;
  private Map<CandidateId,CandidateGenerator.Generated> generated=Map.of();
  private final Set<OperationToken> previewOperations=new HashSet<>();
  private boolean cancelled,workerCpuMissing;private long queueNanos,workerWall,workerCpu;
  private EvaluationWork(StoreSession session,DecisionInput input,Optional<DefinitionId> only,Optional<EventInstanceId> requested,
   Optional<RegionId> region,Set<PlayerId> required,List<Driver> surveys,BoundedDecisionWorker worker){
   this.session=session;this.input=input;this.only=only;this.requested=requested;this.region=region;this.required=Set.copyOf(required);
   this.surveys=List.copyOf(surveys);this.worker=worker;budget=new DecisionWorkBudget(input.config());
  }
  public DecisionInput frozenInput(){return input;}
  public String stage(){return stage;}
  public DecisionWorkBudget.Metrics actorMetrics(){return budget.metrics();}
  public long queueNanos(){return queueNanos;}public long workerWallNanos(){return workerWall;}public long workerCpuNanos(){return workerCpuMissing?-1:workerCpu;}
 }
 private record FirstPass(DecisionInput input,DecisionProposal proposal,Map<CandidateId,CandidateGenerator.Generated> generated){}
 private static DecisionInput restrictedInput(DecisionInput input,Optional<DefinitionId> only,Optional<EventInstanceId> requested,
   Optional<RegionId> region,Set<PlayerId> required){
  if(only.isEmpty()&&requested.isEmpty()&&region.isEmpty()&&required.isEmpty())return input;
  var restriction="catalogue-restriction-v1\\0"+input.seed()+"\\0"+only+"\\0"+requested+"\\0"+region+"\\0"+
   required.stream().sorted().map(p->p.value().toString()).toList();
  var key=UUID.nameUUIDFromBytes(restriction.getBytes(StandardCharsets.UTF_8));
  return new DecisionInput(input.context(),input.memory(),input.catalog(),input.config(),input.requests(),input.chains(),
   key.getMostSignificantBits()^key.getLeastSignificantBits(),input.state(),input.advisorySnapshot());
 }
 private List<Driver> surveyDrivers(DecisionInput input){
  var health=ServerHealthTransitions.worst(input.state().world().healthMode(),input.context().serverHealth());
  var result=new LinkedHashSet<Driver>();drivers.forEach((id,driver)->{
   var definition=catalog.events().get(id);
   if(health==HealthMode.NORMAL||definition!=null&&ServerHealthTransitions.allowsNewScene(health,definition))result.add(driver);
  });return List.copyOf(result);
 }
 private static void requireSurveyIdentity(DecisionInput before,DecisionInput after){
  if(!after.state().equals(before.state())||!after.memory().equals(before.memory())||!after.catalog().equals(before.catalog())
   ||!after.config().equals(before.config())||!after.requests().equals(before.requests())||!after.chains().equals(before.chains())
   ||after.seed()!=before.seed()||!after.advisorySnapshot().equals(before.advisorySnapshot())
   ||!after.context().scopeRevisions().equals(before.context().scopeRevisions())
   ||after.context().serverHealth()!=before.context().serverHealth()||!after.context().scopes().equals(before.context().scopes()))
   throw new IllegalArgumentException("native survey changed durable input identity/state/health");
 }
 public EvaluationWork beginEvaluation(DecisionInput input,Optional<DefinitionId> only,Optional<EventInstanceId> requested,
   Optional<RegionId> region,Set<PlayerId> required,BoundedDecisionWorker worker){
  actor();checkedInput(input);Objects.requireNonNull(only);Objects.requireNonNull(requested);Objects.requireNonNull(region);Objects.requireNonNull(worker);
  if(store.readOnlySafe())throw new IllegalStateException("decision worker lost journal authority");
  if(requested.isPresent()&&only.isEmpty())throw new IllegalArgumentException("requested event needs one definition");
  var work=new EvaluationWork(store.session(),input,only,requested,region,required,surveyDrivers(input),worker);
  speculativeCpu.track(work,()->cancelEvaluation(work));return work;
 }
 private static void workerMeasured(EvaluationWork work,BoundedDecisionWorker.Metrics metrics){
  work.queueNanos=Math.addExact(work.queueNanos,metrics.queueWaitNanos());work.workerWall=Math.addExact(work.workerWall,metrics.workerWallNanos());
  if(metrics.workerCpuNanos()>=0)work.workerCpu=Math.addExact(work.workerCpu,metrics.workerCpuNanos());else work.workerCpuMissing=true;
 }
 /** A denied unit stays pending. No partial result/audit is created by a deadline. */
 public Optional<DecisionProposal> pollEvaluation(EvaluationWork work,long now){return pollEvaluation(work,now,()->true);}
 public Optional<DecisionProposal> pollEvaluation(EvaluationWork work,long now,BooleanSupplier sharedGate){
  actor();Objects.requireNonNull(work);work.budget.sharedGate(sharedGate);Optional<DecisionProposal> result;
  try{result=work.budget.account(()->pollEvaluationActor(work,now));}finally{work.budget.sharedGate(()->true);}
  if(result.isPresent()&&work.stage.equals("DONE")){
   speculativeCpu.completed(work);
   var metrics=work.budget.metrics();System.out.println("DIRECTOR_DECISION_CPU_WORK slices="+metrics.slices()+" actor_wall_ns="+metrics.actorWallNanos()
    +" actor_cpu_ns="+metrics.actorCpuNanos()+" unpreemptible_unit_overruns="+metrics.unpreemptibleUnitOverruns()+" queue_wait_ns="+work.queueNanos
    +" worker_wall_ns="+work.workerWall+" worker_cpu_ns="+work.workerCpuNanos()+" source=DECISION_LANE_NOT_SERVER_MSPT");
  }
  return result;
 }
 private Optional<DecisionProposal> pollEvaluationActor(EvaluationWork work,long now){
  if(work.cancelled)throw new CancellationException("decision continuation cancelled");
  if(!work.session.equals(store.session())||store.readOnlySafe()){cancelEvaluation(work);throw new CancellationException("decision continuation session revoked");}
  work.budget.begin(now);
  while(work.result==null&&work.budget.take()){
   if(work.stage.equals("SURVEY")){
    if(work.surveyIndex<work.surveys.size()){
     work.budget.run(()->{var before=work.input;var after=Objects.requireNonNull(work.surveys.get(work.surveyIndex).enrich(before));
      requireSurveyIdentity(before,after);work.input=after;work.surveyIndex++;});
    }else work.stage="FIRST_PURE";
   }else if(work.stage.equals("FIRST_PURE")){
    if(work.first==null){
     var captured=work.input;var only=work.only;var requested=work.requested;var region=work.region;var required=work.required;var evaluationMode=mode;
     var submitted=work.worker.submit(()->{var frozen=restrictedInput(captured,only,requested,region,required);
      var details=new WorldDirector(evaluationMode).evaluateDetailed(frozen);return new FirstPass(frozen,details.proposal(),details.generatedCandidates());});
     if(submitted.isEmpty())return Optional.empty();work.first=submitted.orElseThrow();return Optional.empty();
    }
    if(!work.first.done())return Optional.empty();
    work.budget.run(()->{var computed=work.first.result();workerMeasured(work,work.first.metrics());work.first=null;
     work.input=computed.input();work.known=computed.proposal();work.generated=computed.generated();var previous=captures.get(work.known.proposalId());
     if(previous!=null){if(!previous.input().equals(work.input))throw new IllegalStateException("decision input ID conflict");work.result=previous.proposal();work.stage="DONE";}
     else{if(captures.size()>=maximumCaptures)throw new IllegalStateException("catalogue capture queue full");work.stage="PREVIEW";}
    });
   }else if(work.stage.equals("PREVIEW")){
    if(work.candidateIndex>=work.known.candidates().size()){work.stage="FINAL_PURE";continue;}
    work.budget.run(()->captureNextPreview(work));
   }else if(work.stage.equals("PLAN")){
    work.budget.run(()->captureNextPrimitive(work));
   }else if(work.stage.equals("FINAL_PURE")){
    if(work.second==null){
     var captured=work.input;var snapshots=Map.copyOf(work.snapshots);var planned=Map.copyOf(work.planned);var evaluationMode=mode;
     var submitted=work.worker.submit(()->{
      PlanningPort immutable=request->{var binding=request.snapshot().bindings().orElseThrow();
       var value=Objects.requireNonNull(planned.get(binding.operationToken()),"actor planned primitive missing");
       if(!value.bindings().equals(binding)||!value.effect().snapshot().equals(request.snapshot().snapshot())
        ||!ObservedWeatherArguments.matches(request.step().args(),value.effect().intent().typedArgs(),value.effect().preconditions())
        ||!value.effect().intent().primitive().equals(request.step().primitive()))throw new IllegalArgumentException("actor planned primitive identity changed");
       return value;};
      return new WorldDirector(evaluationMode,new CandidatePlanner(immutable,snapshots)).evaluate(captured);
     });
     if(submitted.isEmpty())return Optional.empty();work.second=submitted.orElseThrow();return Optional.empty();
    }
    if(!work.second.done())return Optional.empty();
    work.budget.run(()->{work.result=work.second.result();workerMeasured(work,work.second.metrics());work.second=null;work.stage="PUBLISH";});
   }else if(work.stage.equals("PUBLISH"))throw new IllegalStateException("unpublished decision result loop");
   else throw new IllegalStateException("decision continuation stage "+work.stage);
  }
  if(work.result!=null&&!work.stage.equals("DONE")){
   if(!work.budget.take())return Optional.empty();
   work.budget.run(()->publishEvaluation(work));
  }
  return work.stage.equals("DONE")?Optional.of(work.result):Optional.empty();
 }
 private void captureNextPreview(EvaluationWork work){
  var candidate=work.known.candidates().get(work.candidateIndex++);
  if(work.only.filter(id->!id.equals(candidate.definition().id())).isPresent()
   ||work.region.isPresent()&&!candidate.scopes().scopes().contains(new ScopeRef(ScopeKind.REGION,work.region.orElseThrow().value())))return;
  Set<PlayerId> players=new HashSet<>();candidate.roles().roles().values().forEach(players::addAll);
  if(!players.containsAll(work.required)||candidate.reasons().stream().anyMatch(reason->reason!=fr.proutlost.director.api.error.ReasonCode.CAPABILITY_MISSING))return;
  var driver=drivers.get(candidate.definition().id());if(driver==null||!driver.available())return;
  var event=work.requested.orElseGet(()->instance(candidate,work.input));if(work.requested.isPresent()&&work.requestedUsed.contains(event))return;
  try{
   var preview=driver.preview(candidate,work.input,event);
   if(preview.snapshots().size()!=work.input.catalog().events().get(candidate.definition().id()).steps().size())throw new IllegalArgumentException("all original steps must be captured");
   if(physicalSites!=null&&physicalSites.requires(candidate.definition())){
    var site=physicalSites.resolve(candidate,work.input,event,List.copyOf(preview.effects().values()));if(site.isEmpty())return;work.sites.put(candidate.id(),site.orElseThrow());
   }
   work.previewCandidate=candidate;work.previewEvent=event;work.preview=preview;work.previewInstance=null;work.previewOperations.clear();work.stepIndex=0;work.stage="PLAN";
  }catch(RuntimeException notReady){
   String message=notReady.getClass().getSimpleName()+": "+String.valueOf(notReady.getMessage()).replace('\n',' ').replace('\r',' ');
   if(message.length()>320)message=message.substring(0,320);
   if(!message.equals(previewDeferrals.put(candidate.definition().id(),message)))System.out.println("DIRECTOR_NATIVE_PREVIEW_DEFERRED definition="+candidate.definition().id().value()+" cause="+message);
  }
 }
 private void captureNextPrimitive(EvaluationWork work){
  var preview=work.preview;var candidate=work.previewCandidate;
  if(work.stepIndex<preview.snapshots().size()){
   var snapshot=preview.snapshots().get(work.stepIndex);
   var step=work.input.catalog().events().get(candidate.definition().id()).steps().get(work.stepIndex);
   var generated=Objects.requireNonNull(work.generated.get(candidate.id()),"exact generated candidate absent");
   if(!CandidatePlanner.validateCapturedSnapshot(generated,work.input,snapshot,step).valid()){work.stepIndex++;return;}
   var binding=snapshot.bindings().orElseThrow();
   if(!work.previewOperations.add(binding.operationToken())||work.previewInstance!=null&&!work.previewInstance.equals(binding.instanceId())){work.stepIndex++;return;}
   work.previewInstance=binding.instanceId();
   var prepared=Objects.requireNonNull(preview.effects().get(binding.operationToken()),"native captured effect absent");
   if(!prepared.snapshot().equals(snapshot.snapshot())||!ObservedWeatherArguments.matches(step.args(),prepared.intent().typedArgs(),prepared.preconditions())
    ||!prepared.intent().primitive().equals(step.primitive()))throw new IllegalArgumentException("native captured planning identity");
   var request=new PlanningRequest(step,snapshot);var planned=content==null?new PlannedPrimitive(binding,prepared):content.plan(request,prepared);
   if(work.planned.putIfAbsent(binding.operationToken(),planned)!=null)throw new IllegalArgumentException("duplicate actor planned operation");
   work.stepIndex++;return;
  }
  for(var entry:preview.effects().entrySet())if(work.effects.putIfAbsent(entry.getKey(),entry.getValue())!=null)throw new IllegalArgumentException("duplicate captured operation");
  previewDeferrals.remove(candidate.definition().id());work.snapshots.put(candidate.id(),preview.snapshots());
  if(work.requested.isPresent())work.requestedUsed.add(work.previewEvent);
  work.preview=null;work.previewCandidate=null;work.previewEvent=null;work.stage="PREVIEW";
 }
 private void publishEvaluation(EvaluationWork work){
  var proposal=work.result;var previous=captures.get(proposal.proposalId());
  if(previous!=null){
   if(!previous.input().equals(work.input)||!previous.proposal().equals(proposal))throw new IllegalStateException("decision input ID conflict at publication");
   if(physicalSites!=null)work.sites.values().forEach(physicalSites::discard);work.sites.clear();work.stage="DONE";return;
  }
  if(captures.size()>=maximumCaptures)throw new IllegalStateException("catalogue capture queue full at publication");
  if(proposal.selectedPlan().isPresent()&&physicalSites!=null&&physicalSites.requires(proposal.selectedPlan().orElseThrow().definition())){
   var plan=proposal.selectedPlan().orElseThrow();var selection=Objects.requireNonNull(work.sites.get(plan.candidateId()),"full D0 selection absent from exact planned candidate");
   var offer=physicalSites.pin(selection,plan);work.sites.remove(plan.candidateId());physicalSiteOffers.put(proposal.proposalId(),offer);
  }
  if(physicalSites!=null)work.sites.values().forEach(physicalSites::discard);work.sites.clear();
  captures.put(proposal.proposalId(),new Captured(work.input,proposal,Map.copyOf(work.effects),Map.copyOf(work.snapshots)));
  work.requested.ifPresent(event->latestQueuedDecision.put(event,proposal.proposalId()));work.stage="DONE";
 }
 /** Cancel speculative data only; published captures/journals and physical
  * ownership remain with their original exact-ACK lifecycle. */
 public void revokeSpeculativeCpu(){actor();speculativeCpu.revokeAll();}
 public void cancelEvaluation(EvaluationWork work){
  actor();if(work==null)return;work.cancelled=true;
  if(work.first!=null)work.first.cancel();if(work.second!=null)work.second.cancel();work.stage="CANCELLED";
  RuntimeException failure=null;
  for(var iterator=work.sites.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();
   try{if(physicalSites!=null)physicalSites.discard(entry.getValue());iterator.remove();}
   catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
  }
  if(work.sites.isEmpty())speculativeCpu.completed(work);
  if(failure!=null)throw failure;
 }
 public DecisionProposal evaluate(DecisionInput input) {
  return evaluate(input,Optional.empty(),Optional.empty(),Optional.empty(),Set.of());
 }
 /** Restriction selects among genuinely generated candidates; it changes neither roles,
  * eligibility nor the validated catalogue. Requested IDs are preserved for chain children. */
 public DecisionProposal evaluate(DecisionInput input,Optional<DefinitionId> only,
   Optional<EventInstanceId> requestedEvent,Optional<RegionId> region,Set<PlayerId> requiredParticipants) {
  actor();Objects.requireNonNull(input);Objects.requireNonNull(only);Objects.requireNonNull(requestedEvent);
  Objects.requireNonNull(region);requiredParticipants=Set.copyOf(requiredParticipants);
  if(store.readOnlySafe()||!world.equals(input.state().world().worldId())||
     !catalog.equals(input.catalog()))throw new IllegalStateException("actual catalogue input unavailable");
  if(requestedEvent.isPresent()&&only.isEmpty())throw new IllegalArgumentException("requested event needs one definition");
  input=enrichInput(checkedInput(input));
  if(only.isPresent()||requestedEvent.isPresent()||region.isPresent()||!requiredParticipants.isEmpty()) {
   var restriction="catalogue-restriction-v1\\0"+input.seed()+"\\0"+only+"\\0"+requestedEvent+"\\0"+region+"\\0"+
    requiredParticipants.stream().sorted().map(p->p.value().toString()).toList();
   var key=UUID.nameUUIDFromBytes(restriction.getBytes(StandardCharsets.UTF_8));
   input=new DecisionInput(input.context(),input.memory(),input.catalog(),input.config(),input.requests(),input.chains(),
    key.getMostSignificantBits()^key.getLeastSignificantBits(),input.state(),input.advisorySnapshot());
  }
  final DecisionInput frozen=input;
  // Both passes are pure. The first reveals exact role-bound candidate IDs produced after
  // pacing's deterministic draws. Only the second proposal is submitted to J04 by the caller.
  var known=new WorldDirector(mode).evaluate(input);
  var previous=captures.get(known.proposalId());
  if(previous!=null) {
   if(!previous.input().equals(input))throw new IllegalStateException("decision input ID conflict");
   return previous.proposal();
  }
  if(captures.size()>=maximumCaptures)throw new IllegalStateException("catalogue capture queue full");
  Map<CandidateId,List<PlanningSnapshot>> snapshots=new HashMap<>();
  Map<CandidateId,PhysicalSiteSelection> siteSelections=new HashMap<>();
  Map<OperationToken,PreparedEffect> effects=new HashMap<>();
  Set<EventInstanceId> requestedUsed=new HashSet<>();
  for(var candidate:known.candidates()) {
   if(only.filter(id->!id.equals(candidate.definition().id())).isPresent())continue;
   if(region.isPresent()&&!candidate.scopes().scopes().contains(new ScopeRef(ScopeKind.REGION,region.orElseThrow().value())))continue;
   Set<PlayerId> players=new HashSet<>();candidate.roles().roles().values().forEach(players::addAll);
   if(!players.containsAll(requiredParticipants))continue;
   if(candidate.reasons().stream().anyMatch(reason->reason!=fr.proutlost.director.api.error.ReasonCode.CAPABILITY_MISSING))continue;
   var driver=drivers.get(candidate.definition().id());if(driver==null||!driver.available())continue;
   var event=requestedEvent.orElseGet(()->instance(candidate,frozen));
   if(requestedEvent.isPresent()&&requestedUsed.contains(event))continue;
   try {
    var preview=driver.preview(candidate,input,event);
    if(preview.snapshots().size()!=input.catalog().events().get(candidate.definition().id()).steps().size())
     throw new IllegalArgumentException("all original steps must be captured");
    if(physicalSites!=null&&physicalSites.requires(candidate.definition())){
     var site=physicalSites.resolve(candidate,input,event,List.copyOf(preview.effects().values()));
     if(site.isEmpty())continue; // J03 receives no executable plan for a genuinely unavailable D0 site.
     siteSelections.put(candidate.id(),site.orElseThrow());
    }
    for(var entry:preview.effects().entrySet())
     if(effects.putIfAbsent(entry.getKey(),entry.getValue())!=null)throw new IllegalArgumentException("duplicate captured operation");
    previewDeferrals.remove(candidate.definition().id());
    snapshots.put(candidate.id(),preview.snapshots());if(requestedEvent.isPresent())requestedUsed.add(event);
   }catch(RuntimeException notReady) {
    String message=notReady.getClass().getSimpleName()+": "+String.valueOf(notReady.getMessage()).replace('\n',' ').replace('\r',' ');
    if(message.length()>320)message=message.substring(0,320);
    if(!message.equals(previewDeferrals.put(candidate.definition().id(),message)))System.out.println("DIRECTOR_NATIVE_PREVIEW_DEFERRED definition="+candidate.definition().id().value()+" cause="+message);
    // A missing real anchor, habitat, audience, terrain or asset leaves this candidate
    // without a plan. J03 emits its existing explicit planning deferral; no alias is run.
   }
  }
  PlanningPort port=request->{
   var binding=request.snapshot().bindings().orElseThrow();
   var prepared=effects.get(binding.operationToken());
   if(prepared==null||!prepared.snapshot().equals(request.snapshot().snapshot())||
      !ObservedWeatherArguments.matches(request.step().args(),prepared.intent().typedArgs(),prepared.preconditions())||
      !prepared.intent().primitive().equals(request.step().primitive()))
    throw new IllegalArgumentException("native captured planning identity");
   return content==null?new PlannedPrimitive(binding,prepared):content.plan(request,prepared);
  };
  var proposal=new WorldDirector(mode,new CandidatePlanner(port,snapshots)).evaluate(input);
  if(proposal.selectedPlan().isPresent()&&physicalSites!=null&&physicalSites.requires(proposal.selectedPlan().orElseThrow().definition())){
   var plan=proposal.selectedPlan().orElseThrow();var selection=Objects.requireNonNull(siteSelections.remove(plan.candidateId()),"full D0 selection absent from exact planned candidate");
   physicalSiteOffers.put(proposal.proposalId(),physicalSites.pin(selection,plan));
  }
  if(physicalSites!=null)siteSelections.values().forEach(physicalSites::discard);
  captures.put(proposal.proposalId(),new Captured(input,proposal,Map.copyOf(effects),Map.copyOf(snapshots)));
  requestedEvent.ifPresent(event->latestQueuedDecision.put(event,proposal.proposalId()));
  return proposal;
 }
 /** Stage only already audited, still-authenticated plans. This does not call
  * a provider, create EVENT state, or bypass the ordinary admission gate. */
 public void stageEnvelope(DecisionProposal proposal,DirectorDecisionJournal journal,Optional<ChainId> parentChain,BooleanSupplier authorized){
  actor();if((mode!=ProviderMode.PRODUCTION&&(content==null||!content.declaredSynthetic()))||proposal.selectedPlan().isEmpty()||!journal.poll(proposal.proposalId()).mayProceedToAdmission())return;
  var captured=Objects.requireNonNull(captures.get(proposal.proposalId()));if(!captured.proposal().equals(proposal))throw new IllegalArgumentException("ready captured proposal changed");
  var event=proposal.selectedPlan().orElseThrow().effects().getFirst().instanceId();
  if(cancelledBeforeDriver.contains(event)||invokedEvents.contains(event))return;
  var selected=new SelectedEventStart(captured.input(),proposal,proposal.selectedPlan().orElseThrow(),parentChain,mode)
   .withLiveContext(live).withRequirementBinding(requirementBound);
  var physical=physicalSiteGate(proposal.proposalId(),proposal.selectedPlan().orElseThrow());if(physical.status()!=GateStatus.READY)return;
  selected=physicalSite(proposal.proposalId(),selected);
  globalStaging.accept(selected,authorized);
 }
 public Dispatch beginAudited(DecisionProposal proposal,DirectorDecisionJournal journal,Optional<ChainId> parentChain) {
  actor();Objects.requireNonNull(proposal);Objects.requireNonNull(journal);Objects.requireNonNull(parentChain);
  var captured=Objects.requireNonNull(captures.get(proposal.proposalId()),"selected native capture absent");
  if(!captured.proposal().equals(proposal))throw new IllegalArgumentException("selected proposal changed");
  var audit=journal.poll(proposal.proposalId());
  if(!audit.mayProceedToAdmission())return new Dispatch(proposal.proposalId(),
   audit.state()==DirectorDecisionJournal.State.WAITING?Stage.WAITING_FOR_AUDIT:Stage.REJECTED,Optional.empty(),"J04 audit not durable");
  if(proposal.selectedPlan().isEmpty())return new Dispatch(proposal.proposalId(),Stage.NO_PLAN,Optional.empty(),"no genuinely eligible native plan");
  var plan=proposal.selectedPlan().orElseThrow();var event=plan.effects().getFirst().instanceId();
  if(cancelledBeforeDriver.contains(event)){
   var pending=preStartAborts.get(event);var outcome=pending==null?new AbortResult(AbortStatus.WAITING_FOR_ACK,event,"abort source not appended"):pollAbort(event,pending);
   return new Dispatch(proposal.proposalId(),outcome.status()==AbortStatus.DURABLE?Stage.REJECTED:Stage.WAITING_FOR_ABORT,Optional.of(event),outcome.detail());
  }
  var old=started.get(proposal.proposalId());
  if(old!=null)return new Dispatch(proposal.proposalId(),Stage.ALREADY_STARTED,Optional.of(old.eventId()),old.message());
  if(invokedDecisions.contains(proposal.proposalId())||invokedEvents.contains(event))return new Dispatch(proposal.proposalId(),Stage.REJECTED,Optional.of(event),"driver already invoked; uncertain/refused attempt is never replayed");
  var driver=drivers.get(plan.definition().id());
  if(store.readOnlySafe()||driver==null)return new Dispatch(proposal.proposalId(),Stage.REJECTED,Optional.empty(),"native driver unavailable");
  if(!driver.available())return new Dispatch(proposal.proposalId(),Stage.WAITING_FOR_REQUIRE_BINDING,Optional.of(event),"WAIT_NATIVE_DRIVER_AVAILABLE");
  var physical=physicalSiteGate(proposal.proposalId(),plan);
  if(physical.status()!=GateStatus.READY)return new Dispatch(proposal.proposalId(),physical.status()==GateStatus.WAITING?Stage.WAITING_FOR_REQUIRE_BINDING:Stage.REJECTED,Optional.of(event),physical.detail());
  var selected=new SelectedEventStart(captured.input(),proposal,plan,parentChain,mode).withCompletionHook(completion).withAbortHook(abort).withLedgerStore(store).withClock(()->new Tick(fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server))).withLiveContext(live).withRequirementBinding(requirementBound);
  selected=physicalSite(proposal.proposalId(),selected);
  if(content!=null)selected=selected.withAdmissionRouter(content::admit);
  var beforeInvoke=driver.preflight(selected);
  if(beforeInvoke.status()!=GateStatus.READY)return new Dispatch(proposal.proposalId(),beforeInvoke.status()==GateStatus.WAITING?Stage.WAITING_FOR_REQUIRE_BINDING:Stage.REJECTED,Optional.of(event),beforeInvoke.detail());
  var admission=gate.apply(selected,journal);
  if(admission.status()!=GateStatus.READY)return new Dispatch(proposal.proposalId(),
   admission.status()==GateStatus.WAITING?Stage.WAITING_FOR_REQUIRE_BINDING:Stage.REJECTED,Optional.of(selected.eventId()),admission.detail());
  try{selected.refresh();}catch(SelectedEventStart.ContextUnavailable wait){
   return new Dispatch(proposal.proposalId(),Stage.WAITING_FOR_REQUIRE_BINDING,
      Optional.of(selected.eventId()),"WAIT_LIVE_CONTEXT_BEFORE_DRIVER: "+wait.getMessage());
  }
  if(content!=null){var guardedEvent=selected.eventId();selected=selected.withLiveContext(()->content.envelopes().forwardAllowed(guardedEvent)?live.get():Optional.empty());}
  // Set BEFORE invocation: a throw or refusal after entering the driver has no no-effect authority.
  invokedDecisions.add(proposal.proposalId());invokedEvents.add(selected.eventId());
  var result=driver.begin(selected);
  if(!result.eventId().equals(selected.eventId()))throw new IllegalStateException("driver discarded selected instance ID");
  if(result.accepted())started.put(proposal.proposalId(),result);
  return new Dispatch(proposal.proposalId(),result.accepted()?Stage.STARTED:Stage.REJECTED,Optional.of(result.eventId()),result.message());
 }
 public Dispatch beginAudited(DecisionInput captured,DecisionProposal proposal,DirectorDecisionJournal journal,Optional<ChainId> parentChain) {
  actor();if(!capturedInput(proposal.proposalId()).filter(captured::equals).isPresent())throw new IllegalArgumentException("exact native captured input required");
  return beginAudited(proposal,journal,parentChain);
 }
 /** Register ONCE at actual manual/chain enqueue, or from an actual selected auto plan.
  * The caller supplies its real captured consent; this is metadata identity, never eligibility.
  */
 public void registerQueuedIntent(QueuedIntent intent){
  actor();Objects.requireNonNull(intent);
  if(!intent.session().equals(store.session())||!intent.session().worldId().equals(world)
      ||!catalog.events().containsKey(intent.definition()))throw new IllegalArgumentException("queued intent world/session/definition");
  if(intent.parentChain().isPresent()&&intent.region().isEmpty())throw new IllegalArgumentException("chain needs its real region");
  boolean observed=server.getPlayerList().getPlayer(intent.actor().value())!=null;
  if(!observed)observed=captures.values().stream().anyMatch(capture->capture.proposal().selectedPlan().filter(plan->
    plan.effects().getFirst().instanceId().equals(intent.eventId())&&plan.definition().id().equals(intent.definition())
    &&plan.roles().roles().values().stream().anyMatch(players->players.contains(intent.actor()))
    &&intent.region().map(region->plan.scopes().scopes().contains(new ScopeRef(ScopeKind.REGION,region.value()))).orElse(true)).isPresent());
  if(!observed)throw new IllegalArgumentException("actual queued actor absent from player list and captured plan");
  var old=queuedIntents.get(intent.eventId());
  if(old!=null){if(!old.equals(intent))throw new IllegalArgumentException("queued intent identity changed");return;}
  if(invokedEvents.contains(intent.eventId())||cancelledBeforeDriver.contains(intent.eventId()))throw new IllegalStateException("queued event already invoked/cancelled");
  if(queuedIntents.size()>=maximumCaptures)throw new IllegalStateException("queued intent capacity");
  queuedIntents.put(intent.eventId(),intent);
  captures.values().stream().filter(capture->capture.proposal().selectedPlan().filter(plan->
    plan.effects().getFirst().instanceId().equals(intent.eventId())&&plan.definition().id().equals(intent.definition())).isPresent())
    .reduce((before,latest)->latest).ifPresent(capture->latestQueuedDecision.put(intent.eventId(),capture.proposal().proposalId()));
 }
 /** Cancel a real queued request without inventing an event when J03 had no plan. */
 public AbortResult abortQueuedIntent(EventInstanceId event,Optional<DecisionProposal> latestProposal,
     DirectorDecisionJournal journal,String reason){
  actor();Objects.requireNonNull(latestProposal);Objects.requireNonNull(journal);Objects.requireNonNull(reason);
  var intent=queuedIntents.get(event);if(intent==null)return new AbortResult(AbortStatus.REJECTED,event,"queued intent was never registered in this boot");
  var existing=preStartAborts.get(event);if(existing!=null)return pollAbort(event,existing);
  if(!intent.session().equals(store.session()))return new AbortResult(AbortStatus.REJECTED,event,"queued WAL session changed");
  if(invokedEvents.contains(event))return new AbortResult(AbortStatus.DRIVER_INVOKED,event,"driver invocation makes no-effect proof unavailable");
  cancelledBeforeDriver.add(event);
  Captured captured=null;
  if(latestProposal.isPresent()){
   var proposal=latestProposal.orElseThrow();captured=captures.get(proposal.proposalId());
   if(captured==null||!captured.proposal().equals(proposal)||!proposal.proposalId().equals(latestQueuedDecision.get(event)))
    return new AbortResult(AbortStatus.REJECTED,event,"latest actual queued capture/audit required");
   var audit=journal.poll(proposal.proposalId());
   if(!audit.mayProceedToAdmission())return new AbortResult(audit.state()==DirectorDecisionJournal.State.WAITING?
      AbortStatus.WAITING_FOR_AUDIT:AbortStatus.REJECTED,event,"queued audit not durable");
   if(proposal.selectedPlan().isPresent()){
    if(!proposal.selectedPlan().orElseThrow().effects().getFirst().instanceId().equals(event))
     return new AbortResult(AbortStatus.REJECTED,event,"selected event differs from queued intent");
    return abortBeforeDriver(proposal,journal,intent.parentChain(),reason);
   }
  }else if(latestQueuedDecision.containsKey(event))return new AbortResult(AbortStatus.REJECTED,event,"known queued decision must not be omitted");
  var payload=new HashMap<String,JsonValue>();payload.put("source",new JsonValue.Text("CATALOGUE_REQUEST"));
  payload.put("definition",new JsonValue.Text(intent.definition().value()));payload.put("requested_event",new JsonValue.Text(event.value().toString()));
  payload.put("actor",new JsonValue.Text(intent.actor().value().toString()));payload.put("reason",new JsonValue.Text(reason));
  payload.put("no_driver_start",new JsonValue.Bool(true));payload.put("no_physical_effect",new JsonValue.Bool(true));
  payload.put("provider_mode",new JsonValue.Text(mode.name()));
  payload.put("proof_source",new JsonValue.Text("EXECUTOR_PRESTART_CANCEL"));
  payload.put("boot_epoch",new JsonValue.Text(intent.session().bootEpoch().value()+""));
  payload.put("wal_session",new JsonValue.Text(intent.session().authoritySession().value().toString()));
  intent.parentChain().ifPresent(chain->payload.put("parent_chain",new JsonValue.Text(chain.value().toString())));
  intent.region().ifPresent(region->payload.put("region",new JsonValue.Text(region.value().toString())));
  if(captured==null)payload.put("audit_not_submitted",new JsonValue.Bool(true));
  else{payload.put("decision",new JsonValue.Text(captured.proposal().proposalId().value().toString()));payload.put("audit_status",new JsonValue.Text("NO_PLAN_DURABLE"));}
  var scopes=new HashSet<ScopeRef>();scopes.add(new ScopeRef(ScopeKind.WORLD,world.value()));scopes.add(new ScopeRef(ScopeKind.PLAYER,intent.actor().value()));
  intent.region().ifPresent(region->scopes.add(new ScopeRef(ScopeKind.REGION,region.value())));
  var fact=new DirectorFact(new FactId(UUID.nameUUIDFromBytes(("catalogue-request-aborted-v1\0"+event.value()).getBytes(StandardCharsets.UTF_8))),
    new FactType("proutlost_director:catalogue_request_aborted"),scopes.stream().sorted(Comparator.comparing(ScopeRef::toString)).toList(),
    Optional.of(event),EvidenceStatus.CONFIRMED,payload,true,Optional.empty());
  var envelope=new DirectorEnvelope(1,new MessageId(UUID.nameUUIDFromBytes(("catalogue-request-abort-outbox-v1\0"+event.value()).getBytes(StandardCharsets.UTF_8))),
    world,new ResourceId("proutlost_director:native_catalogue"),"request/"+event.value()+"/abort",new ScopeRef(ScopeKind.WORLD,world.value()),
    new CorrelationId(event.value()),Optional.empty(),new Sequence(1),Optional.empty(),new Tick(server.overworld().getGameTime()),PayloadType.DirectorFact,1,new FactPayload(fact));
  return submitAbort(event,List.of(new DurableFact(fact),new DurableOutbox(envelope)));
 }
 /** Only this executor's known RAM path before ANY driver invocation can make this proof. */
 public AbortResult abortBeforeDriver(DecisionProposal proposal,DirectorDecisionJournal journal,Optional<ChainId> parentChain,String reason){
  actor();Objects.requireNonNull(proposal);Objects.requireNonNull(journal);Objects.requireNonNull(parentChain);Objects.requireNonNull(reason);
  var captured=captures.get(proposal.proposalId());
  if(captured==null||!captured.proposal().equals(proposal)||proposal.selectedPlan().isEmpty())throw new IllegalArgumentException("actual selected capture required");
  var plan=proposal.selectedPlan().orElseThrow();var event=plan.effects().getFirst().instanceId();
  var existing=preStartAborts.get(event);if(existing!=null)return pollAbort(event,existing);
  if(invokedDecisions.contains(proposal.proposalId())||invokedEvents.contains(event))return new AbortResult(AbortStatus.DRIVER_INVOKED,event,"driver was invoked; no no-effect inference");
  cancelledBeforeDriver.add(event);
  var audit=journal.poll(proposal.proposalId());
  if(!audit.mayProceedToAdmission())return new AbortResult(audit.state()==DirectorDecisionJournal.State.WAITING?
     AbortStatus.WAITING_FOR_AUDIT:AbortStatus.REJECTED,event,"actual selected audit not durable");
  var intent=queuedIntents.get(event);
  if(intent!=null&&(!intent.session().equals(store.session())||!intent.definition().equals(plan.definition().id())||!intent.parentChain().equals(parentChain)))
   return new AbortResult(AbortStatus.REJECTED,event,"selected/queued identity changed");
  var selected=new SelectedEventStart(captured.input(),proposal,plan,parentChain,mode).withCompletionHook(completion).withAbortHook(abort);
  // A scheduled REQUIRE needs its real binding even when cancellation precedes the normal gate.
  // This gate performs metadata only and must remain available while physical execution is paused.
  if(selected.definition().eligibility() instanceof RequirementPending&&!requirementBound.test(selected)){
   var binding=abortBindingGate.apply(selected);
   if(binding.status()!=GateStatus.READY)return new AbortResult(binding.status()==GateStatus.WAITING?
      AbortStatus.WAITING_FOR_ACK:AbortStatus.REJECTED,event,"REQUIRE binding before abort: "+binding.detail());
  }
  var proof=new HashMap<String,JsonValue>();proof.put("no_driver_start",new JsonValue.Bool(true));proof.put("no_physical_effect",new JsonValue.Bool(true));
  proof.put("provider_mode",new JsonValue.Text(mode.name()));
  proof.put("proof_source",new JsonValue.Text("EXECUTOR_PRESTART_CANCEL"));proof.put("audit_decision",new JsonValue.Text(proposal.proposalId().value().toString()));
  parentChain.ifPresent(chain->proof.put("parent_chain",new JsonValue.Text(chain.value().toString())));
  return submitAbort(event,selected.abortRecords(reason,proof));
 }
 private AbortResult submitAbort(EventInstanceId event,List<DurableRecord> records){
  cancelledBeforeDriver.add(event); // Blocks every later begin, including while the metadata ACK is pending.
  var pending=new PreStartAbort();pending.session=store.session();
  pending.batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),pending.session.storeGeneration(),Map.of(),records,List.of());
  preStartAborts.put(event,pending);
  try{pending.ack=store.append(pending.batch).toCompletableFuture();}
  catch(RuntimeException error){pending.ack=CompletableFuture.failedFuture(error);}
  return pollAbort(event,pending);
 }
 private AbortResult pollAbort(EventInstanceId event,PreStartAbort pending){
  if(pending.ack==null||!pending.ack.isDone())return new AbortResult(AbortStatus.WAITING_FOR_ACK,event,"actual abort source ACK pending");
  final CommitAck ack;try{ack=pending.ack.join();}catch(CompletionException error){return new AbortResult(AbortStatus.REJECTED,event,"abort source delivery uncertain: "+error.getCause());}
  if(ack.ackStatus()!=AckStatus.DURABLE||!pending.session.equals(store.session())||!ack.transactionId().equals(pending.batch.transactionId())
      ||!ack.transactionHash().equals(pending.batch.transactionHash())||!ack.storeGeneration().equals(pending.session.storeGeneration())
      ||ack.durableSequence().value()==0||!ack.permitAttempt().equals(pending.batch.permitAttempt())
      ||!ack.forcedBlobs().equals(DurableBlobReferences.collect(pending.batch.records())))
   return new AbortResult(AbortStatus.REJECTED,event,"abort source ACK unavailable or mismatched");
  terminal.put(event,new EventProgress(event,EventStage.ABORTED,"actual pre-driver abort source durable"));
  return new AbortResult(AbortStatus.DURABLE,event,"actual pre-driver abort durable; no provider invoked");
 }
 public void observe(DurableRecord record) {
  actor();
  if(record instanceof DurableFact row&&row.value().eventId().isPresent()&&row.value().durable()&&
     row.value().evidenceStatus()==EvidenceStatus.CONFIRMED) {
   var fact=row.value();var event=fact.eventId().orElseThrow();
   for(var capture:captures.values())if(capture.proposal().selectedPlan().isPresent()) {
    var plan=capture.proposal().selectedPlan().orElseThrow();
    if(!plan.effects().getFirst().instanceId().equals(event))continue;
    var definition=capture.input().catalog().events().get(plan.definition().id());
    if(fact.factType().equals(definition.completion().factType())&&
       fact.payload().getOrDefault("source",JsonValue.Nil.INSTANCE).equals(new JsonValue.Text("CATALOGUE_SELECTED")))
     terminal.put(event,new EventProgress(event,EventStage.COMPLETED,"actual original completion fact durable"));
    else if(fact.factType().equals(new FactType("proutlost_director:catalogue_event_aborted"))||fact.factType().equals(new FactType("proutlost_director:catalogue_request_aborted")))
     terminal.put(event,new EventProgress(event,EventStage.ABORTED,"actual abort fact durable"));
   }
  }
 }
 public Optional<EventProgress> progress(EventInstanceId event) {
  actor();if(terminal.containsKey(event))return Optional.of(terminal.get(event));for(var driver:new HashSet<>(drivers.values())) {var result=driver.progress(event);if(result.isPresent())return result;}
  return Optional.empty();
 }
 /** Release only bounded RAM registrations after actual terminal progress/source ACK.
  * Invocation/cancellation tombstones remain for this boot; the durable history is never deleted.
  */
 public void retireQueuedIntent(EventInstanceId event){
  actor();var pending=preStartAborts.get(event);
  if(pending!=null&&pollAbort(event,pending).status()!=AbortStatus.DURABLE)throw new IllegalStateException("abort source not durably resolved");
  var progress=progress(event);
  if(progress.isEmpty()||progress.get().stage()==EventStage.RUNNING||progress.get().stage()==EventStage.RECOVERING)
   throw new IllegalStateException("queued intent still active or uncertain");
  queuedIntents.remove(event);latestQueuedDecision.remove(event);preStartAborts.remove(event);
 }
 public void retire(DecisionId id) {
  actor();var result=started.get(id);
  var captured=captures.get(id);
  var selected=captured==null?Optional.<ExecutionPlan>empty():captured.proposal().selectedPlan();
  EventInstanceId invoked=result!=null?result.eventId():selected.map(p->p.effects().getFirst().instanceId()).orElse(null);
  if((result!=null||invokedDecisions.contains(id)||invoked!=null&&invokedEvents.contains(invoked))
      &&(invoked==null||progress(invoked).filter(p->p.stage()==EventStage.COMPLETED||p.stage()==EventStage.ABORTED).isEmpty()))
   throw new IllegalStateException("invoked catalogue event is active or uncertain; retain every pin");
  var physical=physicalSiteOffers.get(id);
  if(physical!=null){physicalSiteJournal.retire(physical.pin());physicalSites.retire(physical.pin());physicalSiteOffers.remove(id);}
  if(captured!=null&&captured.proposal().selectedPlan().isPresent()){
   var event=captured.proposal().selectedPlan().orElseThrow().effects().getFirst().instanceId();
   if(queuedIntents.containsKey(event)&&progress(event).map(p->p.stage()==EventStage.COMPLETED||p.stage()==EventStage.ABORTED).orElse(false))retireQueuedIntent(event);
   if(content!=null&&(!invokedEvents.contains(event)||progress(event).map(p->p.stage()==EventStage.COMPLETED||p.stage()==EventStage.ABORTED).orElse(false)))content.retire(event);
  }
  captures.remove(id);started.remove(id);
 }
}
