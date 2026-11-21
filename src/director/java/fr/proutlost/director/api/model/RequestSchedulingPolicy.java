package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** LINEAR_TO_TTL multiplier clamps (expires-now)/(expires-created) to [0,1].
 * created/expires come from original DirectorRequest/DirectorPreference, not a content half-life.
 * REQUIRE ordering uses priority then existing eligibleWaitTicks and normative age cap.
 */
public record RequestSchedulingPolicy(NarrativePriority narrativePriority,PreferenceDecay preferenceDecay,RequestPolicySource source) {
 public RequestSchedulingPolicy {Objects.requireNonNull(narrativePriority);Objects.requireNonNull(preferenceDecay);Objects.requireNonNull(source);require(source!=RequestPolicySource.LEGACY_WIRE_DEFAULT||narrativePriority.equals(NarrativePriority.NORMAL),"wire default priority");}
 /** Exact declared decay; invalid/zero TTL is rejected, never an unrelated half-life. */
 public UnitValue preferenceMultiplier(Tick created,Tick expires,Tick now){
  require(expires.value()>created.value(),"positive preference TTL");
  if(now.value()<=created.value())return new UnitValue(1);
  if(now.value()>=expires.value())return new UnitValue(0);
  return new UnitValue((double)(expires.value()-now.value())/(expires.value()-created.value()));
 }
 public static RequestSchedulingPolicy legacyWire(){return new RequestSchedulingPolicy(NarrativePriority.NORMAL,PreferenceDecay.LINEAR_TO_TTL,RequestPolicySource.LEGACY_WIRE_DEFAULT);}
}
