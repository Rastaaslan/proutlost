package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record FactEvidence(FactId factId, EvidenceStatus status, Map<ResourceKey,Fingerprint> observations) {
 public FactEvidence { Objects.requireNonNull(factId); Objects.requireNonNull(status); observations=map(observations);  }
}
