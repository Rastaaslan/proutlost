package fr.proutlost.director.runtime;
import fr.proutlost.director.api.model.*;
/** Completion needs the full original native duration and a durable physically
 * closed weather mutation. Application, mere passage of time, or yielding are
 * retained evidence but cannot establish a fully completed weather program. */
public final class WeatherEventCompletion {
 private WeatherEventCompletion(){}
 public static boolean fullDuration(long nativeNow,long originalDeadline,boolean applied,boolean conflict,MutationRecord mutation){
  return applied&&!conflict&&originalDeadline>0&&nativeNow>=originalDeadline&&mutation!=null
    &&mutation.primitiveId().equals(new PrimitiveId("proutlost:global_weather_lease"))
    &&mutation.state()==MutationState.ROLLED_BACK&&mutation.durableSeq().value()>0;
 }
}
