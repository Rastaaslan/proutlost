package fr.proutlost.director.engine;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Uses captured, decaying memory evidence; unknown/future evidence remains absent. */
public final class EvidenceSignals {
    private EvidenceSignals() {}
    public static Optional<UnitValue> component(DecisionInput input, MemoryComponent component, Optional<RegionId> region) {
        double maximum = -1;
        long now = input.state().world().simTick().value();
        for (var memory : input.memory().regions()) {
            if (region.isPresent() && !region.orElseThrow().equals(memory.regionId())) continue;
            if (memory.header().updatedTick().value() > now) continue;
            var value = memory.components().get(component);
            if (value == null || value.observedTick().value() > now || value.evidenceCount() == 0 || value.confidence().value() == 0
                || !input.config().values().containsKey(value.halfLifeProfile())) continue;
            double halfLife = new EngineParameters(input.config()).positive(value.halfLifeProfile());
            double decayed = value.value() * StrictMath.pow(2, -(now - value.observedTick().value()) / halfLife);
            maximum = Math.max(maximum, Math.clamp(decayed, 0, 1));
        }
        return maximum < 0 ? Optional.empty() : Optional.of(new UnitValue(maximum));
    }
}
