package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record QuestScope(
    QuestKind kind,
    Id scope_id) {
    public QuestScope {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(scope_id, "scope_id");
    }
}
