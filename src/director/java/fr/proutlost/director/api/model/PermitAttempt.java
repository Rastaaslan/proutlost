package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PermitAttempt(StoreSession session, Sequence attemptSequence, OperationToken operationToken, ReservationId reservationId, Epoch reservationGeneration) {
 public PermitAttempt { Objects.requireNonNull(session); Objects.requireNonNull(attemptSequence); Objects.requireNonNull(operationToken); Objects.requireNonNull(reservationId); Objects.requireNonNull(reservationGeneration); require(attemptSequence.value()>0,"attempt sequence"); }
}
