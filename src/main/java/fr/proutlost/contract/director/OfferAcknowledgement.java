package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record OfferAcknowledgement(
    OpportunityRef opportunity,
    OfferStatus status,
    Set<ReasonCode> reasons) {
    public OfferAcknowledgement {
        Objects.requireNonNull(opportunity, "opportunity");
        Objects.requireNonNull(status, "status");
        reasons = Checks.copy(reasons);
    }
}
