package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
/** J05 supplies this pure registered primitive/footprint planner to J03. No world, IO, callbacks
 * or mutable global cache. Resolve roles/identities from bindings; retain complete incidental
 * snapshot. Missing binding/sensor/coverage yields DeferredPlanning with targeted SampleRequest.
 * Delegate to normative DirectorPrimitive.plan(args,PlanningSnapshot) only after evidence exists.
 */
public interface PlanningPort { PlanningResult plan(PlanningRequest request); }
