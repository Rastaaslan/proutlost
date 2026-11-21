package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AiProposalResult(AiStatus status, Optional<AiAcceptedProposal> accepted, Optional<fr.proutlost.director.api.error.ReasonCode> fallbackReason) {
 public AiProposalResult { Objects.requireNonNull(status); Objects.requireNonNull(accepted); Objects.requireNonNull(fallbackReason); require((status==AiStatus.VALID_PROPOSAL)==accepted.isPresent(),"AI result"); require((status==AiStatus.FALLBACK)==fallbackReason.isPresent(),"AI fallback reason"); }
}
