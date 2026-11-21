package fr.proutlost.director.engine;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Decaying exposure arithmetic. Does not mutate debt, history, facts or cooldowns. */
public final class ExposureFeatures {
    public record Result(double repetitionPenalty, UnitValue novelty, UnitValue suitability) {}
    private final EngineParameters parameters;
    public ExposureFeatures(ConfigView config) { parameters = new EngineParameters(config); }

    public Result evaluate(DefinitionId definition, Set<Family> families, RoleAssignments roles,
                           List<PlayerOperationalMemory> memories, Map<PlayerId, UnitValue> aptitudes, Tick now) {
        Map<PlayerId, PlayerOperationalMemory> byPlayer = new HashMap<>();
        for (var memory : memories)
            if (byPlayer.put(memory.playerId(), memory) != null) throw new IllegalArgumentException("Duplicate player memory");
        Map<PlayerId, Set<String>> bindings = new TreeMap<>(Comparator.comparing(p -> p.value().toString()));
        roles.roles().forEach((role, players) -> players.forEach(player ->
            bindings.computeIfAbsent(player, ignored -> new TreeSet<>()).add(role)));
        double neutral = parameters.unit("score.neutral_feature");
        if (bindings.isEmpty()) return new Result(0, new UnitValue(neutral), new UnitValue(neutral));
        double repetition = 0, suitability = 0;
        int knownHistories = 0;
        for (var binding : bindings.entrySet()) {
            var memory = byPlayer.get(binding.getKey());
            if (memory == null) {
                suitability += neutral;
                continue;
            }
            knownHistories++;
            double event = 0, family = 0, role = 0;
            for (Exposure exposure : memory.eventExposureRing()) {
                double amount = decay(exposure, now);
                if (exposure.definitionId().equals(definition)) event += amount;
                if (families.contains(exposure.family())) family += amount;
            }
            for (Exposure exposure : memory.roleExposureRing())
                if (binding.getValue().contains(exposure.role())) role += decay(exposure, now);
            double penalty = event * parameters.nonnegative("score.repetition_event_scale")
                + family * parameters.nonnegative("score.repetition_family_scale")
                + role * parameters.nonnegative("score.repetition_role_scale");
            repetition += penalty;
            double aptitude = aptitudes.getOrDefault(binding.getKey(),
                new UnitValue(neutral)).value();
            suitability += Math.clamp((aptitude + memory.attentionDebt().value()) / (2 * (1 + penalty)), 0, 1);
        }
        // Unknown histories are not evidence of zero repetition. Only known histories enter
        // the penalty mean; a known empty ring does enter it with zero exposure.
        repetition = knownHistories == 0 ? 0 : repetition / knownHistories;
        // Preserve the known-history aggregate, with a neutral contribution for each unknown
        // distinct target. Overlapping roles cannot multiply either kind of contribution.
        int unknownHistories = bindings.size() - knownHistories;
        double novelty = (knownHistories / (1 + repetition) + unknownHistories * neutral) / bindings.size();
        return new Result(repetition, new UnitValue(novelty),
            new UnitValue(suitability / bindings.size()));
    }

    public UnitValue cooldownReadiness(Tick lastEnd, Tick now) {
        return new UnitValue(Math.clamp((now.value() - lastEnd.value())
            / parameters.positive("score.cooldown_readiness_horizon_ticks"), 0, 1));
    }

    private double decay(Exposure exposure, Tick now) {
        if (exposure.tick().compareTo(now) > 0) throw new IllegalArgumentException("Future exposure");
        return exposure.intensity().value() * StrictMath.exp(-StrictMath.log(2)
            * (now.value() - exposure.tick().value()) / parameters.positive("memory.event_half_life_ticks"));
    }
}
