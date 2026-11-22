package fr.proutlost.worldprep;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData.Job;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData.JobState;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData.Operation;
import fr.proutlost.worldprep.job.WorldPrepJob.State;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeJobFunctionalStatePersistenceTest {
    private static Job job(Operation operation, JobState state) {
        return new Job(UUID.randomUUID(), operation, "proutlost:test", state, 17, "", UUID.randomUUID());
    }

    @Test void terminalLegacyMetadataExposesReadyAndRestoredWithoutChangingSchemaOrStoredState() {
        var data = new WorldPrepSavedData();
        var preview = job(Operation.PREVIEW_BIOMES, JobState.COMPLETED);
        var rollback = job(Operation.ROLLBACK_GEOLOGY, JobState.COMPLETED);
        data.jobs().put(preview.id(), preview);
        data.jobs().put(rollback.id(), rollback);
        var tag = data.save(new CompoundTag(), null);
        assertEquals(7, tag.getInt("schema"));
        for (var value : tag.getList("jobs", Tag.TAG_COMPOUND))
            assertEquals("COMPLETED", ((CompoundTag) value).getString("state"));
        var restored = WorldPrepSavedData.load(tag, null);
        assertEquals(preview, restored.jobs().get(preview.id()));
        assertEquals(rollback, restored.jobs().get(rollback.id()));
        assertEquals(State.PREVIEW_READY, restored.jobs().get(preview.id()).functionalState());
        assertEquals(State.ROLLED_BACK, restored.jobs().get(rollback.id()).functionalState());
    }

    @Test void nativeActiveOperationsExposeTheContractPhasesAndColdStillRequiresExplicitResume() {
        var data = new WorldPrepSavedData();
        var analyze = job(Operation.ANALYZE, JobState.RUNNING);
        var preview = job(Operation.PREVIEW_TREES, JobState.RUNNING);
        var apply = job(Operation.APPLY_BIOMES, JobState.RUNNING);
        var rollback = job(Operation.ROLLBACK_BIOMES, JobState.RUNNING);
        assertEquals(State.ANALYZING, analyze.functionalState());
        assertEquals(State.PREVIEWING, preview.functionalState());
        assertEquals(State.APPLYING, apply.functionalState());
        assertEquals(State.ROLLING_BACK, rollback.functionalState());
        for (var job : new Job[]{analyze, preview, apply, rollback}) data.jobs().put(job.id(), job);
        var restored = WorldPrepSavedData.load(data.save(new CompoundTag(), null), null);
        for (var old : data.jobs().values()) {
            var paused = restored.jobs().get(old.id());
            assertEquals(State.PAUSED, paused.functionalState());
            assertEquals(old.cursor(), paused.cursor());
            assertEquals(old.snapshotId(), paused.snapshotId());
            assertEquals(old.functionalState(), paused.withState(JobState.RUNNING).functionalState());
        }
    }

    @Test void terminalEcologyRecheckAndSafetyStatesCannotMasqueradeAsSuccessfulWork() {
        var data = new WorldPrepSavedData();
        var rollback = job(Operation.ROLLBACK_TREES, JobState.COMPLETED);
        data.jobs().put(rollback.id(), rollback);
        var restored = WorldPrepSavedData.load(data.save(new CompoundTag(), null), null);
        var guarded = restored.jobs().get(rollback.id());
        assertEquals(State.PAUSED, guarded.functionalState());
        assertEquals(State.ROLLED_BACK, guarded.withState(JobState.COMPLETED).functionalState());
        assertEquals(State.QUEUED, job(Operation.ANALYZE, JobState.QUEUED).functionalState());
        assertEquals(State.CANCELLED, job(Operation.PREVIEW_BIOMES, JobState.CANCELLED).functionalState());
        assertEquals(State.FAILED, job(Operation.APPLY_BIOMES, JobState.FAILED).functionalState());
        assertEquals(State.COMPLETED, job(Operation.APPLY_BIOMES, JobState.COMPLETED).functionalState());
    }
}
