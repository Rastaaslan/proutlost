package fr.proutlost.director.catalog;

import fr.proutlost.contract.director.*;
import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Conservative conflict expansion, never identity inference or chunk access. */
public final class GeometryConflicts {
    private GeometryConflicts() {}
    public record Site(LocationRef reference, Id structure, List<Bounds> geometry, List<Anchor> anchors,
                       List<InteractionSurface> surfaces, LocationRef predecessor) {
        public Site {
            Objects.requireNonNull(reference);
            geometry = List.copyOf(geometry); anchors = List.copyOf(anchors); surfaces = List.copyOf(surfaces);
            ReadyCatalog.require(!geometry.isEmpty() && geometry.size() <= Limits.MAX_ITEMS
                && anchors.size() <= Limits.MAX_ITEMS && surfaces.size() <= Limits.MAX_ITEMS, "Geometry cap");
        }
        public static Site of(StaticLocationCatalogEntry e) {
            return new Site(ReadyCatalog.ref(e), e.structure_instance_id(), e.geometry(), e.anchors(), e.surfaces(), e.provenance().predecessor());
        }
        public static Site of(RuntimeSite e) {
            return new Site(new LocationRef(LocationStore.RUNTIME_SITE, e.location_id(), e.revision()), e.structure_instance_id(),
                e.geometry(), e.anchors(), e.surfaces(), e.provenance().predecessor());
        }
    }
    public static boolean intersects(Bounds a, Bounds b) {
        return a.dimension_id().equals(b.dimension_id()) && a.min().x() <= b.max().x() && b.min().x() <= a.max().x()
            && a.min().y() <= b.max().y() && b.min().y() <= a.max().y()
            && a.min().z() <= b.max().z() && b.min().z() <= a.max().z();
    }
    /** Transitive closure deliberately over-reserves chains of overlapping sites. Include retained aliases. */
    public static Set<LocationRef> expand(LocationRef requested, Collection<Site> sites) {
        // Prepared and runtime histories have independent budgets. Runtime's budget already
        // includes reserved retirement revisions; expansion must accommodate both authorities.
        ReadyCatalog.require(sites.size() <= Limits.MAX_ITEMS * LocationStore.values().length, "Conflict index cap");
        var counts = new EnumMap<LocationStore, Integer>(LocationStore.class);
        var entries = new HashMap<LocationRef, Site>();
        for (var site : sites) {
            ReadyCatalog.require(counts.merge(site.reference().store(), 1, Integer::sum) <= Limits.MAX_ITEMS,
                "Conflict authority history cap");
            ReadyCatalog.require(entries.putIfAbsent(site.reference(), site) == null, "Duplicate conflict pin");
        }
        ReadyCatalog.require(entries.containsKey(requested), "Unknown conflict pin");
        for (var site : sites) ReadyCatalog.require(site.predecessor() == null || entries.containsKey(site.predecessor()), "Missing conflict predecessor");
        var result = new HashSet<LocationRef>();
        var pending = new ArrayDeque<LocationRef>(); pending.add(requested); result.add(requested);
        while (!pending.isEmpty()) {
            var a = entries.get(pending.remove());
            for (var b : sites) if (!result.contains(b.reference()) && conflicts(a, b)) {
                result.add(b.reference()); pending.add(b.reference());
            }
        }
        return Set.copyOf(result);
    }
    private static boolean conflicts(Site a, Site b) {
        if (a.reference().location_id().equals(b.reference().location_id()) || Objects.equals(a.predecessor(), b.reference())
            || Objects.equals(b.predecessor(), a.reference())) return true;
        if (a.structure() != null && a.structure().equals(b.structure()) && a.geometry().stream()
            .anyMatch(x -> b.geometry().stream().anyMatch(y -> x.dimension_id().equals(y.dimension_id())))) return true;
        if (a.geometry().stream().anyMatch(x -> b.geometry().stream().anyMatch(y -> intersects(x, y)))) return true;
        return a.anchors().stream().anyMatch(x -> b.anchors().stream().anyMatch(y -> x.dimension_id().equals(y.dimension_id()) && x.position().equals(y.position())));
    }
}
