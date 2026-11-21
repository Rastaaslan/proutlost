package fr.proutlost.director.runtime;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.util.*;

/** Pure registry dispatch. Keeps the complete incidental audience in the immutable snapshot. */
public final class PrimitivePlanningService implements PlanningPort {
 public record Registration<A extends PrimitiveArgs>(Class<A> arguments,DirectorPrimitive<A> primitive,Set<SensorKind> sensors) {
  public Registration {Objects.requireNonNull(arguments);Objects.requireNonNull(primitive);sensors=Set.copyOf(sensors);}
  PreparedEffect plan(PrimitiveArgs args,PlanningSnapshot context){return primitive.plan(arguments.cast(args),context);}
 }
 private final Map<PrimitiveId,Registration<?>> registrations;
 public PrimitivePlanningService(Collection<Registration<?>> registrations,ProviderMode mode){
  var map=new HashMap<PrimitiveId,Registration<?>>();
  for(var registration:registrations){
   var descriptor=registration.primitive().descriptor();
   if(mode==ProviderMode.PRODUCTION&&descriptor.mode()!=ProviderMode.PRODUCTION)throw new IllegalArgumentException("TEST_ONLY planner");
   if(map.putIfAbsent(descriptor.id(),registration)!=null)throw new IllegalArgumentException("duplicate primitive");
  }
  this.registrations=Map.copyOf(map);
 }
 private static DeferredPlanning defer(PlanningSnapshot context,Set<SensorKind> sensors,ReasonCode reason){
  return new DeferredPlanning(List.of(new SampleRequest(context.snapshot().requestId(),context.snapshot().scopes(),sensors,context.budgets().capacity())),List.of(reason));
 }
 public PlanningResult plan(PlanningRequest request){
  var context=request.snapshot();var binding=context.bindings();
  if(binding.isEmpty()||!binding.get().audienceComplete())return defer(context,Set.of(SensorKind.PLAYER_PRESENCE,SensorKind.PLAYER_ELIGIBILITY,SensorKind.REGION_MEMBERSHIP),ReasonCode.NO_TARGET);
  var b=binding.orElseThrow();var snapshot=context.snapshot();
  var registration=registrations.get(request.step().primitive());
  if(registration==null)throw new IllegalArgumentException("CAPABILITY_MISSING: unregistered primitive");
  if(!registration.arguments().isInstance(request.step().args()))throw new IllegalArgumentException("primitive argument type");
  var missing=new HashSet<>(snapshot.missingData());missing.retainAll(registration.sensors());
  if(!missing.isEmpty())return defer(context,missing,ReasonCode.STALE_CONTEXT);
  if(!snapshot.loadedChunks().containsAll(b.footprint().loadedChunks()))return defer(context,Set.of(SensorKind.TERRAIN),ReasonCode.CHUNK_UNAVAILABLE);
  if(snapshot.serverHealth()!=HealthMode.NORMAL&&snapshot.serverHealth()!=HealthMode.DEGRADED)return defer(context,Set.of(SensorKind.HEALTH),ReasonCode.STALE_CONTEXT);
  var effect=registration.plan(request.step().args(),context);var descriptor=registration.primitive().descriptor();
  if(!effect.snapshot().equals(snapshot)||!effect.intent().primitive().equals(descriptor.id())||effect.intent().version()!=descriptor.version()
    ||!ObservedWeatherArguments.matches(request.step().args(),effect.intent().typedArgs(),effect.preconditions())||!effect.capabilities().containsAll(descriptor.capabilities()))throw new IllegalArgumentException("primitive changed snapshot, arguments or descriptor");
  if(!BudgetMath.fits(BudgetMath.add(context.budgets().reserved(),effect.budgetEstimate()),context.budgets().capacity()))throw new IllegalArgumentException("BUDGET_EXHAUSTED");
  return new PlannedPrimitive(b,effect);
 }
}
