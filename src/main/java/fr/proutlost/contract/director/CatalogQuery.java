package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record CatalogQuery(
    Set<Id> capabilities,
    Set<SemanticRegionId> regions,
    @Nullable Id after_id,
    int limit) {
    public CatalogQuery {
        capabilities = Checks.copy(capabilities);
        regions = Checks.copy(regions);
        Checks.require(limit > 0, "Positive limit required");
        Checks.require(limit <= Limits.MAX_QUERY, "Query cap");
    }
}
