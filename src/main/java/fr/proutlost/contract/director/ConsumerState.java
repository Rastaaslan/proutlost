package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record ConsumerState(
    AckStatus status,
    int attempts,
    Instant next_retry_at,
    @Nullable Instant acknowledged_at) {
    public ConsumerState {
        Objects.requireNonNull(status, "status");
        Checks.nonnegative(attempts, "attempts");
        Objects.requireNonNull(next_retry_at, "next_retry_at");
        Checks.require((status == AckStatus.ACKED) == (acknowledged_at != null), "ACK time mismatch");
    }
}
