package fr.proutlost.director.runtime;
import fr.proutlost.director.api.model.*;import java.util.*;
public final class WeatherEventCompletionTests {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static MutationRecord mutation(MutationState state,long seq,String primitive){
  var id=new MutationId(UUID.randomUUID());var dim=new DimensionId("minecraft:overworld");
  var ref=BlobContent.capture(new ResourceId("test:native_witness"),1,new byte[]{1}).reference();
  return new MutationRecord(new AggregateHeader(1,id.value(),new Revision(1),new Sequence(0),new Tick(10)),id,new EventInstanceId(UUID.randomUUID()),new SliceId(UUID.randomUUID()),state,new PrimitiveId(primitive),1,new Footprint(dim,Set.of(),Set.of(),List.of()),ref,ref,new OwnerToken(UUID.randomUUID()),new Epoch(1),new ReadSet(Map.of(),Map.of(),Map.of()),Set.of(),List.of(),Optional.empty(),new Sequence(seq));
 }
 public static void main(String[] args){
  var closed=mutation(MutationState.ROLLED_BACK,7,"proutlost:global_weather_lease");
  check(WeatherEventCompletion.fullDuration(100,100,true,false,closed),"exact original deadline and closed durable effect");
  check(WeatherEventCompletion.fullDuration(101,100,true,false,closed),"observed after original deadline");
  check(!WeatherEventCompletion.fullDuration(99,100,true,false,closed),"early inverse/reset is an abort, not full-duration success");
  check(!WeatherEventCompletion.fullDuration(100,0,true,false,closed),"no fabricated deadline");
  check(!WeatherEventCompletion.fullDuration(100,100,false,false,closed),"no application inferred from a timer");
  check(!WeatherEventCompletion.fullDuration(100,100,true,true,closed),"conflict retained");
  check(!WeatherEventCompletion.fullDuration(100,100,true,false,null),"no missing mutation promoted");
  check(!WeatherEventCompletion.fullDuration(100,100,true,false,mutation(MutationState.ROLLED_BACK,0,"proutlost:global_weather_lease")),"unforced closure is not success");
  for(var state:List.of(MutationState.COMMITTED,MutationState.VERIFIED,MutationState.YIELDED))
   check(!WeatherEventCompletion.fullDuration(100,100,true,false,mutation(state,7,"proutlost:global_weather_lease")),"still owned or yielded mutation "+state);
  check(!WeatherEventCompletion.fullDuration(100,100,true,false,mutation(MutationState.ROLLED_BACK,7,"proutlost:regional_weather_lease")),"foreign effect cannot close global program");
  System.out.println("WEATHER_EVENT_COMPLETION_PASS assertions="+checks+" scope=TERMINAL_POLICY_NOT_NATIVE_RUNTIME");
 }
}
