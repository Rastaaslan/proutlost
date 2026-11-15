package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.WorldEffectPort;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Function;

/** Pure weather lease planner. Captured native timers arrive in an authenticated immutable blob.
 * The adapter applies exact timers; cleanup uses captured pre-state, never an invented climate.
 */
public final class GlobalWeatherPrimitive extends SingleSlicePrimitive<GlobalWeatherLease> {
 private static final ResourceId WEATHER_CODEC=new ResourceId("proutlost_director:weather_state");
 private static final CapabilityId CAPABILITY=new CapabilityId("weather.global_lease_v1");
 public GlobalWeatherPrimitive(PrimitiveDescriptor descriptor,WorldEffectPort effects,Function<PreparedEffect,PreparedSlice> slices,Function<PreparedEffect,BlobBundle> hydrated){super(descriptor,effects,slices,hydrated);if(!descriptor.capabilities().contains(CAPABILITY)||!descriptor.ownershipDomains().contains(OwnershipDomain.WEATHER))throw new IllegalArgumentException("weather descriptor");}
 public PreparedEffect plan(GlobalWeatherLease args,PlanningSnapshot context){
  var bindings=context.bindings().orElseThrow(()->new IllegalArgumentException("weather planning bindings missing"));
  if(!bindings.audienceComplete()||!args.dimension().equals(bindings.footprint().dimension())||args.duration().value()==0||args.duration().value()>descriptor().maximumDuration().value())throw new IllegalArgumentException("weather footprint/duration/audience");
  var key=new ResourceKey(OwnershipDomain.WEATHER,args.dimension(),"weather");
  var owner=Objects.requireNonNull(context.ownership().records().get(key),"weather ownership missing");
  if(owner.disposition()!=OwnershipDisposition.DIRECTOR_ACTIVE||owner.ownerTokens().size()!=1)throw new IllegalArgumentException("exclusive weather ownership required");
  var pre=context.blobs().contents().stream().filter(b->b.reference().codecId().equals(WEATHER_CODEC)&&b.reference().codecVersion()==1&&b.reference().hash().equals(owner.expectedPostHash())).findFirst().orElseThrow(()->new IllegalArgumentException("captured weather timers unavailable"));
  validateTimers(pre);int duration=Math.toIntExact(args.duration().value());
  BlobContent post=switch(args.weather().value()){
   case "minecraft:clear" -> state(duration,0,0,false,false);
   case "minecraft:rain" -> state(0,duration,duration,true,false);
   case "minecraft:thunder" -> state(0,duration,duration,true,true);
   default -> throw new IllegalArgumentException("unknown weather profile");
  };
  var intent=new EffectIntent(bindings.operationToken(),descriptor().id(),descriptor().version(),args,bindings.footprint(),bindings.instanceId());
  var inverse=new EffectIntent(inverseToken(intent.operationToken()),descriptor().id(),descriptor().version(),new GlobalWeatherLease(args.dimension(),new ResourceId("proutlost_director:restore"),args.duration(),args.externalRevision()),bindings.footprint(),bindings.instanceId());
  var read=new ReadSet(context.snapshot().scopeRevisions(),Map.of(key,owner.epoch()),Map.of(key,pre.reference().hash()));
  var ownerBytes=ownerBlob(owner);var work=BudgetMath.add(descriptor().minimumCost(),new BudgetVector(0,0,0,0,0,1,pre.reference().bytes()+post.reference().bytes()+ownerBytes.reference().bytes(),0,0,0));
  if(!BudgetMath.fits(descriptor().minimumCost(),work))throw new IllegalArgumentException("descriptor budget requires calibrated cost");
  var refs=new EffectBlobs(Set.of(pre.reference(),ownerBytes.reference()),post.reference(),pre.reference(),Map.of(key,pre.reference()),Map.of(key,post.reference()));
  return new PreparedEffect(intent,context.snapshot(),List.of(new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,work,Optional.empty(),owner.epoch())),read,Map.of(key,post.reference().hash()),work,List.of(inverse),descriptor().capabilities(),refs);
 }
 /** Deterministic bytes generated during pure planning; caller can hydrate these without IO. */
 public BlobBundle materialize(GlobalWeatherLease args,PlanningSnapshot context,PreparedEffect plan){
  var expected=plan(args,context);if(!expected.equals(plan))throw new IllegalArgumentException("weather plan substitution");
  int duration=Math.toIntExact(args.duration().value());var post=switch(args.weather().value()){
   case "minecraft:clear"->state(duration,0,0,false,false);case "minecraft:rain"->state(0,duration,duration,true,false);case "minecraft:thunder"->state(0,duration,duration,true,true);default->throw new IllegalArgumentException("profile");};
  var blobs=new LinkedHashMap<BlobRef,BlobContent>();for(var b:context.blobs().contents())if(plan.durableBlobs().snapshots().contains(b.reference()))blobs.put(b.reference(),b);
  var owner=ownerBlob(context.ownership().records().get(plan.claims().getFirst().resource()));blobs.put(owner.reference(),owner);blobs.put(post.reference(),post);return new BlobBundle(List.copyOf(blobs.values()));
 }
 private static BlobContent state(int clear,int rain,int thunder,boolean raining,boolean thundering){return BlobContent.capture(WEATHER_CODEC,1,ByteBuffer.allocate(14).putInt(clear).putInt(rain).putInt(thunder).put((byte)(raining?1:0)).put((byte)(thundering?1:0)).array());}
 private static void validateTimers(BlobContent blob){var bytes=blob.bytes();if(bytes.length!=14)throw new IllegalArgumentException("weather codec size");var b=ByteBuffer.wrap(bytes);if(b.getInt()<0||b.getInt()<0||b.getInt()<0)throw new IllegalArgumentException("weather timers");int rain=b.get(),thunder=b.get();if(rain<0||rain>1||thunder<0||thunder>1)throw new IllegalArgumentException("weather flags");}
}
