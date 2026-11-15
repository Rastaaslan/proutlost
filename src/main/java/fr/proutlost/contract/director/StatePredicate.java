package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record StatePredicate(
    StateAddress address,
    TypedValue expected) {
    public StatePredicate {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(expected, "expected");
    }
}
