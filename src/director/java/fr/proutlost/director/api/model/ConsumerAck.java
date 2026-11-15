package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ConsumerAck(ConsumerId consumerId, Set<MessageId> messageIds, Sequence contiguousAckSequence, Sequence expectedCursor) {
 public ConsumerAck { Objects.requireNonNull(consumerId); messageIds=set(messageIds); Objects.requireNonNull(contiguousAckSequence); Objects.requireNonNull(expectedCursor);  }
}
