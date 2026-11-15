package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Budget projection from one caller-verified committed J04 cut.
 * Physical ownership is charged even when unloaded. Entity rows have no
 * canonical RegionId, so every actual ACTIVE region conservatively carries
 * the full physical debit; this never invents a regional free allowance.
 * Live admission/arbiters retain their own active CPU/client/IO reservations.
 */
public final class CatalogueBudgetProjection {
 private CatalogueBudgetProjection(){}
 public static BudgetView from(ConfigView config,Collection<? extends AggregateState> heads){
  Objects.requireNonNull(config);Objects.requireNonNull(heads);
  var seen=new HashSet<AggregateKey>();var regions=new HashSet<ScopeRef>();
  long live=0,persistent=0,mutations=0;
  for(var state:heads){
   Objects.requireNonNull(state);
   if(!seen.add(state.key()))throw new IllegalArgumentException("duplicate budget aggregate head");
   if(state instanceof RegionAggregate region&&region.value().status()==RegionStatus.ACTIVE)
    regions.add(new ScopeRef(ScopeKind.REGION,region.value().regionId().value()));
   else if(state instanceof EntityOwnershipAggregate entity){
    var e=entity.value();
    if(e.disposition()!=OwnershipDisposition.RELEASED&&e.phase()!=EntityPhase.REMOVED){
     live=Math.addExact(live,1);
     if(e.persistMode()==ArrivalPolicy.SETTLE_WORLD)persistent=Math.addExact(persistent,1);
    }
   }else if(state instanceof OwnershipAggregate owner&&owner.value().disposition()==OwnershipDisposition.DIRECTOR_ACTIVE)
    mutations=Math.addExact(mutations,1);
  }
  var v=config.values();
  java.util.function.Function<String,Long> n=k->Objects.requireNonNull(v.get(k),k).value().longValueExact();
  java.util.function.Function<String,Double> d=k->Objects.requireNonNull(v.get(k),k).value().doubleValue();
  var cap=new BudgetVector(d.apply("budget.tick_ms"),d.apply("budget.global_intensity"),d.apply("budget.region_intensity"),
   n.apply("entity.director_world_cap"),n.apply("entity.director_world_cap"),n.apply("event.disaster.mutation_units"),
   n.apply("io.queue_bytes"),n.apply("client.max_effects"),n.apply("network.s2c_payload_bytes"),n.apply("entity.max_path_nodes"));
  var reserved=new BudgetVector(0,0,0,live,persistent,mutations,0,0,0,0);
  var scopes=new HashMap<ScopeRef,BudgetVector>();
  for(var region:regions)scopes.put(region,reserved);
  return new BudgetView(cap,reserved,scopes);
 }
}
