package fr.proutlost.director.runtime;
import fr.proutlost.director.api.model.*;
import java.util.*;
/** Real arbiter algebra. No store authority, native effect or client assertion. */
public final class GlobalReservationBorrowTests {
 private static int count;
 private static void check(boolean yes,String why){count++;if(!yes)throw new AssertionError(why);}
 private static void rejects(Runnable code){try{code.run();throw new AssertionError("accepted invalid tranche");}catch(IllegalArgumentException|IllegalStateException expected){count++;}}
 public static void main(String[] args){
  var world=new WorldId(UUID.randomUUID());var event=new EventInstanceId(UUID.randomUUID());var scope=new ScopeRef(ScopeKind.WORLD,world.value());
  var zero=BudgetMath.zero();var key=new ResourceKey(OwnershipDomain.WEATHER,new DimensionId("minecraft:overworld"),"weather");
  var claim=new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,zero,Optional.empty(),new Epoch(0));
  var arbiter=new ResourceArbiter(world,new Epoch(0),10,4,new ResourceArbiter.Compatibility(){public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId f,ResourceClaim a,ResourceClaim b){return false;}});
  var candidate=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),event,new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1,0),new Tick(100),List.of(claim),Map.of(scope,zero));
  var parent=arbiter.arbitrate(List.of(candidate),new Tick(1),Map.of(key,new Epoch(0)),Map.of(scope,zero)).getFirst().accepted().orElseThrow();
  var child=arbiter.borrow(parent,List.of(claim),new Tick(2),new DurationTicks(200));check(child.expiryTick().equals(parent.expiryTick()),"child bounded by parent expiry");
  check(arbiter.valid(parent,new Tick(2))&&arbiter.valid(child,new Tick(2)),"parent and sealed subset current");
  check(!arbiter.release(parent),"parent cannot release an in-flight tranche");rejects(()->arbiter.renew(parent,new Tick(3),new DurationTicks(100)));
  rejects(()->arbiter.borrow(parent,List.of(claim),new Tick(3),new DurationTicks(10)));
  arbiter.markStarted(child,new Tick(3));check(arbiter.snapshot().size()==1&&arbiter.snapshot().getFirst().equals(parent),"one charged global reservation only");
  check(arbiter.release(child)&&!arbiter.valid(child,new Tick(3))&&arbiter.valid(parent,new Tick(3)),"child release retains parent and closes replay");
  var foreign=new ResourceClaim(new ResourceKey(OwnershipDomain.BLOCK,key.dimension(),"0,0,0"),ClaimPolicy.EXCLUSIVE,zero,Optional.empty(),new Epoch(0));
  rejects(()->arbiter.borrow(parent,List.of(foreign),new Tick(3),new DurationTicks(10)));
  var renewed=arbiter.renew(parent,new Tick(4),new DurationTicks(20));check(!arbiter.valid(parent,new Tick(4)),"old parent generation fenced");
  var last=arbiter.borrow(renewed,List.of(claim),new Tick(5),new DurationTicks(10));check(!arbiter.valid(last,new Tick(15)),"exact absolute child expiry");
  check(!arbiter.release(renewed)&&arbiter.release(last)&&arbiter.release(renewed),"expired physical tranche still retains parent until explicit completion");
  var pending=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),new EventInstanceId(UUID.randomUUID()),candidate.rank(),new Tick(20),List.of(claim),Map.of(scope,zero));
  var retained=arbiter.arbitrate(List.of(pending),new Tick(16),Map.of(key,new Epoch(0)),Map.of(scope,zero)).getFirst().accepted().orElseThrow();
  rejects(()->arbiter.renewRetainedForCleanup(retained,new Tick(17),new DurationTicks(10)));
  arbiter.markStarted(retained,new Tick(18));var clean=arbiter.renewRetainedForCleanup(retained,new Tick(21),new DurationTicks(5));
  check(clean.claims().equals(retained.claims())&&arbiter.valid(clean,new Tick(21))&&!arbiter.valid(retained,new Tick(21)),"expired retained cleanup renewal changes only fence/lifetime and preserves exact claims");
  check(arbiter.release(clean),"exact stabilization owner release");
  System.out.println("GLOBAL_RESERVATION_BORROW_PASS assertions="+count+" minecraft=false");
 }
}
