package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record ClockSnapshot(
    Instant utc,
    long monotonic_ms,
    long logical_time_ms) {
    public ClockSnapshot {
        Objects.requireNonNull(utc, "utc");
        Checks.nonnegative(monotonic_ms, "monotonic_ms");
        Checks.nonnegative(logical_time_ms, "logical_time_ms");
    }
}
