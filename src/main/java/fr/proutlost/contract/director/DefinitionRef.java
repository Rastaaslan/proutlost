package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record DefinitionRef(
    Id definition_id,
    Revision revision,
    Digest digest) {
    public DefinitionRef {
        Objects.requireNonNull(definition_id, "definition_id");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(digest, "digest");
    }
}
