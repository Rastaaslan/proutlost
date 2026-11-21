package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record HealthThresholds(
    double max_mspt,
    double min_tps,
    long sample_window_ms,
    long restart_grace_ms) {
    public HealthThresholds {
        Checks.require(Double.isFinite(max_mspt), "Nonfinite max_mspt");
        Checks.require(Double.isFinite(min_tps), "Nonfinite min_tps");
        Checks.nonnegative(sample_window_ms, "sample_window_ms");
        Checks.duration(sample_window_ms);
        Checks.nonnegative(restart_grace_ms, "restart_grace_ms");
        Checks.duration(restart_grace_ms);
        Checks.require(max_mspt > 0 && max_mspt <= 100 && min_tps > 0 && min_tps <= 20, "Invalid health limits");
    }
}
