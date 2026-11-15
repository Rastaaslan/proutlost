package fr.proutlost.director.engine;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure semi-Markov controller. The caller persists the returned state before adopting it. */
public final class PacingController {
    public enum Cause { NONE, INITIAL_ENTRY, DEADLINE, RECENT_DEATH, EXTERNAL_ACTIVITY, HEALTH }
    public record Signals(Optional<UnitValue> worldIntensity, Optional<UnitValue> maximumRegionRisk,
                          Optional<UnitValue> significantFacts, double narrativeBias,
                          UnitValue externalActivity, boolean recentDeath, HealthMode health,
                          boolean budgetsAvailable) {
        public Signals {
            Objects.requireNonNull(worldIntensity); Objects.requireNonNull(maximumRegionRisk);
            Objects.requireNonNull(significantFacts); Objects.requireNonNull(externalActivity);
            Objects.requireNonNull(health);
            if (!Double.isFinite(narrativeBias)) throw new IllegalArgumentException("Non-finite narrative bias");
        }
    }
    public record Result(PacingState pacing, GlobalTension tension, EngineControlState control,
                         Cause cause, double intensityFactor) {}

    private final EngineParameters parameters;
    public PacingController(ConfigView config) { parameters = new EngineParameters(config); }

    public Result advance(PacingState pacing, GlobalTension tension, EngineControlState before,
                          Tick now, Signals signals, DecisionRandom random) {
        return advance(pacing, tension, before, now, signals, random, false);
    }

    /** Explicit first entry; an expired (including zero-duration PEAK) deadline is never an initializer. */
    public Result initialize(PacingState pacing, GlobalTension tension, EngineControlState before,
                             Tick now, Signals signals, DecisionRandom random) {
        return advance(pacing, tension, before, now, signals, random, true);
    }

    private Result advance(PacingState pacing, GlobalTension tension, EngineControlState before,
                           Tick now, Signals signals, DecisionRandom random, boolean initial) {
        if (now.compareTo(before.tensionLastUpdateTick()) < 0 || now.compareTo(before.pacingEnteredTick()) < 0)
            throw new IllegalArgumentException("Simulation clock rollback");
        double neutral = parameters.unit("score.neutral_feature");
        double target = (value(signals.worldIntensity(), neutral) + value(signals.maximumRegionRisk(), neutral)
            + value(signals.significantFacts(), neutral)) / 3;
        double biasCap = parameters.unit("pacing.story_tension_bias_cap");
        target = Math.clamp(target + Math.clamp(signals.narrativeBias(), -biasCap, biasCap), 0, 1);
        long dt = now.value() - before.tensionLastUpdateTick().value();
        double alpha = -StrictMath.expm1(-StrictMath.log(2) * dt / parameters.positive("pacing.tension_half_life_ticks"));
        double ema = Math.clamp(before.tensionEma().value() + alpha * (target - before.tensionEma().value()), 0, 1);
        double unusual = parameters.unit("pacing.tension_unusual_enter");
        double tense = parameters.unit("pacing.tension_tense_enter");
        double exceptional = parameters.unit("pacing.tension_exceptional_enter");
        if (!(unusual < tense && tense < exceptional)) throw new IllegalArgumentException("Unordered tension thresholds");
        GlobalTension measured = ema >= exceptional ? GlobalTension.EXCEPTIONAL
            : ema >= tense ? GlobalTension.TENSE : ema >= unusual ? GlobalTension.UNUSUAL : GlobalTension.CALM;
        Optional<Tick> below = before.belowThresholdSince();
        if (measured.ordinal() > tension.ordinal()) {
            tension = measured;
            below = Optional.empty();
        } else {
            double entry = switch (tension) {
                case CALM -> 0;
                case UNUSUAL -> unusual;
                case TENSE -> tense;
                case EXCEPTIONAL -> exceptional;
            };
            if (tension != GlobalTension.CALM && ema < entry - parameters.unit("pacing.tension_hysteresis")) {
                if (below.isEmpty()) below = Optional.of(now);
                if (now.value() - below.orElseThrow().value() >= parameters.ticks("health.recover_window_ticks")) {
                    tension = measured;
                    below = Optional.empty();
                }
            } else below = Optional.empty();
        }

        Cause forced = signals.recentDeath() ? Cause.RECENT_DEATH
            : signals.health() != HealthMode.NORMAL ? Cause.HEALTH
            : signals.externalActivity().value() >= parameters.unit("pacing.normalized_busy_threshold")
                ? Cause.EXTERNAL_ACTIVITY : Cause.NONE;
        Tick entered = before.pacingEnteredTick(), deadline = before.pacingDeadlineTick();
        Cause cause = Cause.NONE;
        if (forced != Cause.NONE) {
            if (pacing != PacingState.RECOVERY || initial) {
                pacing = PacingState.RECOVERY;
                cause = forced;
            }
            // A continuing recovery cause never re-rolls its deadline or permits a new peak.
        } else if (initial) cause = Cause.INITIAL_ENTRY;
        else if (now.compareTo(deadline) >= 0) {
            pacing = switch (pacing) {
                case REST -> PacingState.SUBTLE;
                case SUBTLE -> PacingState.ACTIVE;
                case ACTIVE -> signals.budgetsAvailable()
                    && random.next() < parameters.unit("pacing.active_to_peak_probability")
                    ? PacingState.PEAK : PacingState.RECOVERY;
                case PEAK -> PacingState.RECOVERY;
                case RECOVERY -> PacingState.REST;
            };
            cause = Cause.DEADLINE;
        }
        if (cause != Cause.NONE) {
            entered = now;
            deadline = now.plus(duration(pacing, random));
        }
        EngineControlState next = new EngineControlState(1, before.revision().plus(1), entered, deadline,
            new UnitValue(ema), now, below, before.requiredServiceCredit());
        return new Result(pacing, tension, next, cause,
            parameters.unit("pacing." + pacing.name().toLowerCase(Locale.ROOT) + "_intensity_factor"));
    }

    private long duration(PacingState state, DecisionRandom random) {
        String prefix = "pacing." + state.name().toLowerCase(Locale.ROOT);
        long low = state == PacingState.PEAK ? 0 : parameters.ticks(prefix + "_min_ticks");
        long high = parameters.ticks(prefix + "_max_ticks");
        if (high < low || high == 0 || high > (1L << 53) - 1)
            throw new IllegalArgumentException("Invalid pacing duration interval");
        // Uniform inclusive integer interval, consuming exactly one recorded unit draw.
        return low + (long) (random.next() * (high - low + 1));
    }

    private static double value(Optional<UnitValue> value, double neutral) {
        return value.map(UnitValue::value).orElse(neutral);
    }
}
