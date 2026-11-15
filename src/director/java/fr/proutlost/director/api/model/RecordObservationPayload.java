package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RecordObservationPayload(Optional<EffectId> receivedEffectId, Optional<FactId> receivedFactId, Optional<String> playerText) implements AdminPayload {
 public RecordObservationPayload { Objects.requireNonNull(receivedEffectId); Objects.requireNonNull(receivedFactId); Objects.requireNonNull(playerText); require(receivedEffectId.isPresent()!=receivedFactId.isPresent(),"one received source required"); playerText.ifPresent(t->{text(t);require(t.length()<=1000,"player text bound");}); }
}
