package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record LocationCapability(
    Id capability_id,
    Revision capability_version,
    TypedValue parameters,
    Set<Id> anchor_ids,
    Set<Id> surface_ids,
    List<EvidenceRef> evidence) {
    public LocationCapability {
        Objects.requireNonNull(capability_id, "capability_id");
        capability_id = CapabilityTypes.canonical(capability_id);
        Objects.requireNonNull(capability_version, "capability_version");
        Objects.requireNonNull(parameters, "parameters");
        CapabilityTypes.requireKnown(capability_id);
        Checks.require(capability_id.equals(parameters.type_id()) && capability_version.equals(parameters.version()), "Capability parameter codec mismatch");
        anchor_ids = Checks.copy(anchor_ids);
        surface_ids = Checks.copy(surface_ids);
        evidence = Checks.copy(evidence);
    }
}
