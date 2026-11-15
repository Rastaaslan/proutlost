package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record CancelRequest(RequestId requestId, Revision expectedRevision, String producerKey) {
 public CancelRequest { Objects.requireNonNull(requestId); Objects.requireNonNull(expectedRevision); text(producerKey);  }
}
