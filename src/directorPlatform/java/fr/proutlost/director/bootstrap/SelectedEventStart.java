package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.PhysicalSiteResolverPort;
import fr.proutlost.director.api.port.PhysicalSitePinJournalPort;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.Predicate;
import java.util.function.BiFunction;
import fr.proutlost.director.runtime.AdmissionService;
import fr.proutlost.director.engine.*;
import fr.proutlost.director.runtime.CatalogueEventLedger;
import fr.proutlost.director.persistence.JournalStore;

/** Exact selected catalogue identity. This object carries no J05 permit or reservation. */
public final class SelectedEventStart {
 public static final class ContextUnavailable extends IllegalStateException {
  public ContextUnavailable(String detail){super(detail);}
 }
 @FunctionalInterface public interface CompletionHook {
  List<DurableRecord> records(SelectedEventStart selected,Map<String,JsonValue> actualEvidence);
 }
 @FunctionalInterface public interface AbortHook {
  List<DurableRecord> records(SelectedEventStart selected,String reason,Map<String,JsonValue> actualEvidence);
 }
 private record PhysicalSiteGuard(PhysicalSitePin pin,PhysicalSiteResolverPort resolver,PhysicalSitePinJournalPort journal) {}
 private final PhysicalSiteGuard physicalSite;
 private final ProviderMode mode;
 private final DecisionInput captured;
 private final DecisionProposal proposal;
 private final ExecutionPlan plan;
 private final Optional<ChainId> parentChain;
 private final CompletionHook completion;
 private final AbortHook abort;
 private final JournalStore ledgerStore;
 private final Supplier<Tick> clock;
 private final Supplier<Optional<DecisionInput>> live;
 private final Predicate<SelectedEventStart> requirementBound;
 private final BiFunction<AdmissionService,AdmissionRequest,AdmissionResult> admissionRouter;
 public SelectedEventStart(DecisionInput captured,DecisionProposal proposal,ExecutionPlan plan,Optional<ChainId> parentChain) {
  this(captured,proposal,plan,parentChain,ProviderMode.TEST_ONLY);
 }
 public SelectedEventStart(DecisionInput captured,DecisionProposal proposal,ExecutionPlan plan,Optional<ChainId> parentChain,ProviderMode mode) {
  this(captured,proposal,plan,parentChain,(selected,evidence)->selected.originalCompletionRecords(evidence),(selected,reason,evidence)->selected.originalAbortRecords(reason,evidence),null,()->{throw new IllegalStateException("actual Director clock not bound");},Optional::empty,selected->false,mode,null,null);
 }
 private SelectedEventStart(DecisionInput captured,DecisionProposal proposal,ExecutionPlan plan,
   Optional<ChainId> parentChain,CompletionHook completion,AbortHook abort,JournalStore ledgerStore,Supplier<Tick> clock,Supplier<Optional<DecisionInput>> live,Predicate<SelectedEventStart> requirementBound,ProviderMode mode,BiFunction<AdmissionService,AdmissionRequest,AdmissionResult> admissionRouter,PhysicalSiteGuard physicalSite) {
  this.physicalSite=physicalSite;this.admissionRouter=admissionRouter;this.mode=Objects.requireNonNull(mode);this.captured=Objects.requireNonNull(captured);this.proposal=Objects.requireNonNull(proposal);
  this.plan=Objects.requireNonNull(plan);this.parentChain=Objects.requireNonNull(parentChain);
  this.completion=Objects.requireNonNull(completion);this.abort=Objects.requireNonNull(abort);this.ledgerStore=ledgerStore;this.clock=Objects.requireNonNull(clock);this.live=Objects.requireNonNull(live);this.requirementBound=Objects.requireNonNull(requirementBound);
  if(!proposal.selectedPlan().equals(Optional.of(plan)) || !plan.scopes().worldId().equals(captured.state().world().worldId()))
   throw new IllegalArgumentException("selected plan identity");
  var definition=definition();
  if(mode==ProviderMode.PRODUCTION&&(captured.config().calibration()!=Calibration.APPROVED
    ||captured.catalog().events().values().stream().anyMatch(d->d.calibration()!=Calibration.APPROVED)
    ||captured.catalog().chains().values().stream().anyMatch(d->d.calibration()!=Calibration.APPROVED)
    ||captured.catalog().primitives().values().stream().anyMatch(d->d.mode()!=ProviderMode.PRODUCTION)
    ||!captured.state().world().configHash().equals(captured.config().provenanceHash())
    ||!captured.state().world().catalogHash().equals(captured.catalog().hash())))
   throw new IllegalArgumentException("selected production provenance");
  var reference=new DefinitionRef(definition.id(),definition.contentRevision(),
   BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(definition)));
  if(!reference.equals(plan.definition()) || plan.effects().size()!=definition.steps().size() || plan.effects().isEmpty())
   throw new IllegalArgumentException("selected catalogue/step count");
  Set<OperationToken> operations=new HashSet<>();
  for(int i=0;i<plan.effects().size();i++) {
   var effect=plan.effects().get(i);var step=definition.steps().get(i);
   if(!effect.primitive().equals(step.primitive()) || !ObservedWeatherArguments.matches(step.args(),effect.typedArgs(),plan.readSet()) ||
      !effect.instanceId().equals(eventId()) || !operations.add(effect.operationToken()))
    throw new IllegalArgumentException("selected step identity");
  }
 }
 public List<PhysicalResourceClaim> physicalResources(){return physicalSite==null?List.of():physicalSite.pin().resources();}
 public ProviderMode mode(){return mode;}
 public SelectedEventStart withPhysicalSite(PhysicalSitePin pin,PhysicalSiteResolverPort resolver,PhysicalSitePinJournalPort journal){
  Objects.requireNonNull(pin).requirePlan(plan);var guard=new PhysicalSiteGuard(pin,Objects.requireNonNull(resolver),Objects.requireNonNull(journal));
  if(physicalSite!=null&&!physicalSite.equals(guard))throw new IllegalArgumentException("selected physical site guard substitution");
  return new SelectedEventStart(captured,proposal,plan,parentChain,completion,abort,ledgerStore,clock,live,requirementBound,mode,admissionRouter,guard);
 }
 private void requirePhysicalForward(Optional<EffectIntent> effect){
  if(physicalSite==null)return;
  if(!physicalSite.journal().durable(physicalSite.pin()))throw new ContextUnavailable("full D0 site plan pin not J04 durable");
  physicalSite.resolver().requireCurrent(physicalSite.pin(),plan,effect);
 }
 public void requirePhysicalForward(EffectIntent effect){requirePhysicalForward(Optional.of(Objects.requireNonNull(effect)));}
 public boolean physicalForwardValid(EffectIntent effect){try{requirePhysicalForward(effect);return true;}catch(RuntimeException stale){return false;}}

 public SelectedEventStart withAdmissionRouter(BiFunction<AdmissionService,AdmissionRequest,AdmissionResult> router){
  return new SelectedEventStart(captured,proposal,plan,parentChain,completion,abort,ledgerStore,clock,live,requirementBound,mode,Objects.requireNonNull(router),physicalSite);
 }
 public AdmissionResult admit(AdmissionService service,AdmissionRequest request){
  Objects.requireNonNull(service);Objects.requireNonNull(request);
  if(service.mode()!=mode)throw new IllegalArgumentException("selected admission provider mode changed");
  if(admissionRouter!=null)return admissionRouter.apply(service,request);
  if(mode==ProviderMode.PRODUCTION)throw new IllegalStateException("production admission router absent");
  return service.admit(request);
 }
 public DecisionInput captured(){return captured;}
 public DecisionProposal proposal(){return proposal;}
 public ExecutionPlan plan(){return plan;}
 public Optional<ChainId> parentChain(){return parentChain;}
 public EventInstanceId eventId(){return plan.effects().getFirst().instanceId();}
 public EventDefinition definition(){return Objects.requireNonNull(captured.catalog().events().get(plan.definition().id()),"actual definition absent");}
 public EffectIntent effect(String stepId) {
  var steps=definition().steps();
  for(int i=0;i<steps.size();i++)if(steps.get(i).id().equals(stepId)){var effect=plan.effects().get(i);requirePhysicalForward(effect);return effect;}
  throw new IllegalArgumentException("unknown selected step");
 }
 public Set<PlayerId> participants() {
  Set<PlayerId> result=new HashSet<>();plan.roles().roles().values().forEach(result::addAll);return Set.copyOf(result);
 }
 public Optional<RegionId> region() {
  var regions=plan.scopes().scopes().stream().filter(s->s.kind()==ScopeKind.REGION).map(s->new RegionId(s.id())).toList();
  if(regions.size()>1)throw new IllegalArgumentException("ambiguous selected region");
  return regions.stream().findFirst();
 }
 public SelectedEventStart withCompletionHook(CompletionHook hook) {
  return new SelectedEventStart(captured,proposal,plan,parentChain,hook,abort,ledgerStore,clock,live,requirementBound,mode,admissionRouter,physicalSite);
 }
 public SelectedEventStart withAbortHook(AbortHook hook) {
  return new SelectedEventStart(captured,proposal,plan,parentChain,completion,hook,ledgerStore,clock,live,requirementBound,mode,admissionRouter,physicalSite);
 }
 public SelectedEventStart withLedgerStore(JournalStore store) {
  NativeCatalogueLedgerState.keysBeforeApply(selection(),captured);
  return new SelectedEventStart(captured,proposal,plan,parentChain,completion,abort,Objects.requireNonNull(store),clock,live,requirementBound,mode,admissionRouter,physicalSite);
 }
 public SelectedEventStart withClock(Supplier<Tick> source){
  return new SelectedEventStart(captured,proposal,plan,parentChain,completion,abort,ledgerStore,Objects.requireNonNull(source),live,requirementBound,mode,admissionRouter,physicalSite);
 }
 public DecisionInput ledgerCurrent(){
  if(ledgerStore==null)throw new IllegalStateException("actual catalogue ledger store not bound");
  return NativeCatalogueLedgerState.current(ledgerStore,selection(),captured);
 }
 public SelectedEventStart withLiveContext(Supplier<Optional<DecisionInput>> source) {
  return new SelectedEventStart(captured,proposal,plan,parentChain,completion,abort,ledgerStore,clock,source,requirementBound,mode,admissionRouter,physicalSite);
 }
 public SelectedEventStart withRequirementBinding(Predicate<SelectedEventStart> check) {
  return new SelectedEventStart(captured,proposal,plan,parentChain,completion,abort,ledgerStore,clock,live,check,mode,admissionRouter,physicalSite);
 }
 /** Re-read the actual actor-owned J01/J04 input after the decision audit and before
  * each native prepare. Original selected IDs/roles/footprints are unchanged.
  * Uniqueness is adjudicated by admission, not re-consumed by this read. */
 public boolean forwardContextAvailable(){return live.get().isPresent();}
 public DecisionInput current() {
  var current=live.get().orElseThrow(()->new ContextUnavailable("fresh native selected context unavailable"));
  if(!current.catalog().equals(captured.catalog()) ||
     mode==ProviderMode.PRODUCTION&&!current.config().equals(captured.config()) ||
     !current.state().world().worldId().equals(plan.scopes().worldId()))
   throw new IllegalStateException("selected world/catalogue changed");
  return current;
 }
 public DecisionInput refresh() {
  requirePhysicalForward(Optional.empty());
  var current=current();
  if(current.state().world().runControl()!=RunControl.RUNNING ||
     current.state().world().healthMode()==HealthMode.CRITICAL ||
     current.state().world().healthMode()==HealthMode.READ_ONLY_SAFE ||
     current.context().serverHealth()==HealthMode.CRITICAL ||
     current.context().serverHealth()==HealthMode.READ_ONLY_SAFE)
   throw new IllegalStateException("selected world no longer admitted");
  for(var scope:plan.scopes().scopes())
   if(!current.context().scopeRevisions().containsKey(scope)||!current.context().context().scopes().containsKey(scope))
    throw new IllegalStateException("selected scope no longer observed");
  var region=region();Optional<UUID> group=Optional.empty();
  if(definition().target().scope()==TargetScope.GROUP) {
   var matches=current.context().context().groups().values().stream()
    .filter(g->g.members().containsAll(participants())&&region.filter(g.regions()::contains).isPresent())
    .filter(g->participants().stream().allMatch(id->{
     var player=current.context().context().players().get(id);return player!=null&&player.groupIds().contains(g.groupId());
    })).toList();
   if(matches.size()!=1)throw new IllegalStateException("selected real group no longer unambiguous");
   group=Optional.of(matches.getFirst().groupId());
  }
  var generated=new CandidateGenerator.Generated(plan.candidateId(),definition(),plan.definition(),region,group,plan.roles(),plan.scopes(),true);
  if((definition().eligibility() instanceof RequirementPending?!requirementBound.test(this):
      new ConditionEvaluator().evaluate(definition().eligibility(),generated,current)!=ConditionEvaluator.Truth.TRUE) ||
     !new EligibilityEngine(mode).validateAudience(generated,participants(),current,eventId()).valid())
   throw new IllegalStateException("selected eligibility/audience changed");
  return current;
 }
 public List<DurableRecord> abortRecords(String reason,Map<String,JsonValue> actualEvidence) {
  if(!new JsonValue.Bool(true).equals(actualEvidence.get("no_physical_effect"))&&
     !new JsonValue.Bool(true).equals(actualEvidence.get("recovery_complete")))
   throw new IllegalArgumentException("confirmed abort needs real no-effect or completed-recovery proof");
  var rows=List.copyOf(abort.records(this,reason,Map.copyOf(actualEvidence)));
  var original=((DurableFact)originalAbortRecords(reason,actualEvidence).getFirst()).value();
  if(rows.stream().filter(DurableFact.class::isInstance).map(DurableFact.class::cast).map(DurableFact::value).noneMatch(original::equals))
   throw new IllegalArgumentException("abort hook omitted original confirmed abort fact");
  return rows;
 }
 public List<DurableRecord> originalAbortRecords(String reason,Map<String,JsonValue> actualEvidence) {
  var payload=new HashMap<String,JsonValue>(actualEvidence);
  payload.put("definition",new JsonValue.Text(definition().id().value()));
  payload.put("reason",new JsonValue.Text(reason));
  payload.put("source",new JsonValue.Text("CATALOGUE_SELECTED"));
  payload.put("provider_mode",new JsonValue.Text(mode.name()));
  payload.put("decision",new JsonValue.Text(proposal.proposalId().value().toString()));
  var id=new FactId(UUID.nameUUIDFromBytes(("catalogue-aborted-v1\0"+eventId().value()).getBytes(StandardCharsets.UTF_8)));
  return List.of(new DurableFact(new DirectorFact(id,new FactType("proutlost_director:catalogue_event_aborted"),
   plan.scopes().scopes().stream().sorted(Comparator.comparing(ScopeRef::toString)).toList(),Optional.of(eventId()),
   EvidenceStatus.CONFIRMED,payload,true,Optional.empty())));
 }
 /** Called only by the real provider's successful RESULT/END transaction builder.
  * A hook may add chain/memory records, and must preserve this exact original fact. */
 public List<DurableRecord> completionRecords(Map<String,JsonValue> actualEvidence) {
  var rows=List.copyOf(completion.records(this,Map.copyOf(actualEvidence)));
  var original=((DurableFact)originalCompletionRecords(actualEvidence).getFirst()).value();
  if(rows.stream().filter(DurableFact.class::isInstance).map(DurableFact.class::cast)
      .map(DurableFact::value).noneMatch(original::equals))
   throw new IllegalArgumentException("completion hook omitted original confirmed catalogue fact");
  return rows;
 }
 public CatalogueEventLedger.Selection selection(){return new CatalogueEventLedger.Selection(definition(),plan);}
 public List<UniqueUseRecord> reservation(ReservationToken token,Tick now){
  return CatalogueEventLedger.reserve(selection(),ledgerCurrent(),token,now);
 }
 public CatalogueEventLedger.Contribution effectContribution(PrepareRecord prepare,SliceResult actual,Tick now){
  return CatalogueEventLedger.effect(selection(),ledgerCurrent(),prepare,actual,now);
 }
 public CatalogueEventLedger.Contribution abortContribution(PrepareRecord prepare,SliceResult actual,Tick now){
  return CatalogueEventLedger.aborted(selection(),ledgerCurrent(),prepare,actual,now);
 }
 public CatalogueEventLedger.Contribution completionContribution(PrepareRecord prepare,Map<String,JsonValue> actualEvidence,Tick now){
  var rows=new ArrayList<DurableRecord>(completionRecords(actualEvidence));
  var fact=((DurableFact)originalCompletionRecords(actualEvidence).getFirst()).value();
  var ledger=CatalogueEventLedger.completed(selection(),ledgerCurrent(),prepare,fact,now);
  rows.addAll(ledger.records());return new CatalogueEventLedger.Contribution(rows,ledger.blobs());
 }
 public CatalogueEventLedger.Contribution completionContribution(Map<String,JsonValue> actualEvidence){
  return composedCompletionContribution(actualEvidence,clock.get());
 }
 public CatalogueEventLedger.Contribution composedCompletionContribution(Map<String,JsonValue> actualEvidence,Tick now){
  var rows=new ArrayList<DurableRecord>(completionRecords(actualEvidence));
  var fact=((DurableFact)originalCompletionRecords(actualEvidence).getFirst()).value();
  var ledger=CatalogueEventLedger.completedComposed(selection(),ledgerCurrent(),fact,now);
  rows.addAll(ledger.records());return new CatalogueEventLedger.Contribution(rows,ledger.blobs());
 }
 public CatalogueEventLedger.Contribution unchangedOutcomeContribution(PrepareRecord offer,DirectorFact actualClosed,AnchorRecord before,AnchorRecord after,Tick now){
  return CatalogueEventLedger.outcomeUnchanged(selection(),ledgerCurrent(),offer,actualClosed,before,after,now);
 }
 public List<DurableRecord> originalCompletionRecords(Map<String,JsonValue> actualEvidence) {
  var payload=new HashMap<String,JsonValue>(actualEvidence);
  payload.put("definition",new JsonValue.Text(definition().id().value()));
  payload.put("decision",new JsonValue.Text(proposal.proposalId().value().toString()));
  payload.put("source",new JsonValue.Text("CATALOGUE_SELECTED"));
  payload.put("provider_mode",new JsonValue.Text(mode.name()));
  parentChain.ifPresent(id->payload.put("parent_chain",new JsonValue.Text(id.value().toString())));
  var factId=new FactId(UUID.nameUUIDFromBytes(("catalogue-completion-v1\0"+eventId().value()).getBytes(StandardCharsets.UTF_8)));
  var subjects=plan.scopes().scopes().stream().sorted(Comparator.comparing(ScopeRef::toString)).toList();
  var fact=new DirectorFact(factId,definition().completion().factType(),subjects,Optional.of(eventId()),
   EvidenceStatus.CONFIRMED,payload,true,Optional.empty());
  return List.of(new DurableFact(fact));
 }
}
