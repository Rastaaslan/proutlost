package fr.proutlost.director.engine;

import fr.proutlost.director.api.model.ConfigView;
import java.util.Objects;

/** Reads the pinned configuration without embedding gameplay defaults. */
public final class EngineParameters {
    private final ConfigView config;

    public EngineParameters(ConfigView config) {
        this.config = Objects.requireNonNull(config);
    }

    public double number(String key) {
        var value = config.values().get(key);
        if (value == null) throw new IllegalArgumentException("Missing engine parameter: " + key);
        double result = value.value().doubleValue();
        if (!Double.isFinite(result)) throw new IllegalArgumentException("Non-finite parameter: " + key);
        return result;
    }

    public double positive(String key) {
        double value = number(key);
        if (value <= 0) throw new IllegalArgumentException("Expected positive parameter: " + key);
        return value;
    }

    public double nonnegative(String key) {
        double value = number(key);
        if (value < 0) throw new IllegalArgumentException("Expected nonnegative parameter: " + key);
        return value;
    }

    public double unit(String key) {
        double value = nonnegative(key);
        if (value > 1) throw new IllegalArgumentException("Expected unit parameter: " + key);
        return value;
    }

    public long ticks(String key) {
        var value = config.values().get(key);
        if (value == null) throw new IllegalArgumentException("Missing engine parameter: " + key);
        long result = value.value().longValueExact();
        if (result < 0) throw new IllegalArgumentException("Negative parameter: " + key);
        return result;
    }
}
