package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Diagnostic projection only. Never evaluates, admits, retries or changes a request. */
public final class NativeAdminRefusalEvidence {
    private NativeAdminRefusalEvidence() {}
    public record Evidence(ReasonCode reason, String detail) {
        public Evidence { Objects.requireNonNull(reason); Objects.requireNonNull(detail); }
    }
    public static ReasonCode admission(String predicate) {
        return switch (predicate) {
            case "QUEUE_CAPACITY" -> ReasonCode.RATE_LIMITED;
            case "FROZEN" -> ReasonCode.DELIVERY_UNCERTAIN;
            case "STOP_FENCE", "DISABLED" -> ReasonCode.PLAYER_OVERRIDE;
            case "UNKNOWN_DEFINITION" -> ReasonCode.CATALOG_INVALID;
            default -> ReasonCode.INTERNAL_FAULT;
        };
    }
    /**
     * The caller supplies candidates only from its exact ACKed NO_PLAN proposal.
     * The original cancellation wins over historical eligibility when authority,
     * session or participant state changed. No current-world re-evaluation occurs.
     */
    public static Evidence cancellation(String cancellation, DefinitionId requested,
            Optional<DecisionId> durableNoPlanDecision, List<Candidate> durableNoPlanCandidates) {
        Objects.requireNonNull(cancellation); Objects.requireNonNull(requested);
        Objects.requireNonNull(durableNoPlanDecision); Objects.requireNonNull(durableNoPlanCandidates);
        var reason = switch (cancellation) {
            case "ORIGINAL_REQUEST_EXPIRED", "ORIGINAL_REGION_CHANGED" -> ReasonCode.STALE_CONTEXT;
            case "ADMIN_AUTHORITY_REVOKED", "INITIATOR_LEFT_OR_OP4_LOST" -> ReasonCode.AUTH_DENIED;
            case "WAL_SESSION_CHANGED" -> ReasonCode.WAL_UNAVAILABLE;
            case "J06_SESSION_CHANGED" -> ReasonCode.CAPABILITY_MISSING;
            case "CATALOGUE_PAUSED" -> ReasonCode.PLAYER_OVERRIDE;
            default -> ReasonCode.DELIVERY_UNCERTAIN;
        };
        String detail = "cancellation=" + cancellation;
        if (cancellation.equals("ORIGINAL_REQUEST_EXPIRED") && durableNoPlanDecision.isPresent()) {
            var candidates = durableNoPlanCandidates.stream()
                    .filter(c -> c.definition().id().equals(requested)).toList();
            var reasons = candidates.stream().flatMap(c -> c.reasons().stream())
                    .distinct().sorted().toList();
            detail += "; durable_decision=" + durableNoPlanDecision.orElseThrow().value()
                    + "; requested_definition=" + requested.value()
                    + "; rejected_candidates=" + candidates.stream().map(c -> c.id().value().toString()).sorted().toList()
                    + "; candidate_reasons=" + reasons;
            // A single unambiguous recorded cause may explain the request. Mixed
            // causes stay explicit in detail and must not be collapsed arbitrarily.
            if (!candidates.isEmpty() && candidates.stream().allMatch(c -> !c.eligible() && !c.reasons().isEmpty())
                    && reasons.size() == 1) reason = reasons.getFirst();
        }
        return new Evidence(reason, detail);
    }
}
