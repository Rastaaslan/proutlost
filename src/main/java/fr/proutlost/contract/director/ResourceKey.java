package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record ResourceKey(
    ResourceKind kind,
    @Nullable Id dimension_id,
    Id scope_id,
    @Nullable Long membership_epoch,
    @Nullable Id sub_id) {
    public ResourceKey {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(scope_id, "scope_id");
        if (membership_epoch != null) Checks.nonnegative(membership_epoch, "membership_epoch");
        Checks.require((kind == ResourceKind.AUDIENCE) == (membership_epoch != null), "Audience resource requires epoch only");
        if (java.util.Set.of(ResourceKind.LOCATION, ResourceKind.ANCHOR, ResourceKind.SURFACE, ResourceKind.PHYSICAL_FOOTPRINT).contains(kind))
            Checks.require(dimension_id != null, "Physical resource dimension required");
    }
}
