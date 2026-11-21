package fr.proutlost.director.catalog;

import fr.proutlost.contract.director.*;
import java.util.*;
import java.util.function.Supplier;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Whole-snapshot publication. Failure retains prepared truth but closes new admission. */
public final class ReadyCatalog implements LocationCatalogView {
    private final Id world, build;
    private volatile Index index;
    private DirectorStore runtimeStore;
    private Supplier<Set<Id>> runtimeIds = Set::of;

    /** Bind the separate authority without granting it prepared-catalog ownership. */
    synchronized void bindRuntimeIdentities(DirectorStore store, Supplier<Set<Id>> ids) {
        require(runtimeStore == null || runtimeStore == store, "Catalog already bound to another runtime store");
        runtimeStore = store; runtimeIds = ids;
        if (!Collections.disjoint(ownedIds(), runtimeIds.get())) closeAdmission();
    }
    Set<Id> ownedIds() {
        var current = index;
        if (current == null) return Set.of();
        var ids = new HashSet<Id>();
        current.history.keySet().forEach(ref -> ids.add(ref.location_id()));
        return Set.copyOf(ids);
    }
    private record Index(CatalogSnapshot snapshot, NavigableMap<Id, StaticLocationCatalogEntry> entries,
                         Map<Id, Set<Id>> capabilities, Map<SemanticRegionId, Set<Id>> regions,
                         Map<LocationRef, StaticLocationCatalogEntry> history,
                         Map<Revision, Digest> revisions, boolean admitting) {}

    public ReadyCatalog(Id world, Id build) {
        this.world = Objects.requireNonNull(world); this.build = Objects.requireNonNull(build);
    }

    public synchronized void importJson(String json) {
        try { install(StrictCodec.decode(json, CatalogSnapshot.class)); }
        catch (RuntimeException failure) { closeAdmission(); throw failure; }
    }

    public synchronized void install(CatalogSnapshot snapshot) {
        try {
            require(snapshot.world_id().equals(world) && snapshot.build_id().equals(build), "Catalog world/build mismatch");
            require(snapshot.state() == CatalogState.READY, "Catalog not READY");
            CatalogValidation.requireSealed(snapshot);
            var old = index;
            var history = new HashMap<LocationRef, StaticLocationCatalogEntry>();
            var revisions = new HashMap<Revision, Digest>();
            if (old != null) { history.putAll(old.history); revisions.putAll(old.revisions); }
            var priorDigest = revisions.putIfAbsent(snapshot.catalog_revision(), snapshot.digest());
            require(priorDigest == null || priorDigest.equals(snapshot.digest()), "Catalog revision reused");
            var entries = new TreeMap<Id, StaticLocationCatalogEntry>();
            var capabilities = new HashMap<Id, Set<Id>>();
            var regions = new HashMap<SemanticRegionId, Set<Id>>();
            for (var region : snapshot.regions()) {
                require(!region.bounds().isEmpty(), "Empty semantic region");
                regions.put(region.region_id(), new HashSet<>());
            }
            for (var entry : snapshot.entries()) {
                require(!entry.provenance().evidence().isEmpty(), "Missing provenance evidence");
                var previous = history.putIfAbsent(ref(entry), entry);
                require(previous == null || previous.equals(entry), "Location revision reused");
                entries.put(entry.location_id(), entry);
                entry.semantic_region_ids().forEach(r -> regions.get(r).add(entry.location_id()));
                entry.capabilities().forEach(c -> capabilities.computeIfAbsent(c.capability_id(), ignored -> new HashSet<>()).add(entry.location_id()));
            }
            require(history.size() <= Limits.MAX_ITEMS && revisions.size() <= Limits.MAX_ITEMS, "Catalog history cap");
            for (var entry : snapshot.entries()) {
                var visited = new HashSet<LocationRef>();
                var cursor = entry;
                while (cursor != null) {
                    require(visited.add(ref(cursor)), "Alias cycle");
                    var predecessor = cursor.provenance().predecessor();
                    if (predecessor == null) break;
                    require(predecessor.store() == LocationStore.CATALOG && history.containsKey(predecessor), "Unknown alias predecessor");
                    cursor = history.get(predecessor);
                }
            }
            if (old != null) for (var removed : old.entries.values()) {
                require(entries.containsKey(removed.location_id()) || snapshot.entries().stream()
                    .anyMatch(e -> Objects.equals(e.provenance().predecessor(), ref(removed))), "Removal requires provenance migration");
            }
            require(Collections.disjoint(history.keySet().stream().map(LocationRef::location_id).toList(), runtimeIds.get()),
                "Prepared/runtime ID collision");
            index = new Index(snapshot, Collections.unmodifiableNavigableMap(entries), freeze(capabilities), freeze(regions),
                Map.copyOf(history), Map.copyOf(revisions), true);
        } catch (RuntimeException failure) { closeAdmission(); throw failure; }
    }

    private void closeAdmission() {
        var old = index;
        if (old != null) index = new Index(old.snapshot, old.entries, old.capabilities, old.regions, old.history, old.revisions, false);
    }
    public List<GeometryConflicts.Site> conflictSites() {
        var current = index;
        return current == null ? List.of() : current.history.values().stream().map(GeometryConflicts.Site::of).toList();
    }
    public boolean admitting() { var current = index; return current != null && current.admitting; }
    @Override public CatalogSnapshot snapshot() {
        var current = index;
        if (current == null) throw new IllegalStateException("No READY catalog");
        return current.snapshot;
    }
    @Override public Optional<StaticLocationCatalogEntry> lookup(LocationRef reference) {
        var current = index;
        return current == null ? Optional.empty() : Optional.ofNullable(current.history.get(reference));
    }
    /** Current pins only; historical lookup never makes an old pin newly eligible. */
    public boolean current(LocationRef ref) {
        var current = index;
        return current != null && current.admitting && ref.store() == LocationStore.CATALOG
            && Optional.ofNullable(current.entries.get(ref.location_id())).map(ReadyCatalog::ref).filter(ref::equals).isPresent();
    }
    @Override public List<LocationRef> query(CatalogQuery query) {
        var current = index;
        if (current == null || !current.admitting) return List.of();
        var ids = new TreeSet<>(current.entries.keySet());
        for (var capability : query.capabilities()) ids.retainAll(current.capabilities.getOrDefault(CapabilityTypes.canonical(capability), Set.of()));
        if (!query.regions().isEmpty()) {
            var regionIds = new HashSet<Id>();
            query.regions().forEach(r -> regionIds.addAll(current.regions.getOrDefault(r, Set.of())));
            ids.retainAll(regionIds);
        }
        return (query.after_id() == null ? ids : ids.tailSet(query.after_id(), false)).stream()
            .limit(query.limit()).map(id -> ref(current.entries.get(id))).toList();
    }
    public static LocationRef ref(StaticLocationCatalogEntry entry) {
        return new LocationRef(LocationStore.CATALOG, entry.location_id(), entry.revision());
    }
    private static <K> Map<K, Set<Id>> freeze(Map<K, Set<Id>> source) {
        var result = new HashMap<K, Set<Id>>(); source.forEach((key, values) -> result.put(key, Set.copyOf(values))); return Map.copyOf(result);
    }
    static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
