package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AiProposal(int protocolVersion, RequestId requestId, Fingerprint candidateSetHash, List<AiAdjustment> adjustments, List<AiArcProposal> arcProposals, Set<AiRationale> rationaleCodes) {
 public AiProposal { nonnegative(protocolVersion); Objects.requireNonNull(requestId); Objects.requireNonNull(candidateSetHash); adjustments=list(adjustments); arcProposals=list(arcProposals); rationaleCodes=set(rationaleCodes); require(protocolVersion==1,"AI protocol"); require(adjustments.stream().map(AiAdjustment::candidateId).distinct().count()==adjustments.size(),"duplicate adjustments"); require(arcProposals.stream().map(AiArcProposal::chainId).distinct().count()==arcProposals.size(),"duplicate arcs"); }
}
