package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DirectorRequest(MessageId messageId, WorldId worldId, ResourceId producerId, String producerKey, ScopeRef scope, CorrelationId correlationId, Optional<MessageId> causationId, Sequence producerSequence, Tick createdTick, RequestPayload payload) {
 public DirectorRequest { Objects.requireNonNull(messageId); Objects.requireNonNull(worldId); Objects.requireNonNull(producerId); text(producerKey); Objects.requireNonNull(scope); Objects.requireNonNull(correlationId); Objects.requireNonNull(causationId); Objects.requireNonNull(producerSequence); Objects.requireNonNull(createdTick); Objects.requireNonNull(payload);  }
}
