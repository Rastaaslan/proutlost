package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record CapabilityRequirement(
    Id id,
    List<Predicate> predicates) {
    public CapabilityRequirement {
        Objects.requireNonNull(id, "id");
        id = CapabilityTypes.canonical(id);
        CapabilityTypes.requireKnown(id);
        predicates = Checks.copy(predicates);
    }
}
