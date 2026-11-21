package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record StateValue(
    TypedValue value,
    long revision,
    List<EvidenceRef> evidence_refs,
    StateOwner owner) {
    public StateValue {
        Objects.requireNonNull(value, "value");
        Checks.nonnegative(revision, "revision");
        evidence_refs = Checks.copy(evidence_refs);
        Objects.requireNonNull(owner, "owner");
    }
}
