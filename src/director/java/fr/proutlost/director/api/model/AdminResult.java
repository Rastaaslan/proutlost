package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AdminResult(AdminStatus status, RequestId requestId, Revision revision, Optional<AdminRow> data, Optional<SystemError> error) {
 public AdminResult { Objects.requireNonNull(status); Objects.requireNonNull(requestId); Objects.requireNonNull(revision); Objects.requireNonNull(data); Objects.requireNonNull(error);  }
}
