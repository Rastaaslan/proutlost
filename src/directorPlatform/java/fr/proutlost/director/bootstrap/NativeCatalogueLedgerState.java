package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.CatalogueEventLedger;
import java.util.*;

/** CURRENT published J04 metadata ONLY. Captured input supplies immutable
 * catalogue/config/selected identity, never authoritative world/ledger/lineage.
 * WORLD, relevant Unique/Cooldown and region-parent heads all come from the
 * final atomic point-read cut. Operational aggregates never leave RAM in J04.
 * The returned carrier is deliberately incomplete for J03: do not use it as
 * eligibility/observation/ownership evidence or as SelectedEventStart.refresh(). */
public final class NativeCatalogueLedgerState {
 private NativeCatalogueLedgerState(){}
 public record View(DecisionInput input,ReadStamp stamp){}
 /** Call during admission/before apply, reserving the bounded metadata key set. */
 public static Set<AggregateKey> keysBeforeApply(CatalogueEventLedger.Selection selection,DecisionInput identity){
  var keys=new HashSet<>(CatalogueEventLedger.keys(selection,identity));
  keys.add(new AggregateKey(AggregateKind.WORLD,selection.plan().scopes().worldId().value()));
  var regions=new HashSet<UUID>();var pending=new ArrayDeque<RegionId>();
  for(var scope:selection.plan().scopes().scopes())if(scope.kind()==ScopeKind.REGION)pending.add(new RegionId(scope.id()));
  while(!pending.isEmpty()){
   var id=pending.removeFirst();if(!regions.add(id.value()))continue;
   keys.add(new AggregateKey(AggregateKind.REGION,id.value()));
   var record=identity.memory().regionRecords().get(id);if(record!=null)pending.addAll(record.parentIds());
  }
  // Existing logical heads preserve their aggregate identity, including inherited uses.
  if(selection.definition().uniqueness().scope()==UniquenessScope.ONCE_PER_REGION)
   for(var use:identity.state().uniqueness())if(use.definitionId().equals(selection.definition().id())&&use.scopeKind()==UniquenessScope.ONCE_PER_REGION&&
     (regions.contains(use.canonicalScopeId())||!Collections.disjoint(regions,use.originScopeIds())))
    keys.add(new AggregateKey(AggregateKind.UNIQUE,use.header().id()));
  bounded(keys);return Set.copyOf(keys);
 }
 private static void bounded(Set<AggregateKey> keys){if(keys.size()>16)throw new IllegalStateException("reserve selected ledger WORLD/lineage key bound BEFORE physical apply");}
 public static DecisionInput current(JournalStore store,CatalogueEventLedger.Selection selection,DecisionInput capturedIdentity){
  return read(store,selection,capturedIdentity).input();
 }
 public static View read(JournalStore store,CatalogueEventLedger.Selection selection,DecisionInput identity){
  Objects.requireNonNull(store);Objects.requireNonNull(selection);Objects.requireNonNull(identity);
  if(store.readOnlySafe())throw new IllegalStateException("real J04 metadata writer unavailable");
  var session=store.session();var world=selection.plan().scopes().worldId();
  if(!session.worldId().equals(world))throw new IllegalArgumentException("ledger world/session mismatch");
  var keys=new HashSet<>(keysBeforeApply(selection,identity));
  for(int attempt=0;attempt<16;attempt++){
   bounded(keys);var query=new StoreQuery(world,Set.copyOf(keys),new Sequence(0),Optional.empty(),16);
   var result=store.view(query);ReadContracts.validate(result,query,session);
   if(result.status()!=ReadStatus.AVAILABLE||result.page().isEmpty()||result.stamp().isEmpty())
    throw new IllegalStateException("actual published ledger point read unavailable");
   var page=result.page().orElseThrow();var stamp=result.stamp().orElseThrow();
   if(page.hasMore()||!page.records().isEmpty()||!stamp.session().equals(session)||
     !page.lastDurableSequence().equals(stamp.snapshotSequence())||!store.session().equals(session))
    throw new IllegalStateException("ledger read session/cut/shape changed");
   var restored=new HashMap<AggregateKey,AggregateState>();
   for(var row:page.aggregates()){
    var state=row.state();
    if(!keys.contains(state.key())||restored.putIfAbsent(state.key(),state)!=null||
      row.committedSequence().value()<=0||row.committedSequence().compareTo(stamp.snapshotSequence())>0||
      state.header().lastSequence().compareTo(row.committedSequence())>0||
      !(state instanceof UniqueAggregate)&&!(state instanceof CooldownAggregate)&&!(state instanceof WorldAggregate)&&!(state instanceof RegionAggregate))
     throw new IllegalStateException("foreign/future/inconsistent actual ledger head");
   }
   var worldState=restored.get(new AggregateKey(AggregateKind.WORLD,world.value()));
   if(!(worldState instanceof WorldAggregate actualWorld)||!actualWorld.value().worldId().equals(world)||
     !actualWorld.value().storeGeneration().equals(session.storeGeneration()))
    throw new IllegalStateException("actual current WORLD missing or wrong generation");
   var regional=new HashMap<RegionId,RegionRecord>();boolean expanded=false;
   for(var state:restored.values())if(state instanceof RegionAggregate region){
    regional.put(region.value().regionId(),region.value());
    for(var parent:region.value().parentIds())if(keys.add(new AggregateKey(AggregateKind.REGION,parent.value())))expanded=true;
   }
   if(expanded){bounded(keys);continue;} // Reread WHOLE query; never join two cuts.
   for(var key:keys)if(key.kind()==AggregateKind.REGION&&!restored.containsKey(key))
    throw new IllegalStateException("actual selected region-parent lineage unavailable");
   for(var u:identity.state().uniqueness())if(keys.contains(new UniqueAggregate(u).key())&&!restored.containsKey(new UniqueAggregate(u).key()))
    throw new IllegalStateException("previously observed unique head disappeared");
   for(var c:identity.state().cooldowns())if(keys.contains(new CooldownAggregate(c).key())&&!restored.containsKey(new CooldownAggregate(c).key()))
    throw new IllegalStateException("previously observed cooldown head disappeared");
   var unique=new ArrayList<UniqueUseRecord>();var cooldowns=new ArrayList<CooldownRecord>();
   for(var state:restored.values()){
    if(state instanceof UniqueAggregate u)unique.add(u.value());
    if(state instanceof CooldownAggregate c)cooldowns.add(c.value());
   }
   // Empty unrelated evidence means UNKNOWN to J03, not a fabricated current view.
   var memory=new MemoryView(new ScopeSet(world,Set.of()),Map.of(),List.of(),List.of(),List.of(),regional,Map.of(),Map.of());
   var state=new DecisionState(actualWorld.value(),new BudgetView(BudgetMath.zero(),BudgetMath.zero(),Map.of()),new OwnershipView(Map.of()),
    cooldowns,unique,List.of(),List.of(),List.of(),List.of());
   var provenance=identity.context();
   var context=new SnapshotBatch(provenance.requestId(),new ScopeSet(world,Set.of()),provenance.capturedTick(),Map.of(),
    new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),
    EnumSet.allOf(SensorKind.class),Set.of(),provenance.serverHealth());
   return new View(new DecisionInput(context,memory,identity.catalog(),identity.config(),List.of(),List.of(),identity.seed(),state,Optional.empty()),stamp);
  }
  throw new IllegalStateException("actual region-parent graph changed throughout bounded metadata read");
 }
}
