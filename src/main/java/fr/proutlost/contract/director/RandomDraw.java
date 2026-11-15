package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record RandomDraw(
    Digest seed,
    long draw_index,
    long bound,
    long value) {
    public RandomDraw {
        Objects.requireNonNull(seed, "seed");
        Checks.nonnegative(draw_index, "draw_index");
        Checks.nonnegative(bound, "bound");
        Checks.nonnegative(value, "value");
        Checks.require(bound > 0 && value < bound, "Invalid random draw");
    }
}
