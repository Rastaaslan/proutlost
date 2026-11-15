package fr.proutlost.director.runtime;
import fr.proutlost.director.api.model.*;import java.util.*;
/** Regression: a boot/foreign fence cannot freeze an approved weather definition forever;
 * binding that observed fence must not authorize a changed effect or lose weather guards. */
public final class ObservedWeatherArgumentsTests {
 static int checks;static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 static GlobalWeatherLease lease(String dim,String type,long duration,long revision){return new GlobalWeatherLease(new DimensionId(dim),new ResourceId(type),new DurationTicks(duration),new Revision(revision));}
 public static void main(String[] args){
  var authored=lease("minecraft:overworld","minecraft:rain",400,0);var observed=lease("minecraft:overworld","minecraft:rain",400,55834574850L);
  var key=new ResourceKey(OwnershipDomain.WEATHER,authored.dimension(),"weather");var hash=BlobContent.hash(new byte[]{1,2,3});
  var guarded=new ReadSet(Map.of(),Map.of(key,new Epoch(1)),Map.of(key,hash));var empty=new ReadSet(Map.of(),Map.of(),Map.of());
  check(ObservedWeatherArguments.matches(authored,observed,guarded),"same approved effect accepts a new sampled boot fence with weather guards");
  check(ObservedWeatherArguments.matches(authored,authored,empty),"existing exact closed arguments retain compatibility");
  check(!ObservedWeatherArguments.matches(authored,observed,empty),"unsealed fence change rejected");
  check(!ObservedWeatherArguments.matches(authored,observed,new ReadSet(Map.of(),Map.of(key,new Epoch(1)),Map.of())),"missing native fingerprint rejected");
  check(!ObservedWeatherArguments.matches(authored,observed,new ReadSet(Map.of(),Map.of(),Map.of(key,hash))),"missing owner epoch rejected");
  for(var changed:List.of(lease("minecraft:the_end","minecraft:rain",400,55834574850L),lease("minecraft:overworld","minecraft:thunder",400,55834574850L),lease("minecraft:overworld","minecraft:rain",401,55834574850L),lease("minecraft:overworld","proutlost_director:restore",400,55834574850L)))
   check(!ObservedWeatherArguments.matches(authored,changed,guarded),"altered dimension/type/duration/restore cannot be authorized");
  var foreign=new ResourceKey(OwnershipDomain.WEATHER,new DimensionId("minecraft:the_end"),"weather");
  check(!ObservedWeatherArguments.matches(authored,observed,new ReadSet(Map.of(),Map.of(foreign,new Epoch(1)),Map.of(foreign,hash))),"other dimension guards cannot authorize binding");
  check(authored.externalRevision().value()==0&&authored.duration().value()==400,"approved definition was not mutated");
  System.out.println("OBSERVED_WEATHER_ARGUMENTS_PASS assertions="+checks+" scope=PLANNING_FENCE_NOT_APPLY_AUTHORITY");
 }
}
