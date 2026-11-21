package fr.proutlost.director.engine;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Three-valued, work-bounded conditions. In particular NOT UNKNOWN remains UNKNOWN. */
public final class ConditionEvaluator {
    public enum Truth { TRUE, FALSE, UNKNOWN }
    public Truth evaluate(Condition condition, CandidateGenerator.Generated candidate, DecisionInput input) {
        return evaluateScope(condition,candidate.region(),candidate.roles(),candidate.scopes(),input);
    }
    /** Chains without a child event still evaluate the same conditions on captured
     * scope participants. No event definition or synthetic candidate is required. */
    public Truth evaluateScope(Condition condition, Optional<RegionId> region, RoleAssignments roles,
                               ScopeSet scopes, DecisionInput input) {
        int cap = Math.toIntExact(new EngineParameters(input.config()).ticks("scheduler.candidates_per_window"));
        return evaluate(condition, new Subject(region,roles,scopes), input, new int[]{cap});
    }
    private record Subject(Optional<RegionId> region,RoleAssignments roles,ScopeSet scopes) {}
    private Truth evaluate(Condition condition, Subject candidate, DecisionInput input, int[] budget) {
        if (--budget[0] < 0) return Truth.UNKNOWN;
        return switch (condition) {
            case AllConditions all -> combine(all.conditions(), true, candidate, input, budget);
            case AnyConditions any -> combine(any.conditions(), false, candidate, input, budget);
            case NotCondition not -> switch (evaluate(not.condition(), candidate, input, budget)) {
                case TRUE -> Truth.FALSE; case FALSE -> Truth.TRUE; case UNKNOWN -> Truth.UNKNOWN;
            };
            case PlayersPresent present -> {
                if (!known(candidate, input, SensorKind.PLAYER_PRESENCE)) yield Truth.UNKNOWN;
                Set<PlayerId> players = players(candidate);
                boolean complete = players.stream().allMatch(id -> input.context().context().players().containsKey(id));
                yield !complete ? Truth.UNKNOWN : truth(players.size() >= present.minimum());
            }
            case RegionTag tag -> {
                var region = candidate.region().map(input.context().context().regions()::get);
                yield region.isEmpty() || !known(candidate, input, SensorKind.REGION_TAGS) ? Truth.UNKNOWN
                    : truth(region.orElseThrow().tags().contains(tag.tag()));
            }
            case PatternConfidence pattern -> {
                var region = candidate.region().map(input.context().context().regions()::get);
                yield region.isEmpty() || !known(candidate, input, SensorKind.CAMP_ACTIVITY) ? Truth.UNKNOWN
                    : truth(region.orElseThrow().camps().stream().anyMatch(c -> c.confidence().value() >= pattern.minimum().value()));
            }
            case SoloReady solo -> {
                if (!known(candidate, input, SensorKind.PLAYER_PRESENCE)) yield Truth.UNKNOWN;
                var players = players(candidate);
                if (players.size() != 1) yield Truth.FALSE;
                var player = input.context().context().players().get(players.iterator().next());
                yield player == null ? Truth.UNKNOWN : truth(player.isolatedTicks().value() >= solo.minimumIsolatedTicks().value());
            }
            case RiskMinimum risk -> {
                var region = candidate.region().map(input.context().context().regions()::get);
                var value = region.map(r -> r.risks().get(risk.riskId()));
                yield value.isEmpty() || !knownRegionalRisk(candidate.region().orElseThrow(), risk.riskId(), input) ? Truth.UNKNOWN
                    : truth(value.orElseThrow().value() >= risk.minimum().value());
            }
            case RequirementPending pending -> {
                boolean unresolvedSelector = false, matched = false;
                for (var view : input.requests()) {
                    if (view.state().kind() != RequestKind.REQUIRE || !pending(view.state().status())) continue;
                    if (!candidate.scopes().scopes().contains(view.request().scope())) continue;
                    var constraints = view.state().constraints();
                    if (!constraints.playerIds().isEmpty() && !players(candidate).containsAll(constraints.playerIds())) continue;
                    if (!constraints.regionIds().isEmpty() && (candidate.region().isEmpty()
                        || !constraints.regionIds().contains(candidate.region().orElseThrow()))) continue;
                    if (view.state().selector() instanceof AnyOfEvents ids) matched |= ids.eventIds().contains(pending.selector());
                    else unresolvedSelector = true;
                }
                yield matched ? Truth.TRUE : unresolvedSelector ? Truth.UNKNOWN : Truth.FALSE;
            }
            case MemoryFlag flag -> {
                var memories = input.memory().regions().stream().filter(m -> candidate.region().filter(m.regionId()::equals).isPresent()).toList();
                if (memories.size() != 1) yield Truth.UNKNOWN;
                boolean present = memories.getFirst().persistentStateRefs().stream().anyMatch(id -> id.value().equals(flag.flag().value()));
                yield truth(present == flag.expected());
            }
        };
    }
    private Truth combine(List<Condition> conditions, boolean all, Subject candidate,
                          DecisionInput input, int[] budget) {
        boolean unknown = false;
        for (var child : conditions) {
            Truth value = evaluate(child, candidate, input, budget);
            if (all && value == Truth.FALSE) return Truth.FALSE;
            if (!all && value == Truth.TRUE) return Truth.TRUE;
            unknown |= value == Truth.UNKNOWN;
            if (budget[0] < 0) return Truth.UNKNOWN;
        }
        return unknown ? Truth.UNKNOWN : all ? Truth.TRUE : Truth.FALSE;
    }
    /** Positive RISK knowledge is local to this exact region and risk key. A
     * globally incomplete risk census remains UNKNOWN for world-wide pacing;
     * another unmeasured region cannot erase this measured regional value.
     * Other sensors keep known()'s existing global-missing veto. */
    public static boolean knownRegionalRisk(RegionId regionId, ResourceId riskId, DecisionInput input) {
        Objects.requireNonNull(regionId); Objects.requireNonNull(riskId); Objects.requireNonNull(input);
        var batch = input.context();
        var scope = new ScopeRef(ScopeKind.REGION, regionId.value());
        var snapshot = batch.context().scopes().get(scope);
        var region = batch.context().regions().get(regionId);
        return batch.scopes().scopes().contains(scope)
            && snapshot != null && snapshot.scope().equals(scope)
            && Objects.equals(batch.scopeRevisions().get(scope), snapshot.revision())
            && snapshot.availableSensors().contains(SensorKind.RISK)
            && !snapshot.missingSensors().contains(SensorKind.RISK)
            && region != null && region.region().regionId().equals(regionId)
            && region.risks().get(riskId) != null;
    }
    static Set<PlayerId> players(CandidateGenerator.Generated candidate) {
        return players(new Subject(candidate.region(),candidate.roles(),candidate.scopes()));
    }
    private static Set<PlayerId> players(Subject candidate) {
        Set<PlayerId> players = new HashSet<>();
        candidate.roles().roles().values().forEach(players::addAll);
        return Set.copyOf(players);
    }
    static boolean known(CandidateGenerator.Generated candidate, DecisionInput input, SensorKind sensor) {
        return known(new Subject(candidate.region(),candidate.roles(),candidate.scopes()),input,sensor);
    }
    private static boolean known(Subject candidate, DecisionInput input, SensorKind sensor) {
        if (input.context().missingData().contains(sensor)) return false;
        var snapshots = input.context().context().scopes();
        ScopeKind kind = switch (sensor) {
            case REGION_TAGS, REGION_TRAITS, REGION_MEMBERSHIP, CAMP_ACTIVITY, RISK, TERRAIN, ANCHORS, LOCATIONS -> ScopeKind.REGION;
            case HEALTH -> ScopeKind.WORLD;
            default -> ScopeKind.PLAYER;
        };
        var relevant = candidate.scopes().scopes().stream().filter(s -> s.kind() == kind).toList();
        return !relevant.isEmpty() && relevant.stream().allMatch(scope -> {
            var snapshot = snapshots.get(scope);
            return snapshot != null && snapshot.availableSensors().contains(sensor) && !snapshot.missingSensors().contains(sensor);
        });
    }
    private static boolean pending(RequestStatus status) {
        return status == RequestStatus.ACCEPTED || status == RequestStatus.WAITING_CONTEXT || status == RequestStatus.WAITING_SAFETY;
    }
    private static Truth truth(boolean value) { return value ? Truth.TRUE : Truth.FALSE; }
}
