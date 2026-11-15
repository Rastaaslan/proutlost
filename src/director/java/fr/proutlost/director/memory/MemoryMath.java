package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure, lazy memory calculations. No wall clock, store or world access. */
public final class MemoryMath {
    private MemoryMath() {}

    public static double decay(double value, Tick at, Tick now, long halfLife) {
        finiteNonnegative(value);
        if (halfLife <= 0) throw new IllegalArgumentException("half life must be positive");
        if (now.compareTo(at) < 0) throw new IllegalArgumentException("clock rollback");
        return value * StrictMath.exp(-StrictMath.log(2) * (now.value() - at.value()) / halfLife);
    }

    /** Durable components deliberately cannot enter the decay path. */
    public static DecayValue advance(MemoryComponent component, DecayValue before,
                                     Tick now, long halfLife, double addition, double cap) {
        Objects.requireNonNull(component);
        if (component == MemoryComponent.NARRATIVE_FACT || component == MemoryComponent.WORLD_CHANGE)
            throw new IllegalArgumentException("durable state requires an explicit lifecycle, not decay");
        finiteNonnegative(addition);
        finiteNonnegative(cap);
        double next = Math.min(cap, decay(before.value(), before.observedTick(), now, halfLife) + addition);
        return new DecayValue(next, now, before.halfLifeProfile(),
                addition == 0 ? before.evidenceCount() : Math.addExact(before.evidenceCount(), 1), before.confidence());
    }

    public static RegionPersonality personality(RegionPersonality before,
            Map<RegionTrait, UnitValue> targets, Map<RegionTrait, Double> majorImpulses,
            Tick at, Tick now, long halfLife, double impulseCap) {
        finiteNonnegative(impulseCap);
        if (impulseCap > 1) throw new IllegalArgumentException("impulse cap");
        double alpha = 1 - decay(1, at, now, halfLife);
        var next = new EnumMap<RegionTrait, UnitValue>(RegionTrait.class);
        next.putAll(before.traits());
        for (RegionTrait trait : RegionTrait.values()) {
            double old = before.traits().getOrDefault(trait, new UnitValue(0)).value();
            // Missing evidence retains the previous trait instead of inventing a target.
            double target = targets.getOrDefault(trait, new UnitValue(old)).value();
            double impulse = majorImpulses.getOrDefault(trait, 0.0);
            if (!Double.isFinite(impulse)) throw new IllegalArgumentException("nonfinite impulse");
            impulse = Math.max(-impulseCap, Math.min(impulseCap, impulse));
            next.put(trait, new UnitValue(Math.clamp(old + alpha * (target - old) + impulse, 0, 1)));
        }
        return new RegionPersonality(next);
    }

    static void finiteNonnegative(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("finite nonnegative value required");
    }
}
