package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure arithmetic only. The actor must supply eligible online duration and confirmed exposures. */
public final class FairnessMath {
    private FairnessMath() {}

    public static UnitValue debt(UnitValue before, DurationTicks eligibleOnlineDelta,
            double gainPerTick, double cap, double confirmedExposureCost) {
        MemoryMath.finiteNonnegative(gainPerTick);
        MemoryMath.finiteNonnegative(cap);
        MemoryMath.finiteNonnegative(confirmedExposureCost);
        if (cap > 1) throw new IllegalArgumentException("unit debt cap");
        double gain = eligibleOnlineDelta.value() * gainPerTick;
        if (!Double.isFinite(gain)) throw new IllegalArgumentException("debt gain overflow");
        double next = before.value() + gain - confirmedExposureCost;
        if (!Double.isFinite(next)) throw new IllegalArgumentException("debt overflow");
        return new UnitValue(Math.clamp(next, 0, cap));
    }

    /** UUID deduplication occurs before taking a capped mean; a group gets no sum bonus. */
    public static UnitValue audienceScore(Collection<PlayerId> audience,
            Map<PlayerId, UnitValue> scores, UnitValue cap) {
        var players = new TreeSet<>(audience);
        if (players.isEmpty()) throw new IllegalArgumentException("empty audience");
        double mean = 0;
        int count = 0;
        for (PlayerId player : players) {
            UnitValue score = Objects.requireNonNull(scores.get(player), "missing player score");
            mean += (Math.min(cap.value(), score.value()) - mean) / ++count;
        }
        return new UnitValue(mean);
    }

    /** Newest entries retained, canonical order at equal ticks, immutable output. */
    public static List<Exposure> recent(List<Exposure> entries, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("ring capacity");
        Comparator<Exposure> order = Comparator.comparing(Exposure::tick)
                .thenComparing(Exposure::definitionId)
                .thenComparing(Exposure::family).thenComparing(Exposure::role)
                .thenComparingDouble(e -> e.intensity().value());
        var sorted = entries.stream().sorted(order).toList();
        return List.copyOf(sorted.subList(Math.max(0, sorted.size() - limit), sorted.size()));
    }
}
