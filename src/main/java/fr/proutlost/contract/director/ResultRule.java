package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record ResultRule(
    Id validator_id,
    Id observation_type,
    Set<Id> required_evidence,
    Id fact_type,
    Id payload_mapping,
    Persistence persistence) {
    public ResultRule {
        Objects.requireNonNull(validator_id, "validator_id");
        Objects.requireNonNull(observation_type, "observation_type");
        required_evidence = Checks.copy(required_evidence);
        Objects.requireNonNull(fact_type, "fact_type");
        Objects.requireNonNull(payload_mapping, "payload_mapping");
        Objects.requireNonNull(persistence, "persistence");
    }
}
