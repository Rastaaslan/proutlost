package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record CommitResult(
    CommitStatus status,
    long store_sequence,
    Set<ReasonCode> reasons) {
    public CommitResult {
        Objects.requireNonNull(status, "status");
        Checks.nonnegative(store_sequence, "store_sequence");
        reasons = Checks.copy(reasons);
    }
}
