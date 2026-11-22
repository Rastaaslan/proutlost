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
    /** Fixture setup only: capture actual terrain for a newly authored candidate, never a persisted old preview. */
    static fr.proutlost.worldprep.storage.TerrainInputTrace.Binding terrainBinding(ServerLevel level,
            WorldPrepSavedData.Area area,fr.proutlost.worldprep.persistence.BlockWorldPrepData.Plan plan){
        var bounds=new fr.proutlost.worldprep.storage.TerrainInputFingerprint.Bounds(area.minX(),level.getMinBuildHeight(),
            area.minZ(),area.maxX(),level.getMaxBuildHeight(),area.maxZ());
        var cursor=new fr.proutlost.worldprep.storage.TerrainInputFingerprint.Cursor(bounds);
        while(!cursor.complete())cursor.advance(256,(x,y,z)->BlockMutationRuntime.stateString(
            fr.proutlost.worldprep.world.ServerExistingChunkAccess.requireBlock(level,area,x,y,z)
                .getBlockState(new net.minecraft.core.BlockPos(x,y,z))));
        return new fr.proutlost.worldprep.storage.TerrainInputTrace.Binding(
            fr.proutlost.worldprep.plan.PlanFingerprint.of(plan.input).value(),bounds,cursor.fingerprint());
    }
    /** Complete real read-only admission before a test measures its unchanged physical slice window. */
    static void admit(ServerLevel level,UUID id,int budget){
        var data=WorldPrepRuntime.data(level);var area=WorldPrepRuntime.requireArea(data,data.jobs().get(id).area());
        long total=Math.multiplyExact(Math.multiplyExact((long)area.maxX()-area.minX()+1,
            (long)area.maxZ()-area.minZ()+1),(long)level.getMaxBuildHeight()-level.getMinBuildHeight());
        long slices=(total+budget-1)/budget;
        for(long n=0;n<=slices+2;n++){
            drain(level);var job=data.jobs().get(id);
            if(job.snapshotId()!=null)return;
            if(job.state()==WorldPrepSavedData.JobState.FAILED)
                throw new IllegalStateException("Fixture terrain admission failed: "+job.error());
            WorldPrepRuntime.tickLevel(level,budget);drain(level);
        }
        throw new IllegalStateException("Fixture terrain admission exceeded its exact cell census bound");
    }
    private CheckpointFixture() {}
}
