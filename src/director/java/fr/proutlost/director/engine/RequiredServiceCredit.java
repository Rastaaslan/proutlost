package fr.proutlost.director.engine;

import fr.proutlost.director.api.model.*;
import java.math.BigDecimal;

/** Opportunity accounting only; this does not declare a requirement ready or fulfilled. */
public final class RequiredServiceCredit {
    public enum Lane { AMBIENT, REQUIRE, NONE }
    public record Result(Lane lane, UnitValue credit) {}

    private final BigDecimal share;
    public RequiredServiceCredit(ConfigView config) {
        new EngineParameters(config).unit("scheduler.required_service_share");
        share = config.values().get("scheduler.required_service_share").value();
    }

    public Result advance(UnitValue credit, boolean readyRequirement, boolean ambientAvailable) {
        // Preserve decimal calibration at threshold comparisons; the normative cap discards overflow.
        BigDecimal next = BigDecimal.valueOf(credit.value());
        if (readyRequirement) next = next.add(share).min(BigDecimal.ONE);
        Lane lane;
        if (readyRequirement && next.compareTo(BigDecimal.ONE) >= 0) {
            lane = Lane.REQUIRE;
            next = next.subtract(BigDecimal.ONE);
        } else if (ambientAvailable) lane = Lane.AMBIENT;
        else lane = readyRequirement ? Lane.REQUIRE : Lane.NONE;
        return new Result(lane, new UnitValue(next.doubleValue()));
    }
}
