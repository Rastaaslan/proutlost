package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record StoredRow(
    RowKey key,
    SchemaVersion schema_version,
    long revision,
    String canonical_payload,
    Digest digest) {
    public StoredRow {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(schema_version, "schema_version");
        Checks.nonnegative(revision, "revision");
        Objects.requireNonNull(canonical_payload, "canonical_payload");
        Checks.rowPayload(canonical_payload);
        Objects.requireNonNull(digest, "digest");
        Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "Wrong record schema version");
        Checks.require(Digests.sha256(canonical_payload).equals(digest), "Row checksum mismatch"); Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "No legacy store import");
    }
}
