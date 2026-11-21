package fr.proutlost.director.engine;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.port.PlanningPort;
import java.util.*;

/** Consumes actor-captured evidence, never manufactures an effect footprint or world authority. */
public final class CandidatePlanner {
    private final PlanningPort port;
    private final Map<CandidateId, List<PlanningSnapshot>> captured;
    public CandidatePlanner(PlanningPort port, Map<CandidateId, List<PlanningSnapshot>> captured) {
        this.port = Objects.requireNonNull(port);
        Map<CandidateId, List<PlanningSnapshot>> copy = new HashMap<>();
        captured.forEach((id, snapshots) -> copy.put(id, List.copyOf(snapshots)));
        this.captured = Map.copyOf(copy);
    }
    public record Result(Optional<ExecutionPlan> plan, List<ReasonCode> reasons, List<SampleRequest> observations) {
        public Result { reasons = List.copyOf(reasons); observations = List.copyOf(observations); }
    }
    private static Result rejected(ReasonCode reason) { return new Result(Optional.empty(), List.of(reason), List.of()); }
    public Result plan(CandidateGenerator.Generated candidate, DecisionInput input) {
        var snapshots = captured.get(candidate.id());
        if (snapshots == null || snapshots.size() != candidate.definition().steps().size()) return rejected(ReasonCode.STALE_CONTEXT);
        List<EffectIntent> intents = new ArrayList<>();
        Map<ScopeRef, Revision> revisions = new HashMap<>(input.context().scopeRevisions());
        Map<ResourceKey, Epoch> epochs = new HashMap<>();
        Map<ResourceKey, Fingerprint> fingerprints = new HashMap<>();
        EventInstanceId instance = null;
        Set<OperationToken> operations = new HashSet<>();
        for (int i = 0; i < snapshots.size(); i++) {
            var snapshot = snapshots.get(i);
            var step = candidate.definition().steps().get(i);
            var binding = snapshot.bindings().orElse(null);
            if(binding!=null&&operations.contains(binding.operationToken()))return rejected(ReasonCode.STALE_CONTEXT);
            var safety=validateCapturedSnapshot(candidate,input,snapshot,step);
            if(!safety.valid())return new Result(Optional.empty(),safety.reasons(),List.of());
            if(!operations.add(binding.operationToken()))return rejected(ReasonCode.STALE_CONTEXT);
            if (instance != null && !instance.equals(binding.instanceId())) return rejected(ReasonCode.STALE_CONTEXT);
            instance = binding.instanceId();
            var result = port.plan(new PlanningRequest(step, snapshot));
            if (result instanceof DeferredPlanning deferred) return new Result(Optional.empty(), deferred.reasons(), deferred.observations());
            if (!(result instanceof PlannedPrimitive planned) || !planned.bindings().equals(binding)) return rejected(ReasonCode.STALE_CONTEXT);
            var effect = planned.effect();
            if (!effect.snapshot().equals(input.context()) || !effect.intent().primitive().equals(step.primitive())
                || !ObservedWeatherArguments.matches(step.args(),effect.intent().typedArgs(),effect.preconditions())
                || !BudgetMath.fits(effect.budgetEstimate(), candidate.definition().budget())) return rejected(ReasonCode.STALE_CONTEXT);
            if (!merge(revisions, effect.preconditions().revisions()) || !merge(epochs, effect.preconditions().epochs())
                || !merge(fingerprints, effect.preconditions().fingerprints())) return rejected(ReasonCode.STALE_CONTEXT);
            intents.add(effect.intent());
        }
        return new Result(Optional.of(new ExecutionPlan(candidate.id(), candidate.reference(), intents, candidate.roles(), candidate.scopes(),
            new ReadSet(revisions, epochs, fingerprints))), List.of(), List.of());
    }
    /** Shared pure preflight before an actor invokes its registered planning
     * primitive. The final worker repeats it over the same immutable evidence. */
    public static ValidationResult validateCapturedSnapshot(CandidateGenerator.Generated candidate,DecisionInput input,
            PlanningSnapshot snapshot,EventStep step){
        var binding=snapshot.bindings().orElse(null);
        if(binding==null||!binding.audienceComplete()||!binding.candidateId().equals(candidate.id())
            ||!binding.definition().equals(candidate.reference())||!binding.roles().equals(candidate.roles())
            ||!binding.scopes().equals(candidate.scopes())||!binding.stepId().equals(step.id())
            ||!snapshot.snapshot().equals(input.context())||!snapshot.ownership().equals(input.state().ownership())
            ||!snapshot.budgets().equals(input.state().budgets())||!input.context().loadedChunks().containsAll(binding.footprint().loadedChunks()))
            return new ValidationResult(false,List.of(ReasonCode.STALE_CONTEXT),new ReadSet(Map.of(),Map.of(),Map.of()));
        return new EligibilityEngine(ProviderMode.TEST_ONLY).validateAudience(candidate,binding.incidentalAudience(),input);
    }
    private static <K,V> boolean merge(Map<K,V> target, Map<K,V> source) {
        for (var entry : source.entrySet()) {
            var previous = target.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) return false;
        }
        return true;
    }
}
