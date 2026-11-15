package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record HealthVerdict(
    boolean admission_allowed,
    boolean continuation_allowed,
    boolean cleanup_allowed,
    Set<ReasonCode> reasons) {
    public HealthVerdict {
        reasons = Checks.copy(reasons);
        Checks.require(cleanup_allowed, "Cleanup capacity cannot be disabled");
    }
}
