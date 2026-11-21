package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record ResourceRequest(
    ResourceKey key,
    LeaseMode mode,
    int units) {
    public ResourceRequest {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mode, "mode");
        Checks.require(units > 0, "Positive units required");
        Checks.require(key.kind() != ResourceKind.GLOBAL_MAJOR || (units == 1 && mode == LeaseMode.EXCLUSIVE), "Global major capacity is one exclusive unit");
    }
}
