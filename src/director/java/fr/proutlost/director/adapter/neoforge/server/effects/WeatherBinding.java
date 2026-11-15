package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.codec.ModelCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.ServerLevelData;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.*;

/** Native weather lease with an actual game-time witness.
 * Owner/override/external revision fences are unchanged.
 * Exact restoration ends at the first unobserved counterfactual random sample.
 * Such inverse captures are rejected before any physical write; the coordinator must
 * qualify natural expiry with an explicit NO_BASELINE_RESTORE fact instead.
 */
public final class WeatherBinding implements RestorableWeatherBinding {
 private static final ResourceId RAW=new ResourceId("proutlost_director:weather_state");
 private static final ResourceId OWNER=new ResourceId("proutlost_director:owner_v1");
 private static final ResourceId CLOCK=new ResourceId("proutlost_director:weather_clock_v1");
 private static final ResourceId TIMED=new ResourceId("proutlost_director:weather_timed_state_v1");
 private static final ResourceId TARGET=new ResourceId("proutlost_director:weather_restore_source_v1");
 private static final ResourceId DEADLINES=new ResourceId("proutlost_director:weather_deadlines_v1");
 private static final CapabilityId CAPABILITY=new CapabilityId("weather.global_lease_v1");
 private final Function<DimensionId,ServerLevel> levels;
 private final Supplier<OwnershipView> ownership;
 private final Function<DimensionId,Revision> externalRevision;
 private final Map<OperationToken,BlobContent> restoreTargets=new HashMap<>();
 private final int capacity;
 private final RestorableWeatherBinding alternative;
 private record State(int clear,int rain,int thunder,boolean raining,boolean thundering){
  State{if(clear<0||rain<0||thunder<0)throw new IllegalArgumentException("negative weather counter");}
  byte[] bytes(){return ByteBuffer.allocate(14).putInt(clear).putInt(rain).putInt(thunder).put((byte)(raining?1:0)).put((byte)(thundering?1:0)).array();}
  BlobContent blob(){return BlobContent.capture(RAW,1,bytes());}
  static State bytes(byte[] bytes){
   if(bytes.length!=14)throw new IllegalArgumentException("weather state length");
   var b=ByteBuffer.wrap(bytes);int c=b.getInt(),r=b.getInt(),t=b.getInt();byte rf=b.get(),tf=b.get();
   if(rf<0||rf>1||tf<0||tf>1)throw new IllegalArgumentException("weather flags");
   return new State(c,r,t,rf==1,tf==1);
  }
  static State read(BlobContent blob){check(blob,RAW,14);return bytes(blob.bytes());}
 }
 private record Clock(long tick,boolean cycle){
  BlobContent blob(){return BlobContent.capture(CLOCK,1,ByteBuffer.allocate(9).putLong(tick).put((byte)(cycle?1:0)).array());}
  static Clock read(BlobContent blob){
   check(blob,CLOCK,9);var b=ByteBuffer.wrap(blob.bytes());long t=b.getLong();byte c=b.get();
   if(c<0||c>1)throw new IllegalArgumentException("weather clock flag");return new Clock(t,c==1);
  }
 }
 private record Stop(State state,Clock clock){
  BlobContent blob(ResourceId codec){
   boolean target=codec.equals(TARGET);var b=ByteBuffer.allocate(target?24:23);
   if(target)b.put((byte)1); // Distinct payload: J04 blob identity is the payload hash, not codecId.
   return BlobContent.capture(codec,1,b.put(state.bytes()).putLong(clock.tick()).put((byte)(clock.cycle()?1:0)).array());
  }
  static Stop read(BlobContent blob){
   var codec=blob.reference().codecId();int length=blob.bytes().length;
   if(!Set.of(TIMED,TARGET).contains(codec)||blob.reference().codecVersion()!=1
     ||(codec.equals(TIMED)?length!=23:length!=23&&length!=24))throw new IllegalArgumentException("dated weather stop");
   var b=ByteBuffer.wrap(blob.bytes());
   // Legacy TARGET23 remains readable; every newly captured TARGET has the validated v1 tag.
   if(codec.equals(TARGET)&&length==24&&b.get()!=1)throw new IllegalArgumentException("weather restore source tag");
   var s=new byte[14];b.get(s);long tick=b.getLong();byte c=b.get();
   if(c<0||c>1)throw new IllegalArgumentException("weather stop flag");return new Stop(State.bytes(s),new Clock(tick,c==1));
  }
 }
 private record Channel(int timer,boolean flag){}
 private static void check(BlobContent b,ResourceId codec,int length){
  if(!b.reference().codecId().equals(codec)||b.reference().codecVersion()!=1||b.bytes().length!=length)throw new IllegalArgumentException("weather codec/version/length");
 }
 public WeatherBinding(Function<DimensionId,ServerLevel> levels,Supplier<OwnershipView> ownership,Function<DimensionId,Revision> externalRevision,int capacity){
  this.levels=Objects.requireNonNull(levels);this.ownership=Objects.requireNonNull(ownership);this.externalRevision=Objects.requireNonNull(externalRevision);
  if(capacity<1)throw new IllegalArgumentException("capacity");this.capacity=capacity;
  this.alternative=alternative(levels,ownership,externalRevision,capacity);
 }

 /** The optional implementation is not resolved at all when Core is absent.
  * If Core is present but this adapter is missing, refuse instead of silently
  * restoring raw native timers under a different authoritative weather engine.
  */
 private static RestorableWeatherBinding alternative(Function<DimensionId,ServerLevel> levels,
   Supplier<OwnershipView> ownership,Function<DimensionId,Revision> externalRevision,int capacity){
  if(!net.neoforged.fml.ModList.get().isLoaded("weathercore"))return null;
  try{
   return (RestorableWeatherBinding)Class.forName("fr.proutlost.director.adapter.neoforge.server.effects.weathercore.WeatherCoreIntegration")
    .getMethod("createBinding",Function.class,Supplier.class,Function.class,int.class)
    .invoke(null,levels,ownership,externalRevision,capacity);
  }catch(ReflectiveOperationException failure){throw new IllegalStateException("WeatherCore installed without compatible Director adapter",failure);}
 }
 public Fingerprint observedFingerprint(DimensionId dimension){
  return alternative==null?state(level(dimension)).blob().reference().hash():alternative.observedFingerprint(dimension);
 }
 public boolean expiredByBackend(PreparedEffect plan){return alternative!=null&&alternative.expiredByBackend(plan);}
 public void releaseReceipt(PreparedEffect plan){if(alternative!=null)alternative.releaseReceipt(plan);}
 public ProviderMode mode(){return ProviderMode.PRODUCTION;}
 public Set<PrimitiveId> supportedPrimitives(){return Set.of(new PrimitiveId("proutlost:global_weather_lease"));}
 public Set<CapabilityId> capabilities(){return Set.of(CAPABILITY);}
 private ServerLevel level(DimensionId d){
  var l=Objects.requireNonNull(levels.apply(d),"dimension unavailable");
  if(!l.getServer().isSameThread())throw new IllegalStateException("server actor required");
  if(!l.dimension().location().toString().equals(d.value())||!l.dimensionType().hasSkyLight())throw new IllegalArgumentException("weather dimension");return l;
 }
 private static Clock clock(ServerLevel l){return new Clock(l.getGameTime(),l.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE));}
 private static State state(ServerLevel l){
  var d=(ServerLevelData)l.getLevelData();return new State(d.getClearWeatherTime(),d.getRainTime(),d.getThunderTime(),d.isRaining(),d.isThundering());
 }
 private static ResourceKey key(DimensionId d){return new ResourceKey(OwnershipDomain.WEATHER,d,"weather");}
 private static BlobContent find(BlobBundle b,BlobRef r){return b.contents().stream().filter(v->v.reference().equals(r)).findFirst().orElseThrow(()->new IllegalArgumentException("missing weather blob"));}
 private static Optional<BlobContent> snapshot(PreparedEffect p,BlobBundle b,ResourceId codec){
  var refs=p.durableBlobs().snapshots().stream().filter(r->r.codecId().equals(codec)&&r.codecVersion()==1).toList();
  if(refs.size()>1)throw new IllegalArgumentException("ambiguous weather witness");return refs.isEmpty()?Optional.empty():Optional.of(find(b,refs.getFirst()));
 }
 private static OwnershipRecord capturedOwner(PreparedEffect p,BlobBundle b,ResourceKey k){
  try{var raw=snapshot(p,b,OWNER).orElseThrow();var o=new ModelCodec<>(OwnershipRecord.class).decode(raw.bytes());return o.resourceKey().equals(k)?o:null;}
  catch(RuntimeException unreadable){return null;}
 }
 private GlobalWeatherLease args(EffectIntent i){
  if(!(i.typedArgs() instanceof GlobalWeatherLease a)||i.version()!=1||!a.dimension().equals(i.expectedFootprint().dimension()))throw new IllegalArgumentException("weather intent");return a;
 }
 private static boolean restore(GlobalWeatherLease a){return a.weather().value().equals("proutlost_director:restore");}
 public void registerRestore(EffectIntent inverse,BlobContent stop){
  if(alternative!=null){alternative.registerRestore(inverse,stop);return;}
  var a=args(inverse);var l=level(a.dimension());
  if(!restore(a))throw new IllegalArgumentException("restore intent");
  if(stop.reference().codecId().equals(RAW)){
   State.read(stop);if(clock(l).cycle())throw new IllegalArgumentException("live restore needs original dated stopPlan");
  }else Stop.read(stop);
  if(restoreTargets.size()>=capacity||restoreTargets.putIfAbsent(inverse.operationToken(),stop)!=null)throw new IllegalStateException("restore capacity/duplicate");
 }
 /** Exact counterfactual projection only. Never invent a weather random draw. */
 private static Channel age(int timer,boolean flag,long elapsed){
  if(elapsed==0)return new Channel(timer,flag);
  if(timer==0||elapsed>timer)throw new IllegalStateException("counterfactual weather entropy boundary");
  if(elapsed<timer)return new Channel(Math.toIntExact(timer-elapsed),flag);
  return new Channel(0,!flag);
 }
 private static State age(Stop source,Clock at){
  if(source.clock().cycle()!=at.cycle())throw new IllegalStateException("weather cycle rule changed");
  long elapsed=Math.subtractExact(at.tick(),source.clock().tick());
  if(elapsed<0)throw new IllegalStateException("weather time moved backwards");
  var s=source.state();if(!at.cycle()||elapsed==0)return s;
  if(s.clear()>0){
   if(elapsed<=s.clear())return new State(Math.toIntExact(s.clear()-elapsed),
     elapsed==1?(s.raining()?0:1):1,elapsed==1?(s.thundering()?0:1):1,false,false);
   elapsed-=s.clear();
   s=new State(0,s.clear()==1?(s.raining()?0:1):1,s.clear()==1?(s.thundering()?0:1):1,false,false);
  }
  var rain=age(s.rain(),s.raining(),elapsed);var thunder=age(s.thunder(),s.thundering(),elapsed);
  return new State(0,rain.timer(),thunder.timer(),rain.flag(),thunder.flag());
 }
 /** Canonical actual native state: positive counters become absolute deadlines.
  * Timer-zero phases remain distinct; a boundary crossing invalidates a sealed target.
  */
 private static BlobContent deadlines(State s,long tick){
  boolean clear=s.clear()>0;
  if(clear&&(s.raining()||s.thundering()||s.rain()>1||s.thunder()>1))throw new IllegalStateException("weather clear phase not naturally canonical");
  return BlobContent.capture(DEADLINES,1,ByteBuffer.allocate(27).put((byte)(clear?1:0))
    .putLong(clear?Math.addExact(tick,s.clear()):0)
    .putLong(clear||s.rain()==0?0:Math.addExact(tick,s.rain()))
    .putLong(clear||s.thunder()==0?0:Math.addExact(tick,s.thunder()))
    .put((byte)(!clear&&s.raining()?1:0)).put((byte)(!clear&&s.thundering()?1:0)).array());
 }
 /** Pinned 1.21.1 advanceWeatherCycle: at most one unobserved random sample per channel.
  * Bounds are vanilla RAIN_DELAY 12000..180000, RAIN_DURATION 12000..24000,
  * THUNDER_DELAY 12000..180000, THUNDER_DURATION 3600..15600.
  * No random source is consumed during validation.
  */
 private static boolean reachable(int timer,boolean flag,long elapsed,int observed,boolean observedFlag,boolean thunder){
  if(elapsed==0)return observed==timer&&observedFlag==flag;
  if(elapsed>=3600)return false;
  if(timer>0&&elapsed<timer)return observed==timer-elapsed&&observedFlag==flag;
  if(timer>0&&elapsed==timer)return observed==0&&observedFlag!=flag;
  boolean next=timer==0?flag:!flag;long spent=elapsed-(timer==0?1:(long)timer+1);
  int min=next?(thunder?3600:12000):12000,max=next?(thunder?15600:24000):180000;
  return observedFlag==next&&observed>=min-spent&&observed<=max-spent;
 }
 private static boolean natural(State before,Clock captured,State actual,Clock current){
  if(captured.cycle()!=current.cycle())return false;
  long dt=current.tick()-captured.tick();if(dt<0)return false;
  if(!captured.cycle()||dt==0)return before.equals(actual);
  if(before.clear()>0){
   if(dt<=before.clear())return actual.equals(age(new Stop(before,captured),current));
   dt-=before.clear();
   before=new State(0,before.clear()==1?(before.raining()?0:1):1,before.clear()==1?(before.thundering()?0:1):1,false,false);
  }
  return actual.clear()==0&&reachable(before.rain(),before.raining(),dt,actual.rain(),actual.raining(),false)
      &&reachable(before.thunder(),before.thundering(),dt,actual.thunder(),actual.thundering(),true);
 }
 public EffectPreparation capture(EffectIntent intent,PlanningSnapshot context){
  if(alternative!=null)return alternative.capture(intent,context);
  var a=args(intent);var l=level(a.dimension());var k=key(a.dimension());var c=clock(l);
  var owner=Objects.requireNonNull(context.ownership().records().get(k),"weather ownership must be admitted");
  if(owner.disposition()!=OwnershipDisposition.DIRECTOR_ACTIVE||owner.ownerTokens().isEmpty()||!a.externalRevision().equals(externalRevision.apply(a.dimension())))throw new IllegalArgumentException("weather ownership/revision");
  var before=state(l);int duration=Math.toIntExact(a.duration().value());if(duration<=0)throw new IllegalArgumentException("weather TTL");
  BlobContent target=null;State after;
  if(restore(a)){
   var registered=Objects.requireNonNull(restoreTargets.remove(intent.operationToken()),"restore must hydrate captured stopPlan");
   if(registered.reference().codecId().equals(RAW)){if(c.cycle())throw new IllegalArgumentException("undated live stop");after=State.read(registered);}
   else{var dated=Stop.read(registered);after=age(dated,c);target=dated.blob(TARGET);}
  }else after=switch(a.weather().value()){
   case "minecraft:clear"->new State(duration,0,0,false,false);
   case "minecraft:rain"->{
    if(!c.cycle())yield new State(0,duration,duration,true,false); // Legacy frozen-cycle fixtures: no native timer transition.
    // Do not arm native thunder at the rain TTL. Preserve a real non-thundering
    // countdown already observed beyond the whole lease plus the 100-tick fade.
    // Capture-to-apply timer delay is explicit; exact inverse repays its aging.
    if(before.thundering()||before.thunder()<=Math.addExact(duration,100)||l.getThunderLevel(1.0F)>0)
     throw new IllegalStateException("RAIN needs an observed non-thundering countdown beyond lease and fade");
    yield new State(0,duration,before.thunder(),true,false);
   }
   case "minecraft:thunder"->new State(0,duration,duration,true,true);
   default->throw new IllegalArgumentException("unknown weather profile");
  };
  var pre=before.blob();var post=target!=null&&c.cycle()?deadlines(after,c.tick()):after.blob();
  var stop=c.cycle()?new Stop(before,c).blob(TIMED):pre;
  var ownerBlob=BlobContent.capture(OWNER,1,new ModelCodec<>(OwnershipRecord.class).encode(owner));var clockBlob=c.blob();
  var snapshots=new HashSet<BlobRef>(Set.of(pre.reference(),ownerBlob.reference(),clockBlob.reference()));
  var all=new LinkedHashMap<BlobRef,BlobContent>();for(var b:List.of(pre,post,stop,ownerBlob,clockBlob))all.put(b.reference(),b);
  if(target!=null){snapshots.add(target.reference());all.put(target.reference(),target);}
  var read=new ReadSet(context.snapshot().scopeRevisions(),Map.of(k,owner.epoch()),Map.of(k,pre.reference().hash()));
  var budget=new BudgetVector(0,0,0,0,0,1,0,0,0,0);var claim=new ResourceClaim(k,ClaimPolicy.EXCLUSIVE,budget,Optional.empty(),owner.epoch());
  var refs=new EffectBlobs(snapshots,post.reference(),stop.reference(),Map.of(k,pre.reference()),Map.of(k,post.reference()));
  var plan=new PreparedEffect(intent,context.snapshot(),List.of(claim),read,Map.of(k,post.reference().hash()),budget,List.of(),capabilities(),refs);
  return new EffectPreparation(intent,new ValidationResult(true,List.of(),read),Optional.of(plan),new BlobBundle(List.copyOf(all.values())));
 }
 public boolean revalidate(PreparedEffect plan,BlobBundle blobs){
  if(alternative!=null)return alternative.revalidate(plan,blobs);
  var a=args(plan.intent());var l=level(a.dimension());var k=key(a.dimension());var owner=ownership.get().records().get(k);
  if(!WeatherOwnershipFence.sameOwner(capturedOwner(plan,blobs,k),owner)||!owner.epoch().equals(plan.preconditions().epochs().get(k))||!a.externalRevision().equals(externalRevision.apply(a.dimension())))return false;
  var before=State.read(find(blobs,plan.durableBlobs().preStates().get(k)));
  if(!before.blob().reference().hash().equals(plan.preconditions().fingerprints().get(k)))return false;
  if(a.weather().value().equals("minecraft:rain")&&clock(l).cycle()){
   var actual=state(l);
   if(actual.thundering()||actual.thunder()<=Math.addExact(Math.toIntExact(a.duration().value()),100)||l.getThunderLevel(1.0F)>0)return false;
  }
  var witness=snapshot(plan,blobs,CLOCK);var current=clock(l);
  // Legacy pure-planner plans cannot certify live timer movement without a capture clock.
  if(witness.isEmpty())return !current.cycle()&&before.equals(state(l));
  if(!natural(before,Clock.read(witness.orElseThrow()),state(l),current))return false;
  var target=snapshot(plan,blobs,TARGET);
  if(target.isEmpty()||!current.cycle())return true;
  try{return deadlines(age(Stop.read(target.orElseThrow()),current),current.tick()).reference().hash().equals(plan.expectedPost().get(k));}
  catch(IllegalStateException entropyOrClockBoundary){return false;}
 }
 public SliceResult apply(PreparedEffect plan,BlobBundle blobs){
  if(alternative!=null)return alternative.apply(plan,blobs);
  if(!revalidate(plan,blobs))throw new IllegalStateException("weather changed before write");
  var a=args(plan.intent());var l=level(a.dimension());var k=key(a.dimension());var at=clock(l);
  var target=snapshot(plan,blobs,TARGET);
  var desired=target.isPresent()?age(Stop.read(target.orElseThrow()),at):State.read(find(blobs,plan.durableBlobs().expectedPostStates().get(k)));
  l.setWeatherParameters(desired.clear(),desired.rain(),desired.raining(),desired.thundering());
  ((ServerLevelData)l.getLevelData()).setThunderTime(desired.thunder());
  var actual=state(l);
  // Observed fingerprint comes from the native readback, never the expected hash itself.
  var observed=target.isPresent()&&at.cycle()?deadlines(actual,at.tick()).reference().hash():actual.blob().reference().hash();
  restoreTargets.remove(plan.intent().operationToken());
  return new SliceResult(actual.equals(desired)&&observed.equals(plan.expectedPost().get(k))?Outcome.SUCCEEDED:Outcome.PARTIAL,Set.of(plan.intent().operationToken()),Map.of(k,observed),0,Set.of(),Set.of(),List.of());
 }
 public ReconcileResult reconcile(RecoveryProbe probe){
  if(alternative!=null)return alternative.reconcile(probe);
  var plan=probe.durableIntent();var a=args(plan.intent());var k=key(a.dimension());
  var proofs=probe.loadedProofs().stream().filter(p->p.resource().equals(k)).toList();
  if(proofs.isEmpty())return new ReconcileResult(ReconcileStatus.UNAVAILABLE,List.of(),List.of());
  if(proofs.size()!=1)return new ReconcileResult(ReconcileStatus.AMBIGUOUS,proofs,List.of());
  var proof=proofs.getFirst();var owner=ownership.get().records().get(k);
  if(owner==null)return new ReconcileResult(ReconcileStatus.UNAVAILABLE,proofs,List.of());
  if(owner.disposition()==OwnershipDisposition.YIELDED_TO_PLAYER||!owner.playerOverrideEpoch().equals(proof.playerOverrideEpoch())||proof.playerOverrideEpoch().value()>0)return new ReconcileResult(ReconcileStatus.PLAYER_OVERRIDE,proofs,List.of());
  if(!WeatherOwnershipFence.sameOwner(capturedOwner(plan,probe.blobs(),k),owner)||!owner.epoch().equals(plan.preconditions().epochs().get(k))||!proof.ownerEpoch().equals(owner.epoch())||proof.ownerToken().filter(owner.ownerTokens()::contains).isEmpty()||!a.externalRevision().equals(externalRevision.apply(a.dimension())))return new ReconcileResult(ReconcileStatus.AMBIGUOUS,proofs,List.of());
  var l=level(a.dimension());var actual=state(l);var post=plan.durableBlobs().expectedPostStates().get(k);
  var observed=post.codecId().equals(DEADLINES)?deadlines(actual,l.getGameTime()).reference().hash():actual.blob().reference().hash();
  var status=ReconcileStatus.AMBIGUOUS;
  if(proof.status()==EvidenceStatus.CONFIRMED&&proof.observedHash().equals(Optional.of(observed))){
   if(observed.equals(plan.expectedPost().get(k)))status=ReconcileStatus.SAME_POST;
   else if(actual.blob().reference().hash().equals(plan.preconditions().fingerprints().get(k))&&proof.localAbsenceProven())status=ReconcileStatus.SAME_PRE_PROVEN;
  }
  return new ReconcileResult(status,proofs,List.of());
 }
}
