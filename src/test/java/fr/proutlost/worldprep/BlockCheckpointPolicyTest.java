package fr.proutlost.worldprep;

import fr.proutlost.worldprep.persistence.BlockWorldPrepData;
import fr.proutlost.worldprep.runtime.BlockCheckpointPolicy;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockCheckpointPolicyTest {
    @Test void thousandsOfChangesUseBatchProportionalCheckpoints() {
        int changes = 5_000;
        long checkpoints = BlockCheckpointPolicy.maximumApplyCheckpoints(changes);
        assertTrue(checkpoints < changes / 20L);
        assertEquals(2L * ((changes + BlockCheckpointPolicy.BATCH_SIZE - 1L) / BlockCheckpointPolicy.BATCH_SIZE), checkpoints);
    }

    @Test void batchLengthHonorsCheckpointAndTickBounds() {
        assertEquals(BlockCheckpointPolicy.BATCH_SIZE, BlockCheckpointPolicy.batchLength(0, 5_000, 10_000));
        assertEquals(7, BlockCheckpointPolicy.batchLength(0, 5_000, 7));
        assertEquals(3, BlockCheckpointPolicy.batchLength(4_997, 5_000, 10_000));
    }

    @Test void planFailsClosedAtExplicitMemoryBound() {
        BlockWorldPrepData.requirePlanSize(BlockWorldPrepData.MAX_PLAN_CHANGES);
        assertThrows(IllegalStateException.class, () -> BlockWorldPrepData.requirePlanSize(BlockWorldPrepData.MAX_PLAN_CHANGES + 1));
    }

    @Test void legacyBlockPersistenceFailsClosed() {
        var legacy = BlockWorldPrepData.load(new CompoundTag(), null);
        assertFalse(legacy.compatible());
        var current = new BlockWorldPrepData();
        assertEquals(BlockWorldPrepData.FORMAT_VERSION, current.save(new CompoundTag(), null).getInt("format"));
    }
}
