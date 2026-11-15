package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record ConflictDeclaration(
    ResourceKey resource,
    int capacity,
    Set<Id> compatibility_tags,
    Set<Id> exclusion_tags) {
    public ConflictDeclaration {
        Objects.requireNonNull(resource, "resource");
        Checks.require(capacity > 0, "Positive capacity required");
        compatibility_tags = Checks.copy(compatibility_tags);
        exclusion_tags = Checks.copy(exclusion_tags);
        Checks.require(resource.kind() != ResourceKind.GLOBAL_MAJOR || capacity == 1, "Global major capacity is fixed");
    }
}
