package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.util.*;

public final class NativeAdminRefusalEvidenceTests {
    private static final DefinitionId REQUESTED = new DefinitionId("p008_qa:requested");
    private static final DecisionId DECISION = new DecisionId(UUID.fromString("00000000-0000-0000-0000-000000000038"));
    private static Candidate rejected(String id, DefinitionId definition, ReasonCode... reasons) {
        return new Candidate(new CandidateId(UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                new DefinitionRef(definition, new Revision(1), new Fingerprint("0".repeat(64))),
                false, List.of(reasons), List.of(), 0, new RoleAssignments(Map.of()),
                new ScopeSet(new WorldId(new UUID(0, 1)), Set.of()));
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        var budget = rejected("budget", REQUESTED, ReasonCode.BUDGET_EXHAUSTED);
        var exact = NativeAdminRefusalEvidence.cancellation("ORIGINAL_REQUEST_EXPIRED", REQUESTED,
                Optional.of(DECISION), List.of(budget));
        require(exact.reason() == ReasonCode.BUDGET_EXHAUSTED
                && exact.detail().contains(DECISION.value().toString())
                && exact.detail().contains("candidate_reasons=[BUDGET_EXHAUSTED]"), "exact durable reason and identity lost");
        var foreign = NativeAdminRefusalEvidence.cancellation("ORIGINAL_REQUEST_EXPIRED", REQUESTED,
                Optional.of(DECISION), List.of(rejected("foreign", new DefinitionId("p008_qa:other"), ReasonCode.BUDGET_EXHAUSTED)));
        require(foreign.reason() == ReasonCode.STALE_CONTEXT && foreign.detail().contains("candidate_reasons=[]"), "foreign definition misattributed");
        var mixed = NativeAdminRefusalEvidence.cancellation("ORIGINAL_REQUEST_EXPIRED", REQUESTED,
                Optional.of(DECISION), List.of(budget, rejected("cooldown", REQUESTED, ReasonCode.SAFETY_COOLDOWN)));
        require(mixed.reason() == ReasonCode.STALE_CONTEXT && mixed.detail().contains("BUDGET_EXHAUSTED")
                && mixed.detail().contains("SAFETY_COOLDOWN"), "multiple causes collapsed");
        var unconfirmed = NativeAdminRefusalEvidence.cancellation("ORIGINAL_REQUEST_EXPIRED", REQUESTED,
                Optional.empty(), List.of(budget));
        require(unconfirmed.reason() == ReasonCode.STALE_CONTEXT && !unconfirmed.detail().contains("durable_decision"), "unconfirmed audit promoted");
        var revoked = NativeAdminRefusalEvidence.cancellation("ADMIN_AUTHORITY_REVOKED", REQUESTED,
                Optional.of(DECISION), List.of(budget));
        require(revoked.reason() == ReasonCode.AUTH_DENIED && !revoked.detail().contains("candidate_reasons"), "old budget masked actual authority revocation");
        var possible = new Candidate(new CandidateId(new UUID(0, 2)), budget.definition(), true, List.of(), List.of(), 0,
                budget.roles(), budget.scopes());
        var notAllRejected = NativeAdminRefusalEvidence.cancellation("ORIGINAL_REQUEST_EXPIRED", REQUESTED,
                Optional.of(DECISION), List.of(budget, possible));
        require(notAllRejected.reason() == ReasonCode.STALE_CONTEXT, "eligible requested alternative silently discarded");
        require(NativeAdminRefusalEvidence.admission("QUEUE_CAPACITY") == ReasonCode.RATE_LIMITED
                && NativeAdminRefusalEvidence.admission("DISABLED") == ReasonCode.PLAYER_OVERRIDE
                && NativeAdminRefusalEvidence.admission("UNKNOWN_DEFINITION") == ReasonCode.CATALOG_INVALID
                && NativeAdminRefusalEvidence.admission("FROZEN") == ReasonCode.DELIVERY_UNCERTAIN
                && NativeAdminRefusalEvidence.admission("UNMAPPED") == ReasonCode.INTERNAL_FAULT, "admission predicates collapsed to NO_TARGET");
        System.out.println("NATIVE_ADMIN_REFUSAL_EVIDENCE_TESTS_PASS cases=7");
    }
}
