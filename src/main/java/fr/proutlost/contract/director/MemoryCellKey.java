package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record MemoryCellKey(
    Id dimension_id,
    Revision grid_revision,
    int x,
    int z) {
    public MemoryCellKey {
        Objects.requireNonNull(dimension_id, "dimension_id");
        Objects.requireNonNull(grid_revision, "grid_revision");
    }
}
