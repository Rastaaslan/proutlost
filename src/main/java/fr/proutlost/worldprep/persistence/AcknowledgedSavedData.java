package fr.proutlost.worldprep.persistence;

import fr.proutlost.worldprep.storage.CheckpointBackpressure;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.IOUtilities;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** One native/autosave checkpoint in flight per store; errors remain latched. */
public abstract class AcknowledgedSavedData extends SavedData {
    private final CheckpointBackpressure checkpoint = new CheckpointBackpressure(IOUtilities::withIOWorker);

    /** Observations only: never used for admission, durability or dirty state. */
    private volatile long captureWallNanos,captureCpuNanos,persistWallNanos,persistCpuNanos,writtenCheckpoints;
    public record CheckpointMetrics(long captureWallNanos,long captureCpuNanos,
                                    long persistWallNanos,long persistCpuNanos,long writtenCheckpoints) {}
    public final CheckpointMetrics readOnlyMetrics() {
        return new CheckpointMetrics(captureWallNanos,captureCpuNanos,persistWallNanos,persistCpuNanos,writtenCheckpoints);
    }
    private static long cpuTime() {
        var bean=java.lang.management.ManagementFactory.getThreadMXBean();
        return bean.isCurrentThreadCpuTimeSupported()?bean.getCurrentThreadCpuTime():0;
    }
    private void measuredPersist(CompoundTag root,Path file)throws IOException {
        long start=System.nanoTime(),cpu=cpuTime();
        try { persist(root,file);writtenCheckpoints++; }
        finally {persistWallNanos+=System.nanoTime()-start;persistCpuNanos+=cpuTime()-cpu;}
    }

    private File lastFile;
    private HolderLookup.Provider lastRegistries;

    /** DEV-only observation: no acknowledge, no dirty change, no disk writes.
     * Invoked only on the SavedData owner (Minecraft server) thread.
     */
    public final String readOnlyCheckpointDiagnostic() {
        var s=checkpoint.status();
        return "state="+s.state()+" dirty="+isDirty()+
            " id="+s.id()+" destination="+(lastFile==null?"NONE":lastFile.getName())+
            " failure="+(s.failure()==null?"NONE":s.failure().getClass().getName());
    }
    public final boolean checkpointReady() {
        var status = checkpoint.status();
        if (status.state() == CheckpointBackpressure.State.FAILED)
            throw new IllegalStateException("WorldPrep checkpoint write failed; evidence retained", status.failure());
        if (status.state() == CheckpointBackpressure.State.WRITTEN) checkpoint.acknowledge(status.id());
        return checkpoint.status().state() == CheckpointBackpressure.State.IDLE && !isDirty();
    }

    @Override public final void save(File file, HolderLookup.Provider registries) {
        lastFile=file;lastRegistries=registries;
        checkpointReady(); // consume success or propagate failure, never silently continue
        if (!isDirty()) return;
        checkpoint.submit(UUID.randomUUID(), () -> {
            // Admission precedes the potentially large NBT serialization on the server thread.
            // Both WorldPrep serializers create independent tags; no live state enters the worker.
            long start=System.nanoTime(),cpu=cpuTime();
            try {
                var root = new CompoundTag();
                root.put("data", save(new CompoundTag(), registries));
                NbtUtils.addCurrentDataVersion(root);
                setDirty(false);
                return () -> measuredPersist(root, file.toPath());
            } finally {
                captureWallNanos+=System.nanoTime()-start;
                captureCpuNanos+=cpuTime()-cpu;
            }
        });
        checkpointReady(); // also surface capture / executor rejection immediately
    }

    /** Called by LevelEvent.Save after native SavedData.save, before native flush returns.
     * No runtime mutations can interleave on this owner thread. Two waits suffice:
     * the outstanding capture, then the later dirty state omitted by admission.
     */
    public final void flushNativeSave() {
        checkpoint.awaitWrite(120_000);
        if (checkpointReady()) return;
        if (lastFile == null) throw new IllegalStateException("Dirty store has no native save destination");
        save(lastFile,lastRegistries);
        checkpoint.awaitWrite(120_000);
        if (!checkpointReady()) throw new IllegalStateException("Dirty state remained after final checkpoint");
    }

    protected void persist(CompoundTag captured, Path target) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        // Only an explicit exact-world DEV opt-in may externalize immutable
        // SHA-pinned pages; legacy/production output remains bitwise compatible.
        // A failed page write leaves the prior native target .dat authoritative.
        CompoundTag writeRoot = PagedSavedDataRuntime.prepareWrite(captured,target);
        persistenceBoundary("AFTER_PAGES_DURABLE",target);
        Path staged = Files.createTempFile(directory, ".worldprep-checkpoint-", ".partial");
        // Keep failed/partial evidence. No fallback to non-atomic replacement.
        // Maintain vanilla GZIP/NBT wire format and the exact force/atomic rename guards.
        // Faster DEFLATE does not change integrity, ownership or acknowledgment semantics.
        try (var raw = Files.newOutputStream(staged, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             var buffered = new java.io.BufferedOutputStream(raw, 256 * 1024);
             var gzip = new java.util.zip.GZIPOutputStream(buffered, 256 * 1024) {{
                 def.setLevel(1);
             }};
             var output = new java.io.DataOutputStream(gzip)) {
            NbtIo.write(writeRoot, output);
        }
        try (var channel = FileChannel.open(staged, StandardOpenOption.WRITE)) { channel.force(true); }
        persistenceBoundary("BEFORE_ROOT_PUBLISH",target);
        Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        persistenceBoundary("AFTER_ROOT_DURABLE",target);
    }

    /** No-op in native stores; test subclasses can crash at real durability boundaries. */
    protected void persistenceBoundary(String phase,Path target) throws IOException {}
}
