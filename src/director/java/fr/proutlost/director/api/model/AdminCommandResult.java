package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AdminCommandResult(RequestId commandId, AdminStatus status, List<fr.proutlost.director.api.error.ReasonCode> reasonCodes, Optional<Revision> newRevision, Optional<ScopeRef> resultRef) {
 public AdminCommandResult { Objects.requireNonNull(commandId); Objects.requireNonNull(status); reasonCodes=list(reasonCodes); Objects.requireNonNull(newRevision); Objects.requireNonNull(resultRef);  }
}
