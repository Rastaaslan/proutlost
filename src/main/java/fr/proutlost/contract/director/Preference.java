package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record Preference(
    Id criterion,
    int weight,
    TypedValue parameters) {
    public Preference {
        Objects.requireNonNull(criterion, "criterion");
        Checks.nonnegative(weight, "weight");
        Objects.requireNonNull(parameters, "parameters");
    }
}
