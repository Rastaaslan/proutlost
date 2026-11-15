package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record AnchorRef(
    LocationRef location,
    Id anchor_id) {
    public AnchorRef {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(anchor_id, "anchor_id");
    }
}
