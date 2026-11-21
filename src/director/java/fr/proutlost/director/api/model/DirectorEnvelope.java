package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DirectorEnvelope(int schemaVersion, MessageId messageId, WorldId worldId, ResourceId producerId, String producerKey, ScopeRef scope, CorrelationId correlationId, Optional<MessageId> causationId, Sequence producerSequence, Optional<Sequence> worldSequence, Tick createdTick, PayloadType payloadType, int payloadVersion, EnvelopePayload payload) {
 public DirectorEnvelope { nonnegative(schemaVersion); Objects.requireNonNull(messageId); Objects.requireNonNull(worldId); Objects.requireNonNull(producerId); text(producerKey); Objects.requireNonNull(scope); Objects.requireNonNull(correlationId); Objects.requireNonNull(causationId); Objects.requireNonNull(producerSequence); Objects.requireNonNull(worldSequence); Objects.requireNonNull(createdTick); Objects.requireNonNull(payloadType); nonnegative(payloadVersion); Objects.requireNonNull(payload); require(schemaVersion==1&&payloadVersion==1&&!producerKey.isBlank(),"envelope version/key"); require(MessageContracts.matches(payloadType,payload),"payload type"); }
}
