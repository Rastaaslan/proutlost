package fr.proutlost.director.engine;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Extracts evidence available in G0, retaining explicit missing features for the affinity amendment. */
public final class FeatureExtractor {
    public record Features(Map<Feature, UnitValue> values, Set<Feature> missing, double repetitionPenalty, double habitationPenalty) {
        public Features { values = Map.copyOf(values); missing = Set.copyOf(missing); }
    }
    public Features extract(CandidateGenerator.Generated candidate, DecisionInput input) {
        Map<Feature, UnitValue> values = new EnumMap<>(Feature.class);
        long now = input.state().world().simTick().value();
        var histories = input.memory().operationalPlayers().stream().filter(m -> m.header().updatedTick().value() <= now
            && m.eventExposureRing().stream().allMatch(e -> e.tick().value() <= now)
            && m.roleExposureRing().stream().allMatch(e -> e.tick().value() <= now)).toList();
        var exposure = new ExposureFeatures(input.config()).evaluate(candidate.definition().id(), candidate.definition().families(),
            candidate.roles(), histories, Map.of(), input.state().world().simTick());
        values.put(Feature.novelty, exposure.novelty());
        values.put(Feature.playerSuitability, exposure.suitability());
        values.put(Feature.performanceFit, new UnitValue(headroom(candidate.definition().budget(), input.state().budgets())));
        var preference = preference(candidate, input);
        preference.ifPresent(value -> values.put(Feature.storyPreference, value));
        var profile = input.catalog().scoringProfiles().get(candidate.definition().id());
        if (profile != null && profile.definition().equals(candidate.reference())) {
            double total = 0, weight = 0;
            for (var affinity : profile.contextAffinities()) {
                var truth = new ConditionEvaluator().evaluate(affinity.predicate(), candidate, input);
                if (truth == ConditionEvaluator.Truth.TRUE) { total += affinity.affinity().value() * affinity.weight().value(); weight += affinity.weight().value(); }
            }
            if (weight > 0) values.put(Feature.contextCompatibility, new UnitValue(total / weight));
            var region = candidate.region().map(input.context().context().regions()::get);
            if (region.isPresent() && ConditionEvaluator.known(candidate, input, SensorKind.REGION_TRAITS)) {
                double sum = 0, count = 0;
                for (var affinity : profile.regionAffinities().entrySet()) {
                    var observed = region.orElseThrow().region().personality().traits().get(affinity.getKey());
                    if (observed != null) { sum += 1 - Math.abs(affinity.getValue().value() - observed.value()); count++; }
                }
                if (count > 0) values.put(Feature.regionPersonality, new UnitValue(sum / count));
            }
            var tension = profile.tensionAffinities().get(input.state().world().tension());
            if (tension != null) values.put(Feature.globalTension, tension);
            if (profile.desiredLocalIntensity().isPresent() && candidate.region().isPresent()) {
                var scope = new ScopeRef(ScopeKind.REGION, candidate.region().orElseThrow().value());
                var budget = input.state().budgets().scopes().get(scope);
                double capacity = new EngineParameters(input.config()).positive("budget.region_intensity");
                if (budget != null) values.put(Feature.localIntensityFit, new UnitValue(1 - Math.abs(profile.desiredLocalIntensity().orElseThrow().value() - Math.clamp(budget.regionalIntensity() / capacity, 0, 1))));
            }
        }
        double readiness = 1;
        boolean cooldownKnown = true;
        for (var cooldown : input.state().cooldowns()) {
            var match = new CooldownPolicy().applies(cooldown, candidate, input);
            if (match == CooldownPolicy.Match.UNKNOWN) { cooldownKnown = false; continue; }
            if (match == CooldownPolicy.Match.YES) readiness = Math.min(readiness, Math.clamp((double)(now - cooldown.notBeforeTick().value()) / new EngineParameters(input.config()).positive("score.cooldown_readiness_horizon_ticks"), 0, 1));
        }
        if (cooldownKnown) values.put(Feature.cooldownReadiness, new UnitValue(readiness));
        var relevant = input.memory().significantPlayers().stream().filter(m -> ConditionEvaluator.players(candidate).contains(m.playerId())
            && m.eventDefinitionId().equals(candidate.definition().id()) && m.recordedTick().value() <= now).toList();
        if (!input.memory().significantPlayers().isEmpty()) values.put(Feature.memoryRelevance, new UnitValue(relevant.isEmpty() ? 0 : 1));
        var linked = input.chains().stream().filter(c -> c.plannedHorizon().contains(new ResourceId(candidate.definition().id().value())))
            .map(ChainInstance::relevance).max(Comparator.comparingDouble(UnitValue::value));
        linked.ifPresent(v -> values.put(Feature.chainRelevance, v));
        double habitation = 0;
        if (profile != null && !Collections.disjoint(profile.habitationSensitiveFamilies(), candidate.definition().families())) {
            var camp = EvidenceSignals.component(input, MemoryComponent.CAMP, candidate.region());
            habitation = camp.orElse(new UnitValue(new EngineParameters(input.config()).unit("score.neutral_feature"))).value()
                * new EngineParameters(input.config()).nonnegative("score.habitation_penalty_cap");
        }
        Set<Feature> missing = EnumSet.allOf(Feature.class);
        missing.removeAll(values.keySet());
        // No candidate-specific affinity projection exists in G0. In particular, never reuse
        // one scope score as if it represented every definition's personality/tension affinity.
        return new Features(values, missing, exposure.repetitionPenalty(), habitation);
    }
    private static Optional<UnitValue> preference(CandidateGenerator.Generated candidate, DecisionInput input) {
        double sum = 0;
        long now = input.state().world().simTick().value();
        for (var view : input.requests()) {
            if (!(view.request().payload() instanceof DirectorPreference preference)
                || view.state().status() == RequestStatus.CANCELLED || view.state().status() == RequestStatus.EXPIRED) continue;
            long created = view.request().createdTick().value(), expiry = preference.expiresTick().value();
            if (now < created || now >= expiry || expiry <= created) continue;
            if (!candidate.scopes().scopes().contains(view.request().scope())) continue;
            if (!(preference.selector() instanceof AnyOfEvents selector)) return Optional.empty();
            if (!selector.eventIds().contains(candidate.definition().id())) continue;
            // WORK's approved legacy-wire policy is LINEAR_TO_TTL; no new wire field is inferred.
            double decay = view.state().schedulingPolicy().preferenceMultiplier(new Tick(created), new Tick(expiry), new Tick(now)).value();
            sum = Math.min(1, sum + Math.min(1, preference.boundedWeightBias()) * decay);
        }
        return Optional.of(new UnitValue(sum));
    }
    private static double headroom(BudgetVector cost, BudgetView budget) {
        double[] costs = components(cost), capacity = components(budget.capacity()), reserved = components(budget.reserved());
        double minimum = 1;
        for (int i = 0; i < costs.length; i++) if (costs[i] > 0)
            minimum = Math.min(minimum, capacity[i] <= 0 ? 0 : Math.clamp((capacity[i] - reserved[i] - costs[i]) / capacity[i], 0, 1));
        return minimum;
    }
    private static double[] components(BudgetVector b) {
        return new double[]{b.tickMillis(), b.globalIntensity(), b.regionalIntensity(), b.liveEntities(), b.persistentEntities(),
            b.mutationsInFlight(), b.queuedIoBytes(), b.clientUnits(), b.payloadBytes(), b.navigationNodes()};
    }
}
