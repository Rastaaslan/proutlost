package fr.proutlost.director.runtime;
import fr.proutlost.director.api.model.*;import java.util.*;
public final class WeatherRecoveryRoutingTests {
 static int checks;static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
 static OwnershipRecord owner(ResourceKey key,UUID id){return new OwnershipRecord(new AggregateHeader(1,id,new Revision(1),new Sequence(0),new Tick(1)),key,Set.of(new OwnerToken(UUID.randomUUID())),new Epoch(0),OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),new Fingerprint("0".repeat(64)),new Epoch(0),Optional.empty());}
 static void refused(Runnable action,String why){boolean refused=false;try{action.run();}catch(IllegalArgumentException e){refused=true;}check(refused,why);}
 public static void main(String[] args){
  var world=new WorldId(UUID.randomUUID());var event=new EventInstanceId(UUID.randomUUID());var dim=new DimensionId("minecraft:overworld");
  var request=new RegionalWeatherJournal.Request(event,new OperationToken(UUID.randomUUID()),new Footprint(dim,Set.of(),Set.of(),List.of(new Aabb(dim,-16,-64,-16,16,320,16))),new DurationTicks(200),new Fingerprint("1".repeat(64)));
  var key=new ResourceKey(OwnershipDomain.WEATHER,dim,"region:"+event.value());var owned=owner(key,UUID.randomUUID());
  var state=new RegionalWeatherJournal.State(1,1,request,owned,Optional.empty(),List.of(),false,false,"OWNED_BEFORE_NATIVE_PREPARE");
  var fact=RegionalWeatherJournal.fact(world,state);var router=new WeatherRecoveryRouting(world);
  check(!router.regional(owned),"a region-shaped key alone grants no routing");
  router.observe(new DurableFact(fact));check(router.regional(owned),"actual exact committed journal identity routes to regional owner");
  router.observe(new DurableFact(fact));check(router.regional(owned),"exact replay preserves routing");
  check(!router.regional(owner(new ResourceKey(OwnershipDomain.WEATHER,dim,"weather"),UUID.randomUUID())),"global lease still requires its global recovery");
  check(!router.regional(owner(new ResourceKey(OwnershipDomain.WEATHER,dim,"region:"+UUID.randomUUID()),UUID.randomUUID())),"unknown regional key remains protected");
  check(!router.regional(owner(key,UUID.randomUUID())),"foreign replacement header never hidden by matching resource text");
  refused(()->new WeatherRecoveryRouting(new WorldId(UUID.randomUUID())).observe(new DurableFact(fact)),"foreign world fact refused");
  router.clear();check(!router.regional(owned),"stale chronology restart discards routing cache only, never journal/owners");
  System.out.println("WEATHER_RECOVERY_ROUTING_PASS assertions="+checks+" scope=COMMITTED_MODEL_ROUTING_NOT_NATIVE_RECOVERY");
 }
}
