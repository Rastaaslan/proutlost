package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record LocationRef(
    LocationStore store,
    Id location_id,
    Revision revision) {
    public LocationRef {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(location_id, "location_id");
        Objects.requireNonNull(revision, "revision");
    }
}
