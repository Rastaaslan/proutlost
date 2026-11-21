package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AdmissionResult(AdmissionStatus status, Optional<ReservationToken> token, List<fr.proutlost.director.api.error.ReasonCode> reasons, Optional<RetryCondition> retryCondition) {
 public AdmissionResult { Objects.requireNonNull(status); Objects.requireNonNull(token); reasons=list(reasons); Objects.requireNonNull(retryCondition); require((status==AdmissionStatus.ACCEPTED)==token.isPresent(),"reservation required only on acceptance"); require(status==AdmissionStatus.ACCEPTED ? reasons.isEmpty() : !reasons.isEmpty(),"admission reasons"); }
}
