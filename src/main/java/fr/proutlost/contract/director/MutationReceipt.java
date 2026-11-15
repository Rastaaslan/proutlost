package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record MutationReceipt(
    Id world_id,
    SchemaVersion schema_version,
    Id mutation_id,
    long revision,
    Digest plan_digest,
    Digest before_digest,
    Digest after_digest,
    Revision resulting_physical_revision,
    Id journal_ref,
    MutationStatus status,
    List<EvidenceRef> evidence_refs) {
    public MutationReceipt {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(mutation_id, "mutation_id");
        Checks.nonnegative(revision, "revision");
        Objects.requireNonNull(plan_digest, "plan_digest");
        Objects.requireNonNull(before_digest, "before_digest");
        Objects.requireNonNull(after_digest, "after_digest");
        Objects.requireNonNull(resulting_physical_revision, "resulting_physical_revision");
        Objects.requireNonNull(journal_ref, "journal_ref");
        Objects.requireNonNull(status, "status");
        evidence_refs = Checks.copy(evidence_refs);
        Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "Wrong record schema version");
    }
}
