package fr.proutlost.director.runtime;
import fr.proutlost.director.api.model.*;import java.util.*;
/** Routes actual committed regional owners to their existing coordinator.
 * This never grants apply/rearm authority, discards an owner or changes J04. */
public final class WeatherRecoveryRouting {
 private final WorldId world;private final Map<ResourceKey,RegionalWeatherJournal.State> regional=new HashMap<>();
 public WeatherRecoveryRouting(WorldId world){this.world=Objects.requireNonNull(world);}
 public void clear(){regional.clear();}
 public void observe(DurableRecord row){
  if(!(row instanceof DurableFact fact)||!fact.value().factType().equals(RegionalWeatherJournal.TYPE))return;
  if(!fact.value().subjectRefs().equals(List.of(new ScopeRef(ScopeKind.WORLD,world.value()))))throw new IllegalArgumentException("foreign regional recovery world");
  var state=RegionalWeatherJournal.state(fact.value());var key=state.owner().resourceKey();var old=regional.get(key);
  if(old!=null&&(state.ordinal()<old.ordinal()||state.ordinal()==old.ordinal()&&!state.equals(old)||!state.owner().header().id().equals(old.owner().header().id())))throw new IllegalArgumentException("regional recovery identity or chronology differs");
  if(old==null&&regional.size()>=2048)throw new IllegalStateException("regional recovery routing bound");
  regional.put(key,state);
 }
 public boolean regional(OwnershipRecord owner){
  var known=regional.get(owner.resourceKey());
  return known!=null&&known.owner().header().id().equals(owner.header().id());
 }
}
