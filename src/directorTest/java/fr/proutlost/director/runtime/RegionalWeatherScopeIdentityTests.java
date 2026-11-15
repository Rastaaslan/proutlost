package fr.proutlost.director.runtime;
import fr.proutlost.director.api.model.*;import java.util.*;
/** Real J04 model regression: normal regional activity is not a geometry edit. */
public final class RegionalWeatherScopeIdentityTests {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static final RegionId ID=new RegionId(UUID.fromString("b8e35f5e-d753-4bcd-b562-763dba4e743f"));static final DimensionId DIM=new DimensionId("minecraft:overworld");
 static RegionRecord region(long header,long membership,int edge,RegionStatus status){
  return new RegionRecord(new AggregateHeader(1,ID.value(),new Revision(header),new Sequence(header),new Tick(header)),ID,DIM,status,
   new SupportShape(List.of(new Aabb(DIM,-32,-64,-32,edge,320,edge)),List.of(),List.of()),Set.of(),new UnitValue(header==1?0:.5),Set.of(),Set.of(),new Revision(membership),new RegionPersonality(Map.of()),Set.of(new MemoryId(ID.value())));
 }
 public static void main(String[] args)throws Exception{
  var original=region(1,1,32,RegionStatus.ACTIVE);var activity=region(99,1,32,RegionStatus.ACTIVE);var topology=region(100,2,32,RegionStatus.ACTIVE);var geometry=region(100,1,48,RegionStatus.ACTIVE);
  var lease=new RegionalWeatherLease(DIM,new ResourceId("proutlost_director:regional_mist"),new DurationTicks(600),new Revision(1));
  check(RegionalWeatherScopeIdentity.matches(original,lease),"authored physical region version admitted");
  check(RegionalWeatherScopeIdentity.matches(activity,lease),"real unrelated aggregate activity revision does not revoke physical scope");
  check(!RegionalWeatherScopeIdentity.matches(topology,lease),"changed real membership version refused");
  check(RegionalWeatherScopeIdentity.fingerprint(original).equals(RegionalWeatherScopeIdentity.fingerprint(activity)),"activity preserves physical authorization");
  check(!RegionalWeatherScopeIdentity.fingerprint(original).equals(RegionalWeatherScopeIdentity.fingerprint(topology)),"membership change invalidates pinned authorization");
  check(!RegionalWeatherScopeIdentity.fingerprint(original).equals(RegionalWeatherScopeIdentity.fingerprint(geometry)),"shape drift invalidates authorization even with stale membership counter");
  check(!RegionalWeatherScopeIdentity.matches(region(100,1,32,RegionStatus.ARCHIVED),lease),"inactive real region refused");
  check(!RegionalWeatherScopeIdentity.matches(original,new RegionalWeatherLease(new DimensionId("minecraft:the_nether"),lease.profile(),lease.duration(),lease.externalRevision())),"foreign dimension refused");
  check(activity.header().revision().value()==99&&activity.membershipRevision().value()==1,"both actual revision domains retained, neither fabricated");
  var raw=RegionalWeatherScopeIdentityTests.class.getResourceAsStream("/qa/actual-two-client-snapshot-b18r1.json").readAllBytes();
  var actual=new fr.proutlost.director.api.codec.ModelCodec<>(SnapshotBatch.class).decode(raw);
  var full=new Footprint(DIM,Set.of(),Set.of(),List.of(new Aabb(DIM,-32,-64,-32,32,320,32)));
  check(RegionalWeatherScopeIdentity.affectedAudience(actual,full).equals(Set.of(new PlayerId(UUID.fromString("93094eda-b7a6-39e1-ba24-b4e8ef1d05fd")),new PlayerId(UUID.fromString("c5e778b1-9f86-3cf9-ac22-d13c105f20ea")))),"real sealed snapshot includes both exposed clients independently of EVENT witness scope");
  var alphaOnly=new Footprint(DIM,Set.of(),Set.of(),List.of(new Aabb(DIM,0,60,0,1,80,1)));
  check(RegionalWeatherScopeIdentity.affectedAudience(actual,alphaOnly).equals(Set.of(new PlayerId(UUID.fromString("93094eda-b7a6-39e1-ba24-b4e8ef1d05fd")))),"exact original geometry excludes other real client");
  var foreign=new DimensionId("minecraft:the_nether");check(RegionalWeatherScopeIdentity.affectedAudience(actual,new Footprint(foreign,Set.of(),Set.of(),List.of(new Aabb(foreign,-32,-64,-32,32,320,32)))).isEmpty(),"foreign dimension never widens shared audience");
  var worldRaw=RegionalWeatherScopeIdentityTests.class.getResourceAsStream("/qa/actual-durable-world-b18r1.json").readAllBytes();
  var durable=new fr.proutlost.director.api.codec.ModelCodec<>(DirectorWorldState.class).decode(worldRaw);
  long at=durable.simTick().value()+20;
  var advanced=new DirectorWorldState(durable.header(),durable.worldId(),durable.storeGeneration(),new Tick(at),durable.runControl(),durable.healthMode(),durable.pacingState(),durable.tension(),durable.intensityProfileId(),durable.catalogHash(),durable.configHash(),durable.bootEpoch(),durable.lastCheckpointSeq(),durable.engineControl());
  check(RegionalWeatherScopeIdentity.sameDurableWorld(durable,advanced,new Tick(at)),"genuine NativeCatalogueInput clock projection preserves durable authorization");
  check(RegionalWeatherScopeIdentity.sameDurableWorld(durable,durable,durable.simTick()),"exact durable world still valid");
  check(!RegionalWeatherScopeIdentity.sameDurableWorld(durable,advanced,new Tick(at-1)),"future observation clock refused");
  var paused=new DirectorWorldState(durable.header(),durable.worldId(),durable.storeGeneration(),advanced.simTick(),RunControl.PAUSED,durable.healthMode(),durable.pacingState(),durable.tension(),durable.intensityProfileId(),durable.catalogHash(),durable.configHash(),durable.bootEpoch(),durable.lastCheckpointSeq(),durable.engineControl());
  check(!RegionalWeatherScopeIdentity.sameDurableWorld(durable,paused,new Tick(at)),"changed real run control never normalized away");
  System.out.println("REGIONAL_WEATHER_SCOPE_IDENTITY_PASS assertions="+checks+" scope=REAL_J04_MODELS_NOT_NATIVE");
 }
}
