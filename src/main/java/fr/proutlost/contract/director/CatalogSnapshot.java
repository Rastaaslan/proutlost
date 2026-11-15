package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record CatalogSnapshot(
    Id world_id,
    SchemaVersion schema_version,
    Id build_id,
    Revision catalog_revision,
    Digest digest,
    CatalogState state,
    Instant validated_at,
    List<EvidenceRef> validation_evidence,
    List<SemanticRegion> regions,
    List<StaticLocationCatalogEntry> entries) {
    public CatalogSnapshot {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(build_id, "build_id");
        Objects.requireNonNull(catalog_revision, "catalog_revision");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(validated_at, "validated_at");
        validation_evidence = Checks.copy(validation_evidence);
        regions = Checks.copy(regions);
        entries = Checks.copy(entries);
        Checks.require(schema_version.equals(SchemaVersion.BASE), "Wrong record schema version");
        Checks.require(!validation_evidence.isEmpty(), "Validation evidence required");
        CatalogValidation.snapshot(world_id, build_id, regions, entries);
    }
}
