package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record FactGroupScope(
    GroupScopeKind kind,
    Set<Id> ids) {
    public FactGroupScope {
        Objects.requireNonNull(kind, "kind");
        ids = Checks.copy(ids);
        Checks.require((kind == GroupScopeKind.GROUP) == !ids.isEmpty(), "Invalid group scope");
    }
}
