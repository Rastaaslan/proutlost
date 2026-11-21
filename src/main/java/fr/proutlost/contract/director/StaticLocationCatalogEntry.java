package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record StaticLocationCatalogEntry(
    Id world_id,
    SchemaVersion schema_version,
    Id build_id,
    Id location_id,
    Revision revision,
    SourceType source_type,
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
    Provenance provenance,
    List<EvidenceRef> validation_evidence,
    Digest digest) {
    public StaticLocationCatalogEntry {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(build_id, "build_id");
        Objects.requireNonNull(location_id, "location_id");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(source_type, "source_type");
        semantic_region_ids = Checks.copy(semantic_region_ids);
        geometry = Checks.copy(geometry);
        tags = Checks.copy(tags);
        anchors = Checks.copy(anchors);
        surfaces = Checks.copy(surfaces);
        capabilities = Checks.copy(capabilities);
        protection = Checks.copy(protection);
        presentation_channels = Checks.copy(presentation_channels);
        recovery_anchors = Checks.copy(recovery_anchors);
        Objects.requireNonNull(provenance, "provenance");
        validation_evidence = Checks.copy(validation_evidence);
        Objects.requireNonNull(digest, "digest");
        Checks.require(schema_version.equals(SchemaVersion.BASE), "Wrong record schema version");
        Checks.require(!validation_evidence.isEmpty(), "Validation evidence required");
        Checks.require(source_type != SourceType.worldprep_static_structure || structure_instance_id != null, "Structure identity required"); CatalogValidation.entry(LocationStore.CATALOG, location_id, revision, geometry, anchors, surfaces, capabilities, protection, recovery_anchors);
    }
}
