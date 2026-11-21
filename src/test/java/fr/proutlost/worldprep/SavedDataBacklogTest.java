package fr.proutlost.worldprep;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded reproduction of native save backlog, not an OOM or terrain durability test. */
class SavedDataBacklogTest {
    @TempDir Path directory;

    @Test void nativeSaveQueuesFullHistoricalSnapshotsWithoutWaitingForDisk() throws Exception {
        SharedConstants.tryDetectVersion();
        var data = new WorldPrepSavedData();
        for (int i = 0; i < 8832; i++) {
            var id = new UUID(0, i);
            data.jobs().put(id, new WorldPrepSavedData.Job(id,
                    WorldPrepSavedData.Operation.APPLY_BIOMES, "proutlost:history",
                    WorldPrepSavedData.JobState.COMPLETED, 0, "", null));
        }
        // Deliberately exercise the old native implementation, not the bounded override.
        var nativeData = new net.minecraft.world.level.saveddata.SavedData() {
            @Override public net.minecraft.nbt.CompoundTag save(net.minecraft.nbt.CompoundTag root,
                    net.minecraft.core.HolderLookup.Provider registries) { return data.save(root, registries); }
        };
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var timedOut = new java.util.concurrent.atomic.AtomicBoolean();
        IOUtilities.withIOWorker(() -> {
            entered.countDown();
            try { if (!release.await(30, TimeUnit.SECONDS)) timedOut.set(true); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); timedOut.set(true); }
        });
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            for (int checkpoint = 0; checkpoint < 8; checkpoint++) {
                var id = new UUID(0, 0);
                data.jobs().put(id, data.jobs().get(id).withCursor(checkpoint));
                nativeData.setDirty();
                // Real pinned SavedData.save(File, Provider), including its NBT deep copy.
                nativeData.save(directory.resolve("checkpoint-" + checkpoint + ".dat").toFile(), null);
                assertFalse(nativeData.isDirty(), "dirty cleared before any disk write");
            }
            try (var files = Files.list(directory)) {
                assertEquals(0, files.count(), "worker must still be blocked");
            }
            assertFalse(timedOut.get());
        } finally {
            release.countDown();
            IOUtilities.waitUntilIOWorkerComplete();
        }
        long compressedBytes = 0;
        for (int checkpoint = 0; checkpoint < 8; checkpoint++) {
            var file = directory.resolve("checkpoint-" + checkpoint + ".dat");
            var root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data");
            var jobs = root.getList("jobs", 10);
            assertEquals(8832, jobs.size());
            assertEquals(checkpoint, jobs.getCompound(0).getLong("cursor"), "queued snapshots must be independent");
            assertEquals(8832, WorldPrepSavedData.load(root, null).jobs().size());
            compressedBytes += Files.size(file);
        }
        System.out.println("CB200 native backlog: 8 outstanding snapshots, 70656 serialized job records; compressed bytes=" + compressedBytes);
    }
}
