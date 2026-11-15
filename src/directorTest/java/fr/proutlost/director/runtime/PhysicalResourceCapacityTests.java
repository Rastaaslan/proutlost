package fr.proutlost.director.runtime;
import fr.proutlost.director.api.model.*;
import java.util.*;
/** Same arbiter, atomic units, exclusivity and expired ownership retention. */
public final class PhysicalResourceCapacityTests {
 private static int n;private static void check(boolean yes,String why){n++;if(!yes)throw new AssertionError(why);}
 public static void main(String[] args){
  var world=new WorldId(UUID.randomUUID());var scope=new ScopeRef(ScopeKind.WORLD,world.value());var zero=BudgetMath.zero();var r=new ResourceId("fixture:physical_site");var arbiter=new ResourceArbiter(world,new Epoch(0),10,16,new ResourceArbiter.Compatibility(){public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}});
  var rank=new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1,0);
  java.util.function.BiFunction<Integer,PhysicalResourceClaim.Mode,ResourceArbiter.Candidate> candidate=(units,mode)->new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),new EventInstanceId(UUID.randomUUID()),rank,new Tick(10),List.of(),Map.of(scope,zero),List.of(new PhysicalResourceClaim(r,mode,units,3)));
  var a=candidate.apply(2,PhysicalResourceClaim.Mode.SHARED);var b=candidate.apply(1,PhysicalResourceClaim.Mode.SHARED);
  var first=arbiter.arbitrate(List.of(a,b),new Tick(1),Map.of(),Map.of(scope,zero));check(first.stream().allMatch(d->d.accepted().isPresent()),"two actual shared uses fit exactly three units");
  check(arbiter.arbitrate(List.of(candidate.apply(1,PhysicalResourceClaim.Mode.SHARED)),new Tick(2),Map.of(),Map.of(scope,zero)).getFirst().accepted().isEmpty(),"capacity cannot overbook");
  check(arbiter.arbitrate(List.of(candidate.apply(1,PhysicalResourceClaim.Mode.EXCLUSIVE)),new Tick(2),Map.of(),Map.of(scope,zero)).getFirst().accepted().isEmpty(),"exclusive use conflicts with actual shared uses");
  var conflicting=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),new EventInstanceId(UUID.randomUUID()),rank,new Tick(20),List.of(),Map.of(scope,zero),List.of(new PhysicalResourceClaim(r,PhysicalResourceClaim.Mode.SHARED,1,4)));
  check(arbiter.arbitrate(List.of(conflicting),new Tick(2),Map.of(),Map.of(scope,zero)).getFirst().accepted().isEmpty(),"another declared capacity cannot widen a held resource");
  var later=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),new EventInstanceId(UUID.randomUUID()),rank,new Tick(30),List.of(),Map.of(scope,zero),List.of(new PhysicalResourceClaim(r,PhysicalResourceClaim.Mode.SHARED,1,3)));
  check(arbiter.arbitrate(List.of(later),new Tick(11),Map.of(),Map.of(scope,zero)).getFirst().accepted().isEmpty(),"expiry never releases an uncertain owned use");
  for(var decision:first)check(arbiter.release(decision.accepted().orElseThrow()),"explicit resolved owner release");
  var pin=arbiter.retainPhysicalResources(new EventInstanceId(UUID.randomUUID()),List.of(new PhysicalResourceClaim(r,PhysicalResourceClaim.Mode.EXCLUSIVE,1,3)));
  check(!arbiter.valid(pin,new Tick(11))&&pin.claims().isEmpty(),"restored quarantine grants no forward or physical authority");
  check(arbiter.arbitrate(List.of(later),new Tick(11),Map.of(),Map.of(scope,zero)).getFirst().accepted().isEmpty(),"cold logical pin still conflicts until actual cleanup");
  check(arbiter.release(pin)&&arbiter.arbitrate(List.of(later),new Tick(11),Map.of(),Map.of(scope,zero)).getFirst().accepted().isPresent(),"cleanup releases the exact retained resource");
  System.out.println("PHYSICAL_RESOURCE_CAPACITY_PASS assertions="+n+" minecraft=false");
 }
}
