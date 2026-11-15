package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PerceptionHeader(int protocolVersion, WorldId worldId, Epoch bootEpoch, Epoch connectionEpoch, DimensionId dimension, EventInstanceId instanceId, EffectId effectId, Sequence sequence, DurationTicks remainingTtlTicks) {
 public PerceptionHeader { nonnegative(protocolVersion); Objects.requireNonNull(worldId); Objects.requireNonNull(bootEpoch); Objects.requireNonNull(connectionEpoch); Objects.requireNonNull(dimension); Objects.requireNonNull(instanceId); Objects.requireNonNull(effectId); Objects.requireNonNull(sequence); Objects.requireNonNull(remainingTtlTicks); require(protocolVersion==1,"protocol version"); }
}
