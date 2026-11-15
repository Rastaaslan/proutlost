package fr.proutlost.director.api.model;
import java.util.Objects;
/** Definition identity keeps the authored weather, dimension and duration. The external
 * revision is the fence sampled by the native planner, sealed with its weather read set.
 * This comparison grants no apply authority: current revision/owner/native witnesses
 * remain mandatory in capture, admission and ServerEffectPort revalidation. Cold plans
 * keep their original sampled fence; they are never rebound or replayed here. */
public final class ObservedWeatherArguments {
 private ObservedWeatherArguments(){}
 public static boolean matches(PrimitiveArgs configured,PrimitiveArgs sampled,ReadSet read){
  Objects.requireNonNull(configured);Objects.requireNonNull(sampled);Objects.requireNonNull(read);
  if(configured.equals(sampled))return true;
  if(!(configured instanceof GlobalWeatherLease a)||!(sampled instanceof GlobalWeatherLease b))return false;
  var key=new ResourceKey(OwnershipDomain.WEATHER,a.dimension(),"weather");
  return a.dimension().equals(b.dimension())&&a.weather().equals(b.weather())&&a.duration().equals(b.duration())
   &&read.epochs().containsKey(key)&&read.fingerprints().containsKey(key);
 }
}
