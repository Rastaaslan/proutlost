package fr.proutlost.worldprep.gametest;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.BlockMutationRuntime;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.IOUtilities;
import java.util.UUID;

/** Synchronous fixture driver only. Production always polls without blocking its server thread. */
final class CheckpointFixture {
    static void drain(ServerLevel level) {
        for(int i=0;i<4;i++) {
            level.getDataStorage().save();
            IOUtilities.waitUntilIOWorkerComplete();
            if(WorldPrepRuntime.data(level).checkpointReady() && BlockMutationRuntime.data(level).checkpointReady())return;
        }
        throw new IllegalStateException("Fixture checkpoint did not settle");
    }
    static void tick(ServerLevel level,int budget) {
        drain(level);WorldPrepRuntime.tickLevel(level,budget);drain(level);
    }
    static void process(ServerLevel level,WorldPrepSavedData data,UUID id,WorldPrepSavedData.Job job,int budget) {
        drain(level);BlockMutationRuntime.process(level,data,id,job,budget);drain(level);
    }
    static void batch(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Job parent,int budget) {
        drain(level);fr.proutlost.worldprep.runtime.EcologyBatchRuntime.process(level,data,parent,budget);drain(level);
    }
    private CheckpointFixture() {}
}
