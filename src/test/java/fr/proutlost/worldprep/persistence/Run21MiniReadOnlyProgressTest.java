package fr.proutlost.worldprep.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** ONE-TIME forensic READ ONLY on a cloned RUN21 DEV world, while native
 * Minecraft owns the world. Never writes, locks or recovers anything. */
class Run21MiniReadOnlyProgressTest {
    static final Path W=Path.of("/srv/storage/proutlost-s2-candidates/contra-20261027/"
        +"contra-run21-inner-paged-ecology-game-20261028/Contra-inner-paged-ecology-run21");
    static final UUID BATCH=UUID.fromString("fbe62ce1-4858-447d-ad63-00988c361d67");
    @Test void inspectOnlyNativePagedChildAndParentAtCheckpoint()throws Exception{
        var world=W.resolve("data/proutlost_worldprep.dat");
        var blocks=W.resolve("data/proutlost_worldprep_blocks.dat");
        assertTrue(Files.isRegularFile(world)&&Files.isRegularFile(blocks));
        var full=ImmutableBucketedNbtCheckpoint.expand(
            NbtIo.readCompressed(world,NbtAccounter.unlimitedHeap()),world,WorldPrepSavedData.FILE_ID);
        var b=ImmutableBucketedNbtCheckpoint.expand(
            NbtIo.readCompressed(blocks,NbtAccounter.unlimitedHeap()),blocks,BlockWorldPrepData.FILE_ID);
        var model=WorldPrepSavedData.load(full.getCompound("data"),null);
        var blockModel=BlockWorldPrepData.load(b.getCompound("data"),null);
        assertTrue(blockModel.compatible());
        System.out.println("RUN21_DEV_ONLY_NBT_READONLY_JOBS "+model.jobs().size()+
            " PLANS "+blockModel.plans().size()+" JOURNALS "+blockModel.journals().size());
        for(var j:model.jobs().values()){
            System.out.println("RUN21_DEV_ONLY_NATIVE_SAVED_JOB id="+j.id()+
                " operation="+j.operation()+" area="+j.area()+
                " stateOnColdLoad="+j.state()+" cursor="+j.cursor()+
                " snapshot="+j.snapshotId()+" error="+j.error());
        }
        for(var j:blockModel.journals().values()){
            System.out.println("RUN21_DEV_ONLY_NATIVE_SAVED_JOURNAL id="+j.id+
                " plan="+j.planId()+" pageCount="+j.pageCount()+
                " entryCount="+j.entryCount()+" journalRoot="+j.journalRoot());
        }
        assertTrue(model.jobs().containsKey(BATCH));
        var parent=model.jobs().get(BATCH);
        assertEquals(WorldPrepSavedData.Operation.BATCH_ECOLOGY,parent.operation());
        assertTrue(parent.cursor()>=2 && parent.cursor()<=10);
    }
}
