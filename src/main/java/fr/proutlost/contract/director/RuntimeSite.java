package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record RuntimeSite(
    Id world_id,
    SchemaVersion schema_version,
    Id location_id,
    Revision revision,
    SiteSource source_type,
    SiteStatus status,
    @Nullable Id structure_instance_id,
    Set<SemanticRegionId> semantic_region_ids,
    List<Bounds> geometry,
    Set<Id> tags,
    List<Anchor> anchors,
    List<InteractionSurface> surfaces,
    List<LocationCapability> capabilities,
    List<ProtectedZone> protection,
    Set<Channel> presentation_channels,
    List<AnchorRef> recovery_anchors,
    @Nullable AudienceScope owner_scope,
    Instant recognized_at,
    @Nullable Instant retired_at,
    Provenance provenance,
    List<EvidenceRef> validation_evidence,
    Digest digest) {
    public RuntimeSite {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(location_id, "location_id");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(source_type, "source_type");
        Objects.requireNonNull(status, "status");
        semantic_region_ids = Checks.copy(semantic_region_ids);
        geometry = Checks.copy(geometry);
        tags = Checks.copy(tags);
        anchors = Checks.copy(anchors);
        surfaces = Checks.copy(surfaces);
        capabilities = Checks.copy(capabilities);
        protection = Checks.copy(protection);
        presentation_channels = Checks.copy(presentation_channels);
        recovery_anchors = Checks.copy(recovery_anchors);
        Objects.requireNonNull(recognized_at, "recognized_at");
        Objects.requireNonNull(provenance, "provenance");
        validation_evidence = Checks.copy(validation_evidence);
        Objects.requireNonNull(digest, "digest");
        Checks.require(schema_version.equals(SchemaVersion.BASE), "Wrong record schema version");
        Checks.require(!validation_evidence.isEmpty(), "Validation evidence required");
        Checks.require((status == SiteStatus.RETIRED) == (retired_at != null), "Retirement mismatch"); CatalogValidation.entry(LocationStore.RUNTIME_SITE, location_id, revision, geometry, anchors, surfaces, capabilities, protection, recovery_anchors);
    }
}
