package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record FactPlayerScope(
    PlayerScopeKind kind,
    Set<UUID> ids) {
    public FactPlayerScope {
        Objects.requireNonNull(kind, "kind");
        ids = Checks.copy(ids);
        Checks.require((kind == PlayerScopeKind.PLAYERS) == !ids.isEmpty(), "Invalid player scope");
    }
}
