package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record InboxRecord(ConsumerId consumerId, MessageId messageId, Fingerprint payloadHash, Sequence contiguousCursor, Revision revision) {
 public InboxRecord { Objects.requireNonNull(consumerId); Objects.requireNonNull(messageId); Objects.requireNonNull(payloadHash); Objects.requireNonNull(contiguousCursor); Objects.requireNonNull(revision);  }
}
