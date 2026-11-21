package fr.proutlost.director.integration.ai;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.AiAdvisorPort;
import java.util.Optional;
import java.util.concurrent.*;

/** Default composition: no transport, threads, secrets or world permissions. */
public final class DisabledAiAdvisor implements AiAdvisorPort {
    public CompletionStage<AiProposalResult> propose(AiInput input) {
        java.util.Objects.requireNonNull(input);
        return CompletableFuture.completedFuture(fallback(ReasonCode.CAPABILITY_MISSING));
    }
    static AiProposalResult fallback(ReasonCode reason) {
        return new AiProposalResult(AiStatus.FALLBACK, Optional.empty(), Optional.of(reason));
    }
}
