package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record Bounds(
    Id dimension_id,
    BlockPos min,
    BlockPos max) {
    public Bounds {
        Objects.requireNonNull(dimension_id, "dimension_id");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        Checks.require(min.x() <= max.x() && min.y() <= max.y() && min.z() <= max.z(), "Reversed bounds");
    }
}
