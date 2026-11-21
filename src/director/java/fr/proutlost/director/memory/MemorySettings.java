package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.MetadataMemoryReader;
import java.util.*;

/** Validated, immutable parameters injected by bootstrap, with no dependency on config implementation. */
public final class MemorySettings implements MetadataMemoryReader.Configuration {
    private final ConfigView config;
    private final ProviderMode mode;
    public MemorySettings(ConfigView config, boolean production) {
        this.config = Objects.requireNonNull(config);
        this.mode = production?ProviderMode.PRODUCTION:ProviderMode.TEST_ONLY;
        if (production && config.calibration() != Calibration.APPROVED)
            throw new IllegalArgumentException("production requires approved calibration");
        if(number("pattern.evidence_scale")<=0||number("fairness.debt_cap")>1||number("region.major_impulse_cap")>1)
            throw new IllegalArgumentException("invalid memory calibration bounds");
    }
    @Override public ProviderMode mode(){return mode;}
    @Override public ConfigView configuration(){return config;}
    public double number(String key) {
        var value = Objects.requireNonNull(config.values().get(key), "missing calibration " + key).value();
        double result = value.doubleValue();
        if (!Double.isFinite(result) || result < 0) throw new IllegalArgumentException(key);
        return result;
    }
    public long ticks(String key) {
        long value = Objects.requireNonNull(config.values().get(key), "missing calibration " + key).value().longValueExact();
        if (value <= 0) throw new IllegalArgumentException(key);
        return value;
    }
    public int count(String key) { return Math.toIntExact(ticks(key)); }
    public String halfLife(MemoryComponent component) {
        return switch (component) {
            case VISIT -> "memory.visit_half_life_ticks";
            case CAMP -> "memory.camp_half_life_ticks";
            case ROUTE -> "memory.route_half_life_ticks";
            case ACTIVITY -> "memory.activity_half_life_ticks";
            case EVENT -> "memory.event_half_life_ticks";
            case SOCIAL -> "memory.social_half_life_ticks";
            case DISASTER -> "memory.risk_half_life_ticks";
            default -> throw new IllegalArgumentException("durable component has no half life");
        };
    }
    public Map<MemoryComponent,DecayValue> decay(Map<MemoryComponent,DecayValue> before, Tick now) {
        var next = new EnumMap<MemoryComponent,DecayValue>(MemoryComponent.class);
        before.forEach((component,value) -> {
            if (component == MemoryComponent.NARRATIVE_FACT || component == MemoryComponent.WORLD_CHANGE) next.put(component,value);
            else {
                String profile = halfLife(component);
                double v = MemoryMath.decay(value.value(),value.observedTick(),now,ticks(profile));
                if (v >= number("memory.anodyne_forget_epsilon"))
                    next.put(component,new DecayValue(v,now,profile,value.evidenceCount(),value.confidence()));
            }
        });
        return next;
    }
    public DecayValue add(MemoryComponent component, DecayValue old, Tick now, double amount, UnitValue confidence) {
        MemoryMath.finiteNonnegative(amount);
        String profile = halfLife(component);
        double previous = old == null ? 0 : MemoryMath.decay(old.value(),old.observedTick(),now,ticks(profile));
        double sum = previous + amount;
        if (!Double.isFinite(sum)) throw new IllegalArgumentException("counter overflow");
        return new DecayValue(sum, now, profile, old == null ? 1 : Math.addExact(old.evidenceCount(),1), confidence);
    }
}
