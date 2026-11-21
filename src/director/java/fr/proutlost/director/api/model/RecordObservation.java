package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RecordObservation(PerceptionHeader header, RecordObservationPayload observation) {
 public RecordObservation { Objects.requireNonNull(header); Objects.requireNonNull(observation);  }
}
