package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
public record PlannedPrimitive(PlanningBindings bindings,PreparedEffect effect) implements PlanningResult {
 public PlannedPrimitive {Objects.requireNonNull(bindings);Objects.requireNonNull(effect);require(bindings.audienceComplete(),"incomplete incidental audience");require(effect.intent().instanceId().equals(bindings.instanceId())&&effect.intent().operationToken().equals(bindings.operationToken())&&effect.intent().expectedFootprint().equals(bindings.footprint()),"planned identity/footprint");require(effect.snapshot().scopes().worldId().equals(bindings.scopes().worldId())&&effect.snapshot().context().players().keySet().containsAll(bindings.incidentalAudience()),"planned incidental context");require(bindings.roles().roles().values().stream().allMatch(v->effect.snapshot().context().players().keySet().containsAll(v)),"planned role context");}
}
