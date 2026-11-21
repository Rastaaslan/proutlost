package fr.proutlost.director.integration.locations;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.LocationImportPort;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;

/** Standalone import registry. All filesystem/validation work runs on the supplied isolated writer.
 * Its private directory must have one owner. Archive files are immutable; CURRENT is the commit point.
 * Published snapshots are immutable and can be retained by running instances across imports.
 */
public final class StandaloneLocationImporter implements LocationImportPort {
    /** Native J04 ingress ceilings. Sharing these checks does not authorize an import. */
    public static final int NATIVE_MAX_BYTES=32768,NATIVE_MAX_ENTRIES=96;
    public static void requireNativeEnvelope(LocationImport input) {
        Objects.requireNonNull(input);
        Checks.require(new ModelCodec<>(LocationImport.class).encode(input).length<=NATIVE_MAX_BYTES,
                "native import canonical byte bound; retain the complete prior source");
        Checks.require((long)input.regions().size()+input.locations().size()+input.anchors().size()<=NATIVE_MAX_ENTRIES,
                "native import combined entry bound; no truncated or paged source replacement");
    }
    public record Snapshot(Map<ResourceId, LocationImport> current, Map<UUID, JsonValue> historicalInactive) {
        public Snapshot { current = Map.copyOf(current); historicalInactive = Map.copyOf(historicalInactive); }
    }
    private final Path directory;
    private final WorldId world;
    private final Map<DimensionId, ImportBounds> dimensions;
    private final Predicate<TagId> acceptedTag;
    private final Executor writer;
    private final int maximumBytes, maximumEntries;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot(Map.of(), Map.of()));
    private final ModelCodec<LocationImport> codec = new ModelCodec<>(LocationImport.class);
    private volatile boolean failed;
    private FileChannel lockChannel;
    private FileLock lock;
    private final Map<UUID, ResourceId> identityOwners = new HashMap<>();
    private final Map<String, LocationId> externalIdentities = new HashMap<>();
    private Map<String, String> committed = new TreeMap<>();

    private StandaloneLocationImporter(Path directory, WorldId world, Map<DimensionId, ImportBounds> dimensions,
            Predicate<TagId> acceptedTag, Executor writer, int maximumBytes, int maximumEntries) {
        this.directory = directory; this.world = world; this.dimensions = Map.copyOf(dimensions);
        this.acceptedTag = acceptedTag; this.writer = writer;
        if (maximumBytes < 1 || maximumBytes > 16777216 || maximumEntries < 1) throw new IllegalArgumentException("import limits");
        this.maximumBytes = maximumBytes; this.maximumEntries = maximumEntries;
    }
    /** Invoke without waiting on the server thread. Existing corrupt data fails closed. */
    public static CompletionStage<StandaloneLocationImporter> open(Path directory, WorldId world,
            Map<DimensionId, ImportBounds> dimensions, Predicate<TagId> acceptedTag, Executor writer,
            int maximumBytes, int maximumEntries) {
        return CompletableFuture.supplyAsync(() -> {
            var importer = new StandaloneLocationImporter(directory, world, dimensions, acceptedTag, writer, maximumBytes, maximumEntries);
            try { importer.restore(); return importer; }
            catch (IOException | RuntimeException e) {
                try { importer.releaseLock(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
                throw new CompletionException(e);
            }
        }, writer);
    }
    public Snapshot snapshot() { return snapshot.get(); }
    public CompletionStage<LocationImportResult> submit(LocationImport input) {
        Objects.requireNonNull(input);
        if (failed) return CompletableFuture.completedFuture(reject(input, ReasonCode.WAL_UNAVAILABLE));
        if (!busy.compareAndSet(false, true)) return CompletableFuture.completedFuture(reject(input, ReasonCode.RATE_LIMITED));
        // A preceding writer can fail between the first check and acquiring the slot.
        if (failed) { busy.set(false); return CompletableFuture.completedFuture(reject(input, ReasonCode.WAL_UNAVAILABLE)); }
        var result = new CompletableFuture<LocationImportResult>();
        try { writer.execute(() -> {
            LocationImportResult outcome;
            try { outcome = install(input); }
            catch (IOException e) { failed = true; outcome = reject(input, ReasonCode.WAL_UNAVAILABLE); }
            catch (IllegalArgumentException e) { outcome = reject(input, ReasonCode.CATALOG_INVALID); }
            catch (RuntimeException e) { failed = true; busy.set(false); result.completeExceptionally(e); return; }
            busy.set(false); result.complete(outcome);
        }); } catch (RejectedExecutionException e) { busy.set(false); result.complete(reject(input, ReasonCode.WAL_UNAVAILABLE)); }
        return result.minimalCompletionStage();
    }
    /** Closed JSON decode is also dispatched off the actor. */
    public CompletionStage<LocationImportResult> submitJson(byte[] bytes) {
        if (bytes.length > maximumBytes) return CompletableFuture.failedFuture(new IllegalArgumentException("import bytes"));
        if (failed) return CompletableFuture.failedFuture(new IllegalStateException("WAL_UNAVAILABLE: registry fenced; reopen and reconcile before retry"));
        if (!busy.compareAndSet(false, true)) return CompletableFuture.failedFuture(new RejectedExecutionException("import backpressure"));
        if (failed) { busy.set(false); return CompletableFuture.failedFuture(new IllegalStateException("WAL_UNAVAILABLE: registry fenced; reopen and reconcile before retry")); }
        byte[] copy = bytes.clone(); var result = new CompletableFuture<LocationImportResult>();
        try { writer.execute(() -> {
            try { var outcome = install(codec.decode(copy)); busy.set(false); result.complete(outcome); }
            catch (IOException e) { failed = true; busy.set(false); result.completeExceptionally(e); }
            catch (RuntimeException e) { busy.set(false); result.completeExceptionally(e); }
        }); } catch (RejectedExecutionException e) { busy.set(false); result.completeExceptionally(e); }
        return result.minimalCompletionStage();
    }

    private LocationImportResult install(LocationImport input) throws IOException {
        validate(input);
        validateIdentities(input);
        var before = snapshot.get(); var previous = before.current().get(input.sourceNamespace());
        if (previous != null && input.exportRevision().compareTo(previous.exportRevision()) <= 0) {
            if (input.exportRevision().equals(previous.exportRevision()) && input.exportDigest().equals(previous.exportDigest())) return success(input);
            return reject(input, ReasonCode.IDEMPOTENCY_CONFLICT);
        }
        Map<ResourceId, LocationImport> current = new HashMap<>(before.current()); current.put(input.sourceNamespace(), input);
        Map<UUID, JsonValue> inactive = new HashMap<>(before.historicalInactive());
        if (previous != null) inactive.putAll(entries(previous));
        entries(input).keySet().forEach(inactive::remove);
        var next = new Snapshot(current, inactive);
        byte[] bytes = codec.encode(input);
        String name = BlobContent.hash(bytes).value() + ".json";
        Path archive = directory.resolve(name);
        if (!Files.exists(archive)) writeAtomic(archive, bytes);
        else Checks.require(Arrays.equals(bytes, Files.readAllBytes(archive)), "archive collision");
        Map<String, String> updated = new TreeMap<>(committed);
        updated.put(input.sourceNamespace().value() + "@" + input.exportRevision().value(), name);
        Map<String, JsonValue> manifest = new TreeMap<>(); updated.forEach((k,v) -> manifest.put(k, new JsonValue.Text(v)));
        var imports = new JsonValue.ObjectValue(manifest);
        writeAtomic(directory.resolve("CURRENT.json"), CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of(
                "schema_version", new JsonValue.Number(java.math.BigDecimal.ONE),
                "world_id", new JsonValue.Text(world.value().toString()),
                "imports", imports,
                "sha256", new JsonValue.Text(BlobContent.hash(CanonicalJsonV1.encode(imports)).value())))));
        committed = updated; rememberIdentities(input); snapshot.set(next);
        return success(input);
    }
    private void restore() throws IOException {
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve("WRITER.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock();
        if (lock == null) throw new IOException("import directory already owned");
        Path manifest = directory.resolve("CURRENT.json");
        if (!Files.exists(manifest)) return;
        if (Files.size(manifest) > 16777216) throw new IOException("manifest bound");
        var root = ((JsonValue.ObjectValue) CanonicalJsonV1.decode(Files.readAllBytes(manifest))).values();
        Checks.require(root.keySet().equals(Set.of("schema_version","world_id","imports","sha256")), "manifest fields");
        Checks.require(new JsonValue.Number(java.math.BigDecimal.ONE).equals(root.get("schema_version")) &&
                new JsonValue.Text(world.value().toString()).equals(root.get("world_id")), "manifest version/world");
        var object = (JsonValue.ObjectValue) root.get("imports");
        Checks.require(!object.values().isEmpty() && new JsonValue.Text(BlobContent.hash(CanonicalJsonV1.encode(object)).value()).equals(root.get("sha256")), "manifest digest");
        List<LocationImport> imports = new ArrayList<>();
        for (var entry : object.values().entrySet()) {
            String name = ((JsonValue.Text) entry.getValue()).value();
            Checks.require(name.matches("[a-f0-9]{64}\\.json"), "archive name");
            Path path = directory.resolve(name);
            if (Files.size(path) > maximumBytes) throw new IOException("archive bound");
            byte[] bytes = Files.readAllBytes(path);
            Checks.require((BlobContent.hash(bytes).value() + ".json").equals(name), "archive digest");
            var input = codec.decode(bytes); validate(input);
            Checks.require(entry.getKey().equals(input.sourceNamespace().value()+"@"+input.exportRevision().value()), "manifest identity");
            imports.add(input); committed.put(entry.getKey(), name);
        }
        imports.sort(Comparator.comparing(LocationImport::exportRevision));
        Map<ResourceId, LocationImport> current = new HashMap<>(); Map<UUID, JsonValue> inactive = new HashMap<>();
        for (var input : imports) {
            validateIdentities(input); rememberIdentities(input);
            var prior = current.put(input.sourceNamespace(), input);
            if (prior != null) inactive.putAll(entries(prior));
            entries(input).keySet().forEach(inactive::remove);
        }
        snapshot.set(new Snapshot(current, inactive));
    }
    /** Close on the writer executor. No blocking close/IO is performed by the calling actor. */
    public CompletionStage<Void> closeAsync() {
        if (!busy.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IllegalStateException("import in progress"));
        failed = true;
        return CompletableFuture.runAsync(() -> {
            try { releaseLock(); } catch (IOException e) { throw new CompletionException(e); }
        }, writer);
    }
    private void releaseLock() throws IOException {
        try { if (lock != null && lock.isValid()) lock.release(); }
        finally { if (lockChannel != null) lockChannel.close(); }
    }
    private void validateIdentities(LocationImport input) {
        for (var id : entries(input).keySet()) {
            var owner = identityOwners.get(id);
            Checks.require(owner == null || owner.equals(input.sourceNamespace()), "historical source collision");
        }
        for (var location : input.locations()) {
            var old = externalIdentities.get(input.sourceNamespace().value()+"\0"+location.externalId());
            Checks.require(old == null || old.equals(location.locationId()), "historical external identity");
        }
    }
    private void rememberIdentities(LocationImport input) {
        entries(input).keySet().forEach(id -> identityOwners.put(id,input.sourceNamespace()));
        input.locations().forEach(l -> externalIdentities.put(input.sourceNamespace().value()+"\0"+l.externalId(),l.locationId()));
    }
    private void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(directory, "import-", ".tmp");
        try {
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) file.write(buffer); file.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
        } finally { Files.deleteIfExists(temporary); }
    }
    public static Fingerprint digest(LocationImport input) {
        return LocationTransferContract.digest(input);
    }
    private void validate(LocationImport input) {
        Checks.require(input.worldId().equals(world), "import world");
        Checks.require(codec.encode(input).length <= maximumBytes &&
                (long)input.regions().size()+input.locations().size()+input.anchors().size() <= maximumEntries, "import bound");
        Checks.require(input.exportDigest().equals(digest(input)), "import digest");
        entries(input); // Also rejects IDs duplicated across kinds.
        Map<RegionId, ImportedRegion> regions = new HashMap<>(); input.regions().forEach(r -> regions.put(r.regionId(),r));
        Map<LocationId, ImportedLocation> locations = new HashMap<>(); Set<String> external = new HashSet<>();
        for (var r : input.regions()) { bounds(r.dimension(), r.bounds()); tags(r.tags()); }
        for (var l : input.locations()) {
            bounds(l.dimension(), l.bounds()); tags(l.tags()); Checks.require(external.add(l.externalId()), "duplicate external id");
            for (var id : l.regionIds()) Checks.require(regions.containsKey(id) && regions.get(id).dimension().equals(l.dimension()), "region reference");
            locations.put(l.locationId(), l);
        }
        for (var a : input.anchors()) {
            tags(a.tags()); var location = locations.get(a.locationId());
            Checks.require(location != null && location.dimension().equals(a.dimension()), "location reference");
            var p = a.position(); var border = dimensions.get(a.dimension());
            Checks.require(border != null && contains(border,p) && contains(location.bounds(),p), "anchor bounds");
        }
    }
    private static boolean contains(ImportBounds b, ImportPosition p) {
        return p.x()>=b.minX() && p.x()<b.maxXExclusive() && p.y()>=b.minY() && p.y()<b.maxYExclusive() && p.z()>=b.minZ() && p.z()<b.maxZExclusive();
    }
    private void bounds(DimensionId dimension, ImportBounds b) {
        var border = dimensions.get(dimension);
        Checks.require(border != null && b.minX()>=border.minX() && b.minY()>=border.minY() && b.minZ()>=border.minZ() &&
                b.maxXExclusive()<=border.maxXExclusive() && b.maxYExclusive()<=border.maxYExclusive() && b.maxZExclusive()<=border.maxZExclusive(), "dimension/border/height");
    }
    private void tags(Set<TagId> tags) { Checks.require(tags.stream().allMatch(acceptedTag), "unknown tag"); }
    private static Map<UUID, JsonValue> entries(LocationImport input) {
        Map<UUID,JsonValue> result = new HashMap<>();
        input.regions().forEach(v -> put(result,v.regionId().value(),new ModelCodec<>(ImportedRegion.class).encode(v)));
        input.locations().forEach(v -> put(result,v.locationId().value(),new ModelCodec<>(ImportedLocation.class).encode(v)));
        input.anchors().forEach(v -> put(result,v.anchorId().value(),new ModelCodec<>(ImportedAnchor.class).encode(v)));
        return result;
    }
    private static void put(Map<UUID,JsonValue> map, UUID id, byte[] bytes) { Checks.require(map.putIfAbsent(id,CanonicalJsonV1.decode(bytes))==null,"duplicate import id"); }
    private LocationImportResult success(LocationImport input) {
        Map<RegionId,RegionId> ids = new HashMap<>(); input.regions().forEach(r -> ids.put(r.regionId(),r.regionId()));
        return new LocationImportResult(AckStatus.DURABLE,input.exportRevision(),ids,List.of());
    }
    private LocationImportResult reject(LocationImport input, ReasonCode reason) {
        var error = new SystemError(reason,Severity.ERROR,reason==ReasonCode.WAL_UNAVAILABLE || reason==ReasonCode.RATE_LIMITED,
                (reason==ReasonCode.WAL_UNAVAILABLE || reason==ReasonCode.RATE_LIMITED)?Optional.of(new RetryCondition(reason,Optional.empty(),Set.of())):Optional.empty(),Set.of(),new CorrelationId(UUID.randomUUID()),reason==ReasonCode.WAL_UNAVAILABLE?SafeState.READ_ONLY_SAFE:SafeState.UNCHANGED);
        return new LocationImportResult(reason==ReasonCode.WAL_UNAVAILABLE?AckStatus.UNAVAILABLE:AckStatus.REJECTED,input.exportRevision(),Map.of(),List.of(error));
    }
}

