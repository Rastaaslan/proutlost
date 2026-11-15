package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record Predicate(
    Id field,
    Comparison op,
    TypedValue value) {
    public Predicate {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(value, "value");
    }
}
