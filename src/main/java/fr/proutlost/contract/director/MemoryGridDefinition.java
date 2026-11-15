package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record MemoryGridDefinition(
    Revision revision,
    Id dimension_id,
    int origin_x,
    int origin_z,
    int cell_size_blocks,
    Digest digest) {
    public MemoryGridDefinition {
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(dimension_id, "dimension_id");
        Checks.require(cell_size_blocks > 0, "Positive cell_size_blocks required");
        Objects.requireNonNull(digest, "digest");
    }
}
