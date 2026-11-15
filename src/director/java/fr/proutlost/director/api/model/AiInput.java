package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AiInput(int protocolVersion, RequestId requestId, Pseudonym worldPseudonym, DecisionId decisionId, Fingerprint catalogHash, Fingerprint contextHash, Fingerprint candidateSetHash, long expiresMonotonicDeadline, List<AiCandidate> candidates, List<AiAllowedTransition> allowedChainTransitions, int planningHorizon, AiOutputLimits outputLimits) {
 public AiInput { nonnegative(protocolVersion); Objects.requireNonNull(requestId); Objects.requireNonNull(worldPseudonym); Objects.requireNonNull(decisionId); Objects.requireNonNull(catalogHash); Objects.requireNonNull(contextHash); Objects.requireNonNull(candidateSetHash);  candidates=list(candidates); allowedChainTransitions=list(allowedChainTransitions); nonnegative(planningHorizon); Objects.requireNonNull(outputLimits); require(protocolVersion==1&&planningHorizon>0,"AI protocol/horizon"); require(candidates.stream().map(AiCandidate::candidateId).distinct().count()==candidates.size(),"duplicate candidate"); }
}
