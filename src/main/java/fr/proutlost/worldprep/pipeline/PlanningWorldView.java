package fr.proutlost.worldprep.pipeline;

import fr.proutlost.worldprep.storage.PagedPlanStore;
import fr.proutlost.worldprep.storage.PlanManifest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * A read-only view of the candidate world plus exact sealed upstream plans.
 *
 * <p>The first layer containing a key wins. Production layers decode at most
 * {@code cachePages} plan pages and never construct a complete-world overlay.
 * A lookup which is not in the cache scans the immutable pages deterministically;
 * this deliberately favours bounded memory and exact durable identity over an
 * unbounded secondary index.</p>
 */
public final class PlanningWorldView<K, V> {
    private final Function<K, V> actual;
    private final List<Lookup<K, V>> layers;

    /** Compatibility constructor for small, already-bounded analysis overlays. */
    public PlanningWorldView(Function<K, V> actual, List<Map<K, V>> upstreamInPriorityOrder) {
        this.actual = Objects.requireNonNull(actual, "actual");
        this.layers = upstreamInPriorityOrder.stream()
                .map(values -> (Lookup<K, V>) new MemoryLookup<>(values))
                .toList();
    }

    private PlanningWorldView(Function<K, V> actual, List<Lookup<K, V>> layers, boolean ignored) {
        this.actual = Objects.requireNonNull(actual, "actual");
        this.layers = List.copyOf(layers);
    }

    /**
     * Opens production layers and validates every manifest and page before the
     * view becomes observable. Layers must be supplied newest/highest priority
     * first and must be exact dependencies of {@code consumerPass}.
     */
    public static <K, V, E> PlanningWorldView<K, V> open(
            Function<K, V> actual,
            PassId consumerPass,
            List<SealedLayer<K, V, E>> upstreamInPriorityOrder,
            int cachePages) throws IOException {
        Objects.requireNonNull(consumerPass, "consumerPass");
        Objects.requireNonNull(upstreamInPriorityOrder, "upstreamInPriorityOrder");
        if (cachePages < 1) throw new IllegalArgumentException("cachePages must be positive");
        var allowed = PassGraph.dependencies(consumerPass);
        var seen = java.util.EnumSet.noneOf(PassId.class);
        var result = new ArrayList<Lookup<K, V>>(upstreamInPriorityOrder.size());
        for (var layer : upstreamInPriorityOrder) {
            PlanManifest manifest = layer.manifest();
            manifest.requireApplicable();
            if (!allowed.contains(manifest.passId()))
                throw new IllegalStateException(manifest.passId() + " is not an upstream dependency of " + consumerPass);
            if (!seen.add(manifest.passId()))
                throw new IllegalStateException("Duplicate upstream layer " + manifest.passId());
            if (!manifest.planId().equals(layer.expectedPlanId())
                    || !manifest.rootFingerprint().equals(layer.expectedRootFingerprint()))
                throw new IllegalStateException("Stale upstream identity for " + manifest.passId());
            PlanManifest durable = PagedPlanStore.readManifest(layer.directory());
            if (!durable.equals(manifest)) throw new IOException("Exact sealed upstream manifest mismatch");
            PagedPlanStore.validatePages(layer.directory(), manifest);
            result.add(new PagedLookup<>(layer, cachePages));
        }
        if (!seen.containsAll(allowed)) {
            var missing = java.util.EnumSet.copyOf(allowed);
            missing.removeAll(seen);
            throw new IllegalStateException("Missing exact upstream layers: " + missing);
        }
        return new PlanningWorldView<>(actual, result, true);
    }

    public V get(K key) {
        Objects.requireNonNull(key, "key");
        for (var layer : layers) {
            Optional<V> value = layer.find(key);
            if (value.isPresent()) return value.get();
        }
        return actual.apply(key);
    }

    /** Maximum number of decoded pages resident across this view. */
    public int maximumResidentPages() {
        return layers.stream().mapToInt(Lookup::maximumResidentPages).sum();
    }

    public record SealedLayer<K, V, E>(Path directory, PlanManifest manifest,
            UUID expectedPlanId, String expectedRootFingerprint,
            PagedPlanStore.EntryCodec<E> codec, Function<E, K> key,
            Function<E, V> value) {
        public SealedLayer {
            Objects.requireNonNull(directory, "directory");
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(expectedPlanId, "expectedPlanId");
            Objects.requireNonNull(expectedRootFingerprint, "expectedRootFingerprint");
            Objects.requireNonNull(codec, "codec");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    private interface Lookup<K, V> {
        Optional<V> find(K key);
        int maximumResidentPages();
    }

    private static final class MemoryLookup<K, V> implements Lookup<K, V> {
        private final Map<K, V> values;
        MemoryLookup(Map<K, V> values) { this.values = Map.copyOf(values); }
        public Optional<V> find(K key) { return Optional.ofNullable(values.get(key)); }
        public int maximumResidentPages() { return 0; }
    }

    private static final class PagedLookup<K, V, E> implements Lookup<K, V> {
        private final SealedLayer<K, V, E> layer;
        private final int capacity;
        private final LinkedHashMap<Long, Map<K, V>> cache = new LinkedHashMap<>(16, .75f, true);

        PagedLookup(SealedLayer<K, V, E> layer, int capacity) {
            this.layer = layer;
            this.capacity = capacity;
        }

        public synchronized Optional<V> find(K wanted) {
            for (var page : layer.manifest().pages()) {
                Map<K, V> values = cache.get(page.sequence());
                if (values == null) values = load(page);
                if (values.containsKey(wanted)) return Optional.ofNullable(values.get(wanted));
            }
            return Optional.empty();
        }

        private Map<K, V> load(PlanManifest.PageReference page) {
            var values = new LinkedHashMap<K, V>(Math.max(1, page.entryCount()));
            long offset = layer.manifest().pages().subList(0, Math.toIntExact(page.sequence()))
                    .stream().mapToLong(PlanManifest.PageReference::entryCount).sum();
            try {
                PagedPlanStore.readRange(layer.directory(), layer.manifest(), layer.codec(),
                        offset, page.entryCount(), entry -> {
                            K key = Objects.requireNonNull(layer.key().apply(entry), "layer key");
                            V previous = values.putIfAbsent(key,
                                    Objects.requireNonNull(layer.value().apply(entry), "layer value"));
                            if (previous != null) throw new IOException("Duplicate key in sealed planning layer: " + key);
                        });
            } catch (IOException exception) {
                throw new IllegalStateException("Sealed planning layer became unavailable", exception);
            }
            cache.put(page.sequence(), Map.copyOf(values));
            while (cache.size() > capacity) cache.remove(cache.keySet().iterator().next());
            return cache.get(page.sequence());
        }

        public synchronized int maximumResidentPages() { return cache.size(); }
    }
}
