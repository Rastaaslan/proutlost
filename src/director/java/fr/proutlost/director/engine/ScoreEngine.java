package fr.proutlost.director.engine;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** TECH_SPEC §7: complete log-score arithmetic, independent of feature acquisition. */
public final class ScoreEngine {
    public record Result(double logScore, double score, List<ScoreContribution> contributions) {
        public Result { contributions = List.copyOf(contributions); }
    }

    private final EngineParameters parameters;
    public ScoreEngine(ConfigView config) { parameters = new EngineParameters(config); }

    public Result score(double baseWeight, Map<Feature, UnitValue> features,
                        double repetitionPenalty, double habitationPenalty, double aiLogDelta) {
        return score(baseWeight, features, repetitionPenalty, habitationPenalty, aiLogDelta, 1);
    }
    public Result score(double baseWeight, Map<Feature, UnitValue> features,
                        double repetitionPenalty, double habitationPenalty, double aiLogDelta, double humourMultiplier) {
        if (!Double.isFinite(humourMultiplier) || humourMultiplier <= 0 || humourMultiplier > 1)
            throw new IllegalArgumentException("Humour multiplier must preserve positive weight");
        if (!Double.isFinite(baseWeight) || baseWeight <= 0)
            throw new IllegalArgumentException("Positive finite base weight required");
        requirePenalty(repetitionPenalty);
        requirePenalty(habitationPenalty);
        if (!Double.isFinite(aiLogDelta) || Math.abs(aiLogDelta) > parameters.nonnegative("ai.max_abs_log_delta"))
            throw new IllegalArgumentException("AI log delta outside configured bound");
        double neutral = parameters.unit("score.neutral_feature");
        double z = StrictMath.log(baseWeight);
        List<ScoreContribution> parts = new ArrayList<>();
        parts.add(new ScoreContribution("baseWeight", baseWeight, 1, z));
        for (Feature feature : Feature.values()) {
            double value = features.containsKey(feature) ? features.get(feature).value() : neutral;
            double weight = parameters.number("score." + feature.name());
            double contribution = weight * (2 * value - 1);
            z += contribution;
            parts.add(new ScoreContribution(feature.name(), value, weight, contribution));
        }
        parts.add(new ScoreContribution("repetitionPenalty", repetitionPenalty, -1, -repetitionPenalty));
        parts.add(new ScoreContribution("habitationPenalty", habitationPenalty, -1, -habitationPenalty));
        parts.add(new ScoreContribution("aiLogDelta", aiLogDelta, 1, aiLogDelta));
        double humourLog = StrictMath.log(humourMultiplier);
        parts.add(new ScoreContribution("humourTensionMultiplier", humourMultiplier, 1, humourLog));
        z = z - repetitionPenalty - habitationPenalty + aiLogDelta + humourLog;
        if (!Double.isFinite(z)) throw new IllegalArgumentException("Non-finite log score");
        double lower = parameters.number("score.log_min"), upper = parameters.number("score.log_max");
        if (lower > upper) throw new IllegalArgumentException("Reversed log-score interval");
        double bounded = Math.clamp(z, lower, upper);
        double score = StrictMath.exp(bounded);
        if (!Double.isFinite(score) || score <= 0) throw new IllegalArgumentException("Unrepresentable score");
        parts.add(new ScoreContribution("logClamp", bounded, 1, bounded - z));
        return new Result(bounded, score, parts);
    }

    private static void requirePenalty(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid penalty");
    }
}
