package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AdminCommand(SessionId actorSession, RequestId requestId, Revision expectedRevision, AdminOperation operation, AdminPayload payload) {
 public AdminCommand { Objects.requireNonNull(actorSession); Objects.requireNonNull(requestId); Objects.requireNonNull(expectedRevision); Objects.requireNonNull(operation); Objects.requireNonNull(payload); require(AdminContracts.matches(operation,payload),"operation/payload mismatch"); }
}
