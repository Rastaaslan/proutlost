package fr.proutlost.director.integration.locations;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Resumable, single-owner staging on the SAME executor as the existing registry.
 * No page publishes a revision. Only the full validated source is submitted once to the registry.
 * Registry DURABLE is a registry receipt; it is never a native J04 or physical admission receipt.
 */
public final class LocationTransferInbox {
    private static final ModelCodec<LocationTransferManifest> MANIFEST = new ModelCodec<>(LocationTransferManifest.class);
    private static final ModelCodec<LocationTransferPage> PAGE = new ModelCodec<>(LocationTransferPage.class);
    private final Path root;
    private final WorldId world;
    private final StandaloneLocationImporter registry;
    private final Executor writer;
    @FunctionalInterface interface Probe { void at(String point) throws IOException; }
    private final Probe probe;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Map<ResourceId, LocationTransferManifest> active = new HashMap<>();
    private volatile boolean failed, closed;
    private FileChannel lockChannel;
    private FileLock lock;
    private LocationTransferInbox(Path root, WorldId world, StandaloneLocationImporter registry, Executor writer, Probe probe) {
        this.root = root.toAbsolutePath().normalize(); this.world = Objects.requireNonNull(world);
        this.registry = Objects.requireNonNull(registry); this.writer = Objects.requireNonNull(writer);
        this.probe = Objects.requireNonNull(probe);
    }
    public static CompletionStage<LocationTransferInbox> open(Path root, WorldId world,
            StandaloneLocationImporter registry, Executor writer) {
        return open(root, world, registry, writer, point -> {});
    }
    static CompletionStage<LocationTransferInbox> open(Path root, WorldId world,
            StandaloneLocationImporter registry, Executor writer, Probe probe) {
        return CompletableFuture.supplyAsync(() -> {
            var inbox = new LocationTransferInbox(root, world, registry, writer, probe);
            try { inbox.restore(); return inbox; }
            catch (IOException | RuntimeException error) {
                try { inbox.release(); } catch (IOException suppressed) { error.addSuppressed(suppressed); }
                throw new CompletionException(error);
            }
        }, writer);
    }
    @FunctionalInterface private interface IO<T> { T run() throws IOException; }
    private <T> CompletionStage<T> operation(IO<T> work) {
        if (closed || failed) return CompletableFuture.failedFuture(new IllegalStateException("transfer inbox unavailable"));
        if (!busy.compareAndSet(false, true)) return CompletableFuture.failedFuture(new RejectedExecutionException("transfer in progress"));
        var future = new CompletableFuture<T>();
        try { writer.execute(() -> {
            try {
                Checks.require(!closed && !failed, "transfer inbox unavailable");
                T value = work.run(); busy.set(false); future.complete(value);
            } catch (IOException error) { failed = true; busy.set(false); future.completeExceptionally(error); }
            catch (RuntimeException error) { busy.set(false); future.completeExceptionally(error); }
        }); } catch (RuntimeException error) { busy.set(false); future.completeExceptionally(error); }
        return future.minimalCompletionStage();
    }
    /** Completion means a frozen manifest was staged, never that an import was admitted. */
    public CompletionStage<Fingerprint> begin(byte[] message) {
        if (message.length > LocationTransfer.WIRE_BYTES) return CompletableFuture.failedFuture(new IllegalArgumentException("manifest wire bound"));
        byte[] frozen = message.clone();
        return operation(() -> {
            var value = MANIFEST.decode(frozen);
            Checks.require(Arrays.equals(frozen, MANIFEST.encode(value)) && value.worldId().equals(world), "manifest canonical bytes/world");
            var previous = active.get(value.sourceNamespace());
            if (previous != null && previous.equals(value)) return LocationTransfer.identity(value);
            if (previous != null) {
                // A different proposal must not overwrite a generation whose outcome is uncertain.
                Path receipt = directory(previous).resolve("REGISTRY_COMMITTED.json");
                Checks.require(Files.exists(receipt, LinkOption.NOFOLLOW_LINKS), "unfinished source generation retained");
                verifyReceipt(previous);
                Checks.require(value.exportRevision().compareTo(previous.exportRevision()) > 0, "transfer revision regression/conflict");
            }
            var sources = new HashSet<>(registry.snapshot().current().keySet()); sources.addAll(active.keySet());
            Checks.require(sources.contains(value.sourceNamespace()) || sources.size() < 32, "transfer source capacity");
            var current = registry.snapshot().current().get(value.sourceNamespace());
            Checks.require(current == null || value.exportRevision().compareTo(current.exportRevision()) > 0
                    || value.exportRevision().equals(current.exportRevision()) && value.exportDigest().equals(current.exportDigest()),
                    "transfer registry revision conflict");
            Path dir = directory(value); noSymlinks(dir); Files.createDirectories(dir); syncDirectory(root);
            Checks.require(!Files.exists(dir.resolve("CANCELLED.json"), LinkOption.NOFOLLOW_LINKS), "cancelled generation cannot be revived");
            writeImmutable(dir.resolve("manifest.json"), frozen);
            probe.at("MANIFEST_FILE_FORCED");
            var next = new HashMap<>(active); next.put(value.sourceNamespace(), value);
            writeCurrent(next); active.clear(); active.putAll(next);
            probe.at("CURRENT_POINTER_FORCED");
            return LocationTransfer.identity(value);
        });
    }
    /** Completion attests exactly this persisted byte fragment; it grants no import ACK. */
    public CompletionStage<Integer> accept(byte[] message) {
        if (message.length > LocationTransfer.WIRE_BYTES) return CompletableFuture.failedFuture(new IllegalArgumentException("page wire bound"));
        byte[] frozen = message.clone();
        return operation(() -> {
            var page = PAGE.decode(frozen);
            Checks.require(Arrays.equals(frozen, PAGE.encode(page)), "page canonical bytes");
            var manifest = find(page.manifestSha256());
            byte[] bytes = LocationTransfer.fragment(manifest, page);
            writeImmutable(directory(manifest).resolve("page-" + page.pageIndex() + ".bin"), bytes);
            probe.at("PAGE_FORCED:" + page.pageIndex());
            return page.pageIndex();
        });
    }
    /** No join/get on the caller or writer. The existing registry remains the only publication owner. */
    public CompletionStage<LocationImportResult> commit(Fingerprint identity) {
        if (closed || failed) return CompletableFuture.failedFuture(new IllegalStateException("transfer inbox unavailable"));
        if (!busy.compareAndSet(false, true)) return CompletableFuture.failedFuture(new RejectedExecutionException("transfer in progress"));
        var result = new CompletableFuture<LocationImportResult>();
        try { writer.execute(() -> {
            final byte[] bytes;
            try { bytes = assemble(find(identity)); }
            catch (IOException error) { failed = true; busy.set(false); result.completeExceptionally(error); return; }
            catch (RuntimeException error) { busy.set(false); result.completeExceptionally(error); return; }
            registry.submitJson(bytes).whenCompleteAsync((ack, error) -> {
                try {
                    if (error != null) throw new CompletionException(error);
                    if (ack.status() == AckStatus.DURABLE) {
                        probe.at("REGISTRY_DURABLE_BEFORE_RECEIPT");
                        var manifest = find(identity); var current = registry.snapshot().current().get(manifest.sourceNamespace());
                        Checks.require(current != null && current.exportRevision().equals(manifest.exportRevision())
                                && current.exportDigest().equals(manifest.exportDigest()), "registry changed before exact receipt");
                        writeImmutable(directory(manifest).resolve("REGISTRY_COMMITTED.json"), receipt(manifest));
                        probe.at("TRANSFER_RECEIPT_FORCED");
                    }
                    busy.set(false); result.complete(ack);
                } catch (IOException failure) { failed = true; busy.set(false); result.completeExceptionally(failure); }
                catch (RuntimeException failure) { busy.set(false); result.completeExceptionally(failure); }
            }, writer).exceptionally(error -> {
                // whenComplete preserves a registry's semantic refusal even after our
                // callback handled it. Fence only an undelivered callback (e.g. executor
                // shutdown), never reinterpret an already reported validation refusal.
                if (!result.isDone()) { failed = true; busy.set(false); result.completeExceptionally(error); }
                return null;
            });
        }); } catch (RuntimeException error) { busy.set(false); result.completeExceptionally(error); }
        return result.minimalCompletionStage();
    }
    /** Explicitly retire an uncommitted proposal without deleting evidence or touching the registry. */
    public CompletionStage<Void> cancel(Fingerprint identity) {
        return operation(() -> {
            var manifest = find(identity); var current = registry.snapshot().current().get(manifest.sourceNamespace());
            Checks.require(current == null || !current.exportRevision().equals(manifest.exportRevision())
                    || !current.exportDigest().equals(manifest.exportDigest()), "registry already committed; reconcile receipt instead");
            byte[] marker = CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of(
                    "manifest_sha256", new JsonValue.Text(identity.value()),
                    "scope", new JsonValue.Text("CANCELLED_UNCOMMITTED_STAGING_ONLY"))));
            writeImmutable(directory(manifest).resolve("CANCELLED.json"), marker);
            var next = new HashMap<>(active); next.remove(manifest.sourceNamespace()); writeCurrent(next);
            active.clear(); active.putAll(next); return null;
        });
    }
    private LocationTransferManifest find(Fingerprint identity) {
        return active.values().stream().filter(m -> LocationTransfer.identity(m).equals(identity)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown transfer identity"));
    }
    private byte[] assemble(LocationTransferManifest value) throws IOException {
        var output = new ByteArrayOutputStream(value.sourceBytes());
        for (int i = 0; i < value.pageHashes().size(); i++) {
            Path file = directory(value).resolve("page-" + i + ".bin");
            Checks.require(Files.exists(file, LinkOption.NOFOLLOW_LINKS), "incomplete transfer; prior source retained");
            byte[] bytes = read(file, LocationTransfer.PAGE_BYTES);
            var page = new LocationTransferPage(LocationTransfer.identity(value), i, Base64.getEncoder().encodeToString(bytes));
            LocationTransfer.fragment(value, page); output.writeBytes(bytes);
        }
        byte[] bytes = output.toByteArray();
        var input = new ModelCodec<>(LocationImport.class).decode(bytes);
        LocationTransfer.matches(value, input, bytes);
        return bytes;
    }
    private Path directory(LocationTransferManifest value) { return root.resolve(LocationTransfer.identity(value).value()); }
    private byte[] receipt(LocationTransferManifest value) {
        return CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of(
                "schema_version", new JsonValue.Number(java.math.BigDecimal.ONE),
                "manifest_sha256", new JsonValue.Text(LocationTransfer.identity(value).value()),
                "scope", new JsonValue.Text("STANDALONE_REGISTRY_ONLY_NOT_NATIVE_J04_OR_PHYSICAL"))));
    }
    private void verifyReceipt(LocationTransferManifest value) throws IOException {
        Checks.require(Arrays.equals(receipt(value), read(directory(value).resolve("REGISTRY_COMMITTED.json"), 1024)), "transfer receipt mismatch");
        var current = registry.snapshot().current().get(value.sourceNamespace());
        Checks.require(current != null && current.exportRevision().compareTo(value.exportRevision()) >= 0
                && (!current.exportRevision().equals(value.exportRevision()) || current.exportDigest().equals(value.exportDigest())),
                "transfer receipt ahead of registry");
    }
    private void restore() throws IOException {
        noSymlinks(root); Files.createDirectories(root);
        Path lockFile = root.resolve("WRITER.lock"); noSymlinks(lockFile);
        lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock(); if (lock == null) throw new IOException("transfer inbox already owned");
        Path file = root.resolve("CURRENT.json"); if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        var data = ((JsonValue.ObjectValue) CanonicalJsonV1.decode(read(file, 32768))).values();
        Checks.require(data.keySet().equals(Set.of("world_id", "sources"))
                && new JsonValue.Text(world.value().toString()).equals(data.get("world_id")), "transfer ledger fields/world");
        var sources = ((JsonValue.ObjectValue) data.get("sources")).values();
        Checks.require(sources.size() <= 32, "transfer source capacity");
        for (var entry : sources.entrySet()) {
            String id = ((JsonValue.Text) entry.getValue()).value(); new Fingerprint(id);
            var manifest = MANIFEST.decode(read(root.resolve(id).resolve("manifest.json"), LocationTransfer.WIRE_BYTES));
            Checks.require(LocationTransfer.identity(manifest).value().equals(id) && manifest.worldId().equals(world)
                    && manifest.sourceNamespace().value().equals(entry.getKey()), "transfer ledger identity");
            active.put(manifest.sourceNamespace(), manifest);
            // Cancellation's marker is forced before the root pivot. A crash between the two
            // leaves a non-publishable proposal, never permission to revive it.
            if (Files.exists(directory(manifest).resolve("CANCELLED.json"), LinkOption.NOFOLLOW_LINKS)) {
                var marker = ((JsonValue.ObjectValue) CanonicalJsonV1.decode(read(directory(manifest).resolve("CANCELLED.json"), 1024))).values();
                Checks.require(marker.equals(Map.of("manifest_sha256", new JsonValue.Text(id),
                        "scope", new JsonValue.Text("CANCELLED_UNCOMMITTED_STAGING_ONLY"))), "cancellation marker mismatch");
                active.remove(manifest.sourceNamespace()); continue;
            }
            for (int i = 0; i < manifest.pageHashes().size(); i++) {
                Path page = directory(manifest).resolve("page-" + i + ".bin");
                if (Files.exists(page, LinkOption.NOFOLLOW_LINKS))
                    LocationTransfer.fragment(manifest, new LocationTransferPage(LocationTransfer.identity(manifest), i,
                            Base64.getEncoder().encodeToString(read(page, LocationTransfer.PAGE_BYTES))));
            }
            if (Files.exists(directory(manifest).resolve("REGISTRY_COMMITTED.json"), LinkOption.NOFOLLOW_LINKS)) verifyReceipt(manifest);
        }
    }
    private void writeCurrent(Map<ResourceId, LocationTransferManifest> values) throws IOException {
        var sources = new TreeMap<String, JsonValue>();
        values.forEach((source, manifest) -> sources.put(source.value(), new JsonValue.Text(LocationTransfer.identity(manifest).value())));
        writeAtomic(root.resolve("CURRENT.json"), CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of(
                "world_id", new JsonValue.Text(world.value().toString()), "sources", new JsonValue.ObjectValue(sources)))));
    }
    private static void noSymlinks(Path file) throws IOException {
        for (Path p = file.toAbsolutePath(); p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("symlinked transfer path");
    }
    private static byte[] read(Path file, int maximum) throws IOException {
        noSymlinks(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > maximum) throw new IOException("transfer file type/size");
        try (var in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("transfer file grew");
            return bytes;
        }
    }
    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
    private static void writeImmutable(Path file, byte[] bytes) throws IOException {
        noSymlinks(file);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            Checks.require(Arrays.equals(bytes, read(file, LocationTransfer.WIRE_BYTES)), "immutable transfer file conflict");
        } else writeAtomic(file, bytes);
    }
    private static void writeAtomic(Path file, byte[] bytes) throws IOException {
        noSymlinks(file); Path temp = Files.createTempFile(file.getParent(), ".transfer-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(file.getParent());
        } finally { Files.deleteIfExists(temp); }
    }
    public CompletionStage<Void> closeAsync() {
        if (!busy.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IllegalStateException("transfer in progress"));
        closed = true;
        return CompletableFuture.runAsync(() -> {
            try { release(); } catch (IOException error) { throw new CompletionException(error); }
        }, writer);
    }
    private void release() throws IOException {
        try { if (lock != null && lock.isValid()) lock.release(); }
        finally { if (lockChannel != null) lockChannel.close(); }
    }
}
