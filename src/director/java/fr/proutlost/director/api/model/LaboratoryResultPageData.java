package fr.proutlost.director.api.model;

import java.util.*;
import fr.proutlost.director.api.error.ReasonCode;
import static fr.proutlost.director.api.model.Checks.*;

/** Score-only laboratory projection. Hashes identify the complete immutable
 * calculation retained by the server; candidates are never silently truncated. */
public record LaboratoryResultPageData(ResourceId fixtureId, DecisionId proposalId,
        Fingerprint proposalHash, Fingerprint auditHash, int candidateOffset, int candidateCount,
        List<Candidate> candidates, Optional<CandidateId> chosen, List<ValidationResult> validations,
        List<ReasonCode> admissionReasons) implements AdminData {
    public LaboratoryResultPageData {
        Objects.requireNonNull(fixtureId);Objects.requireNonNull(proposalId);
        Objects.requireNonNull(proposalHash);Objects.requireNonNull(auditHash);
        candidates=list(candidates);Objects.requireNonNull(chosen);
        validations=list(validations);admissionReasons=list(admissionReasons);
        require(candidateOffset>=0&&candidateCount>=candidateOffset
            &&candidates.size()<=candidateCount-candidateOffset,"laboratory candidate page");
    }
}
