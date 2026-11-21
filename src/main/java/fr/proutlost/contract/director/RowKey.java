package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record RowKey(
    Id world_id,
    Id kind,
    Id id) {
    public RowKey {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }
}
