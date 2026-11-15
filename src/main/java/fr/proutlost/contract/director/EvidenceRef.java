package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record EvidenceRef(
    Id record_id,
    Digest digest,
    Id validator_id) {
    public EvidenceRef {
        Objects.requireNonNull(record_id, "record_id");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(validator_id, "validator_id");
    }
}
