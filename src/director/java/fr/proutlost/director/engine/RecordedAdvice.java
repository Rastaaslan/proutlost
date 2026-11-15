package fr.proutlost.director.engine;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.security.*;
import java.util.*;

/** Revalidates a recorded advisory without a transport, clock read, future or world port. */
public final class RecordedAdvice {
    private RecordedAdvice() {}

    public static AiProposalResult validate(AiInput originalInput, AiProposalResult recorded,
                                            Set<CandidateId> currentlyEligible) {
        if (recorded.status() == AiStatus.FALLBACK) return recorded;
        var accepted = recorded.accepted().orElseThrow();
        if (!accepted.catalogHash().equals(originalInput.catalogHash())
            || !accepted.contextHash().equals(originalInput.contextHash())
            || !accepted.decisionId().equals(originalInput.decisionId())) return invalid();
        byte[] bytes = new ModelCodec<>(AiProposal.class).encode(accepted.proposal());
        if (!hash(bytes).equals(accepted.proposalHash())) return invalid();
        try {
            AiProposal proposal = AiProposalContract.decode(bytes, originalInput,
                accepted.acceptedMonotonicNanos(), true, originalInput.contextHash());
            if (proposal.adjustments().stream().anyMatch(a -> !currentlyEligible.contains(a.candidateId())))
                return invalid();
            return recorded;
        } catch (IllegalArgumentException invalidProposal) {
            return invalid();
        }
    }

    public static Fingerprint hash(byte[] bytes) {
        try {
            return new Fingerprint(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JDK SHA-256 unavailable", unavailable);
        }
    }

    private static AiProposalResult invalid() {
        return new AiProposalResult(AiStatus.FALLBACK, Optional.empty(), Optional.of(ReasonCode.AI_INVALID));
    }
}
