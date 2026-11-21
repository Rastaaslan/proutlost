package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record RowChange(
    RowKey key,
    @Nullable Long expected_revision,
    StoredRow replacement) {
    public RowChange {
        Objects.requireNonNull(key, "key");
        if (expected_revision != null) Checks.nonnegative(expected_revision, "expected_revision");
        Objects.requireNonNull(replacement, "replacement");
        Checks.require(key.equals(replacement.key()), "Row identity mismatch"); Checks.require(replacement.revision() == (expected_revision == null ? 0 : Math.addExact(expected_revision, 1)), "CAS revision must advance exactly once");
    }
}
