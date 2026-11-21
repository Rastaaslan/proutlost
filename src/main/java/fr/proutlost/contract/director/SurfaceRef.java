package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record SurfaceRef(
    LocationRef location,
    Id surface_id) {
    public SurfaceRef {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(surface_id, "surface_id");
    }
}
