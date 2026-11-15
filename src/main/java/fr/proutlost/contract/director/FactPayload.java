package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record FactPayload(
    Revision payload_version,
    Id observation_id,
    List<EvidenceRef> evidence,
    @Nullable Id assignment_id,
    @Nullable Revision location_revision,
    TypedValue data) {
    public FactPayload {
        Objects.requireNonNull(payload_version, "payload_version");
        Objects.requireNonNull(observation_id, "observation_id");
        evidence = Checks.copy(evidence);
        Objects.requireNonNull(data, "data");
    }
}
