package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.runtime.*;
import fr.proutlost.director.persistence.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

/** Real queued J03 admission for one native suboperation of an already selected event.
 * It preserves candidate/definition/roles/scopes and derives a stable subdecision ID.
 * Acceptance is only a reservation; the existing runner still forces PREPARE and consumes J05. */
public final class SelectedEventAdmission {
 private SelectedEventAdmission(){}
 public record Held(AdmissionService service,DecisionId decision,ReservationToken token) {}
 /** Current profile allowance after the caller-verified physical reservation projection.
  * Local ResourceArbiter reservations are still added separately by arbitrate(). */
 public static BudgetVector available(SelectedEventStart selected){return available(selected.refresh().state().budgets());}
 /** Own durable entity reservations already appear in the global projection.
  * Verify that projection before excluding this exact selected instance, so
  * an unobserved RAM cohort can never erase another instance's physical debit. */
 public static BudgetVector available(SelectedEventStart selected,JournalStore store,EventInstanceId own){
  if(!own.equals(selected.eventId()))throw new IllegalArgumentException("foreign selected budget exclusion");
  var input=selected.refresh();var world=selected.plan().scopes().worldId();var session=store.session();
  var index=selected.mode()==ProviderMode.PRODUCTION?store.residentBudgetAggregateKeys(4096):
   store.residentAggregateKeys(Set.of(AggregateKind.OWNERSHIP,AggregateKind.ENTITY_OWNERSHIP,AggregateKind.REGION),4096);
  if(index.isEmpty()||!index.get().session().equals(session)||index.get().keys().size()>1024)
   throw new SelectedEventStart.ContextUnavailable("current native budget heads unavailable");
  var keys=index.get().keys();if(keys.isEmpty())keys=Set.of(new AggregateKey(AggregateKind.WORLD,world.value()));
  var query=new StoreQuery(world,keys,new Sequence(0),Optional.empty(),1024);var read=store.view(query);
  ReadContracts.validate(read,query,session);
  if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().hasMore()||!store.session().equals(session)||
    !read.page().orElseThrow().lastDurableSequence().equals(index.get().cut()))
   throw new SelectedEventStart.ContextUnavailable("current native budget cut changed");
  var heads=read.page().orElseThrow().aggregates().stream().map(row->row.state()).toList();
  if(!CatalogueBudgetProjection.from(input.config(),heads).equals(input.state().budgets()))
   throw new SelectedEventStart.ContextUnavailable("current native budget projection changed");
  var others=heads.stream().filter(state->!(state instanceof EntityOwnershipAggregate e&&e.value().instanceId().equals(own))).toList();
  return available(CatalogueBudgetProjection.from(input.config(),others));
 }
 public static BudgetVector available(BudgetView view){
  var c=view.capacity();var r=view.reserved();
  if(!BudgetMath.fits(r,c))throw new IllegalStateException("current profile budget already overallocated");
  return new BudgetVector(c.tickMillis()-r.tickMillis(),c.globalIntensity()-r.globalIntensity(),c.regionalIntensity()-r.regionalIntensity(),
   c.liveEntities()-r.liveEntities(),c.persistentEntities()-r.persistentEntities(),c.mutationsInFlight()-r.mutationsInFlight(),
   c.queuedIoBytes()-r.queuedIoBytes(),c.clientUnits()-r.clientUnits(),c.payloadBytes()-r.payloadBytes(),c.navigationNodes()-r.navigationNodes());
 }

  public record OwnedBlockAllowance(BudgetVector projectedAvailable,BudgetVector available,
          Sequence cut,int creditedCells) {}
  /** A terrain operation may transfer its own already-durable PRE debit into
   * the unchanged full event reservation. All other committed debits remain. */
  public static OwnedBlockAllowance ownedBlockAllowance(SelectedEventStart selected,
          JournalStore store,EffectIntent intent,Map<ResourceKey,OwnershipRecord> owned) {
    selected.requirePhysicalForward(intent);
    if(!selected.plan().effects().contains(intent)||!(intent.typedArgs() instanceof Landslide))
      throw new IllegalArgumentException("exact selected terrain operation required");
    var cells=new HashSet<ResourceKey>();
    for(var p:intent.expectedFootprint().cells())
      cells.add(new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z()));
    if(!cells.equals(owned.keySet()))throw new IllegalArgumentException("complete selected owned PRE footprint required");
    var input=selected.refresh();var session=store.session();
    var index=selected.mode()==ProviderMode.PRODUCTION?store.residentBudgetAggregateKeys(4096):
      store.residentAggregateKeys(Set.of(AggregateKind.OWNERSHIP,AggregateKind.ENTITY_OWNERSHIP,AggregateKind.REGION),4096);
    if(index.isEmpty()||!index.get().session().equals(session)||index.get().keys().isEmpty()
        ||index.get().keys().size()>1024)
      throw new SelectedEventStart.ContextUnavailable("current owned block budget heads unavailable");
    var query=new StoreQuery(selected.plan().scopes().worldId(),index.get().keys(),new Sequence(0),Optional.empty(),1024);
    var read=store.view(query);ReadContracts.validate(read,query,session);
    if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().hasMore()
        ||!store.session().equals(session)
        ||!read.page().orElseThrow().lastDurableSequence().equals(index.get().cut()))
      throw new SelectedEventStart.ContextUnavailable("current owned block budget cut changed");
    var heads=read.page().orElseThrow().aggregates().stream().map(row->row.state()).toList();
    if(!CatalogueBudgetProjection.from(input.config(),heads).equals(input.state().budgets()))
      throw new SelectedEventStart.ContextUnavailable("current owned block budget projection changed");
    var transferred=CatalogueBudgetProjection.withoutExactOwnedBlocks(input.config(),heads,owned);
    return new OwnedBlockAllowance(available(input.state().budgets()),available(transferred),
      index.get().cut(),owned.size());
  }

 public static Held reserve(SelectedEventStart selected,PreparedEffect prepared,
   ResourceArbiter arbiter,Supplier<Tick> clock,DurationTicks lease,BudgetVector capacity,
   BooleanSupplier live) {
  selected.requirePhysicalForward(prepared.intent());
  var definition=selected.definition();var scope=new ScopeRef(ScopeKind.WORLD,selected.plan().scopes().worldId().value());
  var fragment=new ExecutionPlan(selected.plan().candidateId(),selected.plan().definition(),
   List.of(prepared.intent()),selected.plan().roles(),selected.plan().scopes(),prepared.preconditions());
  var decision=new DecisionId(UUID.nameUUIDFromBytes(("catalogue-sub-admission-v1\0"+
   selected.proposal().proposalId().value()+"\0"+prepared.intent().operationToken().value()).getBytes(StandardCharsets.UTF_8)));
  var request=new AdmissionRequest(decision,prepared.preconditions(),prepared.claims(),fragment.roles(),fragment);
  var validator=new AdmissionService.HardValidator() {
   public ProviderMode mode(){return selected.mode();}
   public AdmissionService.Assessment assess(AdmissionRequest actual,Tick now) {
    boolean budgetFits=BudgetMath.fits(definition.budget(),available(selected));
    boolean valid=actual.equals(request)&&live.getAsBoolean()&&budgetFits&&selected.physicalForwardValid(prepared.intent());
    return new AdmissionService.Assessment(new ValidationResult(valid,valid?List.of():List.of(budgetFits?ReasonCode.STALE_CONTEXT:ReasonCode.BUDGET_EXHAUSTED),
     prepared.preconditions()),prepared.claims(),new ResourceArbiter.Rank(false,
      definition.conflicts().priority()==Priority.REQUIRE,selected.parentChain().isPresent(),
      definition.conflicts().priority(),0,1,selected.captured().seed()),Map.of(scope,definition.budget()));
   }
  };
  var admission=new AdmissionService(()->selected.captured().catalog(),validator,arbiter,clock,lease,32,selected.mode());
  var first=selected.admit(admission,request);
  if(first.status()!=AdmissionStatus.DEFERRED)throw new IllegalStateException("selected J03 queue refused: "+first.reasons());
  var resolved=admission.resolveWindow(prepared.preconditions().epochs(),Map.of(scope,available(selected))).get(decision);
  if(resolved==null||resolved.status()!=AdmissionStatus.ACCEPTED)
   throw new IllegalStateException("selected J03 reservation refused: "+resolved);
  return new Held(admission,decision,resolved.token().orElseThrow());
 }
}
