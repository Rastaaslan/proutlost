package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AiAcceptedProposal(AiProposal proposal, Fingerprint proposalHash, Fingerprint catalogHash, Fingerprint contextHash, DecisionId decisionId, ResourceId adapterId, String modelVersion, long acceptedMonotonicNanos) {
 public AiAcceptedProposal { Objects.requireNonNull(proposal); Objects.requireNonNull(proposalHash); Objects.requireNonNull(catalogHash); Objects.requireNonNull(contextHash); Objects.requireNonNull(decisionId); Objects.requireNonNull(adapterId); text(modelVersion);   }
}
