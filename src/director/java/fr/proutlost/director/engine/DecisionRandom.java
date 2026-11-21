package fr.proutlost.director.engine;

import java.util.*;

/** One Java 21 RNG per calculation. Every unit draw is retained for audit/replay. */
public final class DecisionRandom {
    private final SplittableRandom random;
    private final List<Double> draws = new ArrayList<>();
    public DecisionRandom(long seed) { random = new SplittableRandom(seed); }
    public double next() {
        double value = random.nextDouble();
        draws.add(value);
        return value;
    }
    public List<Double> draws() { return List.copyOf(draws); }
}
