package fr.proutlost.contract.director;

import java.util.*;

/** Structural/reference checks only; never observes or loads the world. */
public final class CatalogValidation {
    private CatalogValidation() {}
    static void snapshot(Id world, Id build, List<SemanticRegion> regions, List<StaticLocationCatalogEntry> entries) {
        var regionIds = unique(regions.stream().map(SemanticRegion::region_id).toList());
        unique(entries.stream().map(StaticLocationCatalogEntry::location_id).toList());
        for (var entry : entries) {
            Checks.require(world.equals(entry.world_id()) && build.equals(entry.build_id()), "Catalog identity mismatch");
            Checks.require(regionIds.containsAll(entry.semantic_region_ids()), "Unknown semantic region");
        }
    }
    static void entry(Vocabulary.LocationStore authority, Id location, Revision revision, List<Bounds> geometry, List<Anchor> anchors,
                      List<InteractionSurface> surfaces, List<LocationCapability> capabilities,
                      List<ProtectedZone> protection, List<AnchorRef> recovery) {
        Checks.require(!geometry.isEmpty(), "Empty location geometry");
        var anchorIds = unique(anchors.stream().map(Anchor::anchor_id).toList());
        var surfaceIds = unique(surfaces.stream().map(InteractionSurface::surface_id).toList());
        var zoneIds = unique(protection.stream().map(ProtectedZone::zone_id).toList());
        unique(capabilities.stream().map(LocationCapability::capability_id).toList());
        for (var a : anchors) Checks.require(geometry.stream().anyMatch(b -> contains(b, a.dimension_id(), a.position())), "Anchor outside location");
        for (var s : surfaces) {
            Checks.require(anchorIds.containsAll(s.anchor_ids()) && zoneIds.containsAll(s.protected_zone_ids()), "Unknown surface reference");
            unique(s.slot_ids()); unique(s.anchor_ids());
        }
        for (var c : capabilities) {
            Checks.require(anchorIds.containsAll(c.anchor_ids()) && surfaceIds.containsAll(c.surface_ids()), "Unknown capability reference");
            Checks.require(!c.evidence().isEmpty(), "Capability evidence required");
        }
        for (var r : recovery) Checks.require(r.location().store() == authority && r.location().location_id().equals(location) && r.location().revision().equals(revision) && anchorIds.contains(r.anchor_id()), "Invalid recovery anchor");
    }
    private static <T> Set<T> unique(List<T> values) {
        var set = new HashSet<>(values); Checks.require(set.size() == values.size(), "Duplicate catalog identity"); return set;
    }
    private static boolean contains(Bounds b, Id dimension, Vec3 p) {
        return b.dimension_id().equals(dimension) && p.x() >= b.min().x() && p.x() <= b.max().x()
            && p.y() >= b.min().y() && p.y() <= b.max().y() && p.z() >= b.min().z() && p.z() <= b.max().z();
    }
    public static void requireSealed(CatalogSnapshot snapshot) {
        Checks.require(StrictCodec.contentDigest(snapshot).equals(snapshot.digest()), "Catalog digest mismatch");
        for (var entry : snapshot.entries()) Checks.require(StrictCodec.contentDigest(entry).equals(entry.digest()), "Entry digest mismatch");
    }
    public static MemoryCellKey cell(MemoryGridDefinition grid, int x, int z) {
        return new MemoryCellKey(grid.dimension_id(), grid.revision(),
            Math.toIntExact(Math.floorDiv((long)x - grid.origin_x(), grid.cell_size_blocks())),
            Math.toIntExact(Math.floorDiv((long)z - grid.origin_z(), grid.cell_size_blocks())));
    }
}
