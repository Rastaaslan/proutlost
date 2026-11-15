package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record WarningEvidence(Set<PlayerId> audience, Tick deployedTick, DurationTicks leadTime, Fingerprint proofHash, Epoch audienceRevision, boolean readable, boolean persistentPerimeter) {
 public WarningEvidence { audience=set(audience); Objects.requireNonNull(deployedTick); Objects.requireNonNull(leadTime); Objects.requireNonNull(proofHash); Objects.requireNonNull(audienceRevision);  }
}
