package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AuditDecision(DecisionId decisionId, SnapshotBatch context, Revision configVersion, Fingerprint configHash, Revision catalogVersion, Fingerprint catalogHash, List<Candidate> candidates, List<CandidateId> pool, long seed, List<Double> draws, Optional<AiProposalResult> advisor, Optional<CandidateId> chosen, RoleAssignments roles, ScopeSet scopes, List<ValidationResult> validations, List<ReservationToken> reservations, List<SliceResult> effects, Optional<Outcome> outcome, List<AggregateDelta> memoryDeltas, Map<MutationId,Fingerprint> mutations, List<UniqueUseRecord> uniqueUses, List<DirectorFact> facts, List<MessageId> outbox, Map<String,Long> timings, List<SystemError> errors) {
 public AuditDecision { Objects.requireNonNull(decisionId); Objects.requireNonNull(context); Objects.requireNonNull(configVersion); Objects.requireNonNull(configHash); Objects.requireNonNull(catalogVersion); Objects.requireNonNull(catalogHash); candidates=list(candidates); pool=list(pool);  draws=list(draws); Objects.requireNonNull(advisor); Objects.requireNonNull(chosen); Objects.requireNonNull(roles); Objects.requireNonNull(scopes); validations=list(validations); reservations=list(reservations); effects=list(effects); Objects.requireNonNull(outcome); memoryDeltas=list(memoryDeltas); mutations=map(mutations); uniqueUses=list(uniqueUses); facts=list(facts); outbox=list(outbox); timings=map(timings); errors=list(errors);  }
}
