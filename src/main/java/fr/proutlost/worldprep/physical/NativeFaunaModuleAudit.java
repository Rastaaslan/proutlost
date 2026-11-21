package fr.proutlost.worldprep.physical;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Independent read-only global entity-Anvil audit after the caller flushes saves.
 * No Level, entity manager, pending-write cache or RegionFile writer is used.
 * Every UUID in the directory, including passengers, participates in uniqueness.
 */
public final class NativeFaunaModuleAudit implements AutoCloseable {
    public record Expected(UUID uuid, String species, UUID operation, String group,
                           boolean owned, boolean persistent) {
        public Expected {
            if (uuid == null || species == null || !ID.matcher(species).matches()
                    || (owned && (operation == null || group == null || group.isBlank() || group.length() > 128)))
                throw new IllegalArgumentException("FAUNA_INVALID_EXPECTED_ENTITY");
        }
    }
    public record Observation(UUID uuid, String species, double x, double y, double z,
                              String group, boolean owned, String region, int chunkX,
                              int chunkZ, boolean persistent, float health) {}
    public record Result(String status, List<Observation> observations, int scannedRegions,
                         int scannedChunks, int emptyRegions, String startedAt, String completedAt,
                         int expectedCount, int totalEntities) {
        public Result { observations = List.copyOf(observations); }
    }
    private record Fingerprint(long size, FileTime modified, String fileKey) {}
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern REGION = Pattern.compile("r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca");
    private static final int MAX_REGIONS = 4096, MAX_ENTITIES = 262144;
    private static final long MAX_REGION_BYTES = 256L * 1024 * 1024;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "worldprep-fauna-module-anvil-audit");
        t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t;
    });

    private final Path directory;
    private final UUID batch;
    private final String requestSha;
    private final List<Expected> expected;
    private Future<Result> pending;
    private Result completed;
    private IOException failed;
    private boolean closed;

    public NativeFaunaModuleAudit(Path entitiesDirectory, UUID batch, String requestSha,
                                  List<Expected> expected) {
        if (entitiesDirectory == null || batch == null || requestSha == null
                || !requestSha.matches("[0-9a-f]{64}") || expected == null || expected.size() > 8192)
            throw new IllegalArgumentException("FAUNA_INVALID_AUDIT_REQUEST");
        this.directory = entitiesDirectory.toAbsolutePath().normalize();
        this.batch = batch; this.requestSha = requestSha; this.expected = List.copyOf(expected);
        Set<UUID> ids = new HashSet<>();
        for (Expected e : this.expected)
            if (!ids.add(e.uuid())) throw new IllegalArgumentException("FAUNA_DUPLICATE_EXPECTED_UUID");
    }

    /** Starts or polls one asynchronous bounded disk audit; never blocks a server tick. */
    public boolean step() throws IOException {
        if (completed != null) return true;
        if (failed != null) throw failed;
        if (closed) throw new IOException("FAUNA_AUDIT_CLOSED");
        if (pending == null) { pending = IO.submit(this::scan); return false; }
        if (!pending.isDone()) return false;
        try { completed = pending.get(); return true; }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("FAUNA_AUDIT_INTERRUPTED", e);
        } catch (CancellationException e) {
            throw new IOException("FAUNA_AUDIT_CANCELLED", e);
        } catch (ExecutionException e) {
            failed = e.getCause() instanceof IOException ioe ? ioe
                    : new IOException("FAUNA_AUDIT_READ_FAILED", e.getCause());
            throw failed;
        }
    }

    public Result result() {
        if (completed == null) throw new IllegalStateException("FAUNA_AUDIT_NOT_COMPLETE");
        return completed;
    }

    @Override public void close() {
        closed = true;
        if (pending != null && !pending.isDone()) pending.cancel(true);
    }

    private Result scan() throws IOException {
        String started = Instant.now().toString();
        SortedMap<String, Fingerprint> initial = inventory();
        Map<UUID, Expected> wanted = new LinkedHashMap<>();
        for (Expected e : expected) wanted.put(e.uuid(), e);
        ScanState state = new ScanState(wanted);
        int regions = 0, chunks = 0, empty = 0;
        for (var entry : initial.entrySet()) {
            interrupted();
            Path file = directory.resolve(entry.getKey());
            if (!fingerprint(file).equals(entry.getValue()))
                throw new IOException("FAUNA_REGION_CHANGED_BEFORE_READ: " + file);
            regions++;
            if (entry.getValue().size() == 0) { empty++; continue; }
            if (entry.getValue().size() < 8192 || entry.getValue().size() > MAX_REGION_BYTES)
                throw new IOException("FAUNA_TRUNCATED_OR_OVERSIZED_REGION: " + file);
            var match = REGION.matcher(entry.getKey());
            if (!match.matches()) throw new IOException("FAUNA_BAD_REGION_NAME: " + file);
            int rx, rz;
            try { rx = Integer.parseInt(match.group(1)); rz = Integer.parseInt(match.group(2)); }
            catch (NumberFormatException e) { throw new IOException("FAUNA_REGION_COORDINATE_OVERFLOW", e); }
            byte[] header;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                header = readHeader(channel);
                ByteBuffer locations = ByteBuffer.wrap(header);
                BitSet used = new BitSet(); used.set(0, 2);
                long sectors = (entry.getValue().size() + 4095L) / 4096L;
                for (int slot = 0; slot < 1024; slot++) {
                    interrupted();
                    int location = locations.getInt(slot * 4);
                    if (location == 0) continue;
                    int sector = location >>> 8, count = location & 255;
                    if (sector < 2 || count == 0 || sector + (long)count > sectors
                            || used.nextSetBit(sector) >= 0 && used.nextSetBit(sector) < sector + count)
                        throw new IOException("FAUNA_CORRUPT_OR_OVERLAPPING_SECTOR: " + file + "/" + slot);
                    used.set(sector, sector + count);
                    long lx = (long)rx * 32 + slot % 32, lz = (long)rz * 32 + slot / 32;
                    if (Math.abs(lx) > 1875000 || Math.abs(lz) > 1875000)
                        throw new IOException("FAUNA_CHUNK_OUTSIDE_WORLD_BOUNDS: " + file);
                    int cx = (int)lx, cz = (int)lz;
                    // The shared reader verifies compression flags, payload bounds,
                    // Position, and rereads both the header location and payload.
                    CompoundTag chunk = ReadOnlyChunkNbt.entities(directory, cx, cz);
                    if (!chunk.contains("Entities", Tag.TAG_LIST))
                        throw new IOException("FAUNA_MISSING_ENTITY_LIST: " + file + "/" + slot);
                    ListTag entities = compounds(chunk, "Entities");
                    state.collect(entities, file.toString(), cx, cz, 0);
                    chunks++;
                }
                if (!Arrays.equals(header, readHeader(channel)))
                    throw new IOException("FAUNA_REGION_HEADER_CHANGED: " + file);
            }
            if (!fingerprint(file).equals(entry.getValue()))
                throw new IOException("FAUNA_REGION_CHANGED_DURING_READ: " + file);
        }
        if (!initial.equals(inventory()))
            throw new IOException("FAUNA_ENTITY_DIRECTORY_CHANGED_DURING_AUDIT");
        List<Observation> ordered = new ArrayList<>(expected.size());
        for (Expected e : expected) {
            Observation observed = state.observed.get(e.uuid());
            if (observed == null) throw new IOException("FAUNA_EXPECTED_UUID_ABSENT: " + e.uuid());
            ordered.add(observed);
        }
        return new Result("FAUNA_NATIVE_ANVIL_UUIDS_VERIFIED", ordered, regions, chunks, empty,
                started, Instant.now().toString(), expected.size(), state.total);
    }

    private final class ScanState {
        private final Map<UUID, Expected> wanted;
        private final Set<UUID> all = new HashSet<>();
        private final Map<UUID, Observation> observed = new HashMap<>();
        private int total, chunkEntities;
        private ScanState(Map<UUID, Expected> wanted) { this.wanted = wanted; }

        private void collect(ListTag list, String region, int cx, int cz, int depth) throws IOException {
            if (depth == 0) chunkEntities = 0;
            if (depth > 16 || list.size() > 4096)
                throw new IOException("FAUNA_ENTITY_NESTING_OR_LIST_LIMIT");
            for (int i = 0; i < list.size(); i++) {
                interrupted();
                if (++total > MAX_ENTITIES || ++chunkEntities > 4096)
                    throw new IOException("FAUNA_ENTITY_CENSUS_LIMIT");
                CompoundTag nbt = list.getCompound(i);
                if (!nbt.hasUUID("UUID")) throw new IOException("FAUNA_PERSISTED_UUID_MISSING");
                UUID uuid = nbt.getUUID("UUID");
                if (!all.add(uuid)) throw new IOException("FAUNA_DUPLICATE_PERSISTED_UUID: " + uuid);
                String species = nbt.getString("id");
                if (!ID.matcher(species).matches()) throw new IOException("FAUNA_INVALID_PERSISTED_ID: " + uuid);
                ListTag position = nbt.getList("Pos", Tag.TAG_DOUBLE);
                if (position.size() != 3) throw new IOException("FAUNA_INVALID_PERSISTED_POSITION: " + uuid);
                double x = position.getDouble(0), y = position.getDouble(1), z = position.getDouble(2);
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                        || Math.abs(x) > 30000000 || Math.abs(z) > 30000000 || Math.abs(y) > 4096)
                    throw new IOException("FAUNA_NONFINITE_OR_OUTSIDE_POSITION: " + uuid);
                // Passengers are serialized in their vehicle's chunk; seat offsets
                // may cross a chunk edge without constituting misplaced storage.
                if (depth == 0 && (Math.floor(x / 16) != cx || Math.floor(z / 16) != cz))
                    throw new IOException("FAUNA_ENTITY_CHUNK_POSITION_MISMATCH: " + uuid);
                Expected e = wanted.get(uuid);
                CompoundTag data = nbt.getCompound("NeoForgeData");
                if (batch.toString().equals(data.getString("proutlost_fauna_batch")) && e == null)
                    throw new IOException("FAUNA_UNEXPECTED_BATCH_UUID: " + uuid);
                if (e != null) {
                    if (!species.equals(e.species()))
                        throw new IOException("FAUNA_PERSISTED_SPECIES_MISMATCH: " + uuid);
                    float health = nbt.getFloat("Health");
                    if (!nbt.contains("Health", Tag.TAG_ANY_NUMERIC) || !Float.isFinite(health) || health <= 0)
                        throw new IOException("FAUNA_EXPECTED_ENTITY_NOT_ALIVE: " + uuid);
                    if (e.owned() && (!batch.toString().equals(data.getString("proutlost_fauna_batch"))
                            || !e.operation().toString().equals(data.getString("proutlost_fauna_operation"))
                            || !requestSha.equals(data.getString("proutlost_fauna_request"))
                            || !e.group().equals(data.getString("proutlost_fauna_group"))
                            || e.persistent() && !nbt.getBoolean("PersistenceRequired")))
                        throw new IOException("FAUNA_PERSISTED_OWNERSHIP_OR_POLICY_MISMATCH: " + uuid);
                    observed.put(uuid, new Observation(uuid, species, x, y, z, e.group(), e.owned(),
                            region, cx, cz, nbt.getBoolean("PersistenceRequired"), health));
                }
                if (nbt.contains("Passengers")) collect(compounds(nbt, "Passengers"), region, cx, cz, depth + 1);
            }
        }
    }

    private SortedMap<String, Fingerprint> inventory() throws IOException {
        noLinks(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("FAUNA_ENTITY_DIRECTORY_MISSING");
        SortedMap<String, Fingerprint> result = new TreeMap<>();
        try (var stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                interrupted();
                String name = path.getFileName().toString();
                if (name.endsWith(".mcc"))
                    throw new IOException("FAUNA_EXTERNAL_ENTITY_CHUNKS_UNSUPPORTED: " + name);
                if (!name.endsWith(".mca")) continue;
                if (!REGION.matcher(name).matches()) throw new IOException("FAUNA_BAD_REGION_NAME: " + name);
                if (result.size() >= MAX_REGIONS) throw new IOException("FAUNA_REGION_CENSUS_LIMIT");
                result.put(name, fingerprint(path));
            }
        }
        return result;
    }

    private static Fingerprint fingerprint(Path file) throws IOException {
        noLinks(file);
        BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile()) throw new IOException("FAUNA_NONREGULAR_REGION: " + file);
        return new Fingerprint(a.size(), a.lastModifiedTime(), String.valueOf(a.fileKey()));
    }

    private static ListTag compounds(CompoundTag root, String key) throws IOException {
        if (!root.contains(key, Tag.TAG_LIST)) throw new IOException("FAUNA_INVALID_ENTITY_LIST: " + key);
        Tag raw = root.get(key);
        if (!(raw instanceof ListTag list) || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
            throw new IOException("FAUNA_INVALID_ENTITY_LIST_TYPE: " + key);
        return list;
    }

    private static byte[] readHeader(FileChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        while (buffer.hasRemaining())
            if (channel.read(buffer, buffer.position()) <= 0) throw new EOFException("FAUNA_TRUNCATED_REGION_HEADER");
        return buffer.array();
    }

    private static void noLinks(Path path) throws IOException {
        for (Path p = path.toAbsolutePath(); p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("FAUNA_SYMLINK_INPUT: " + p);
    }

    private static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("FAUNA_AUDIT_INTERRUPTED");
    }
}
