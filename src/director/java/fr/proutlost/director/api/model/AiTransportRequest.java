package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AiTransportRequest(AiInput input, ResourceId adapterId) {
 public AiTransportRequest { Objects.requireNonNull(input); Objects.requireNonNull(adapterId);  }
}
