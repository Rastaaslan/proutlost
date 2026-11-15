package fr.proutlost.worldprep.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** No production world access and no benchmark: isolated cold-NBT correctness. */
class Run111ColdReopenContractTest {
    @TempDir Path temporary;
    private static final String STORE="proutlost_worldprep";
    private static final UUID BATCH=Run111TrackedMetadataContractTest.FIRST;

    @Test void sparseTrackedUpdateKeepsOldRootUntilAtomicPublishThenColdReopensExactly()
            throws Exception {
        var world=temporary.resolve("cold-fixture");
        var parent=world.resolve("data/"+STORE+".dat");
        Files.createDirectories(parent.getParent());
        var canonical=new CompoundTag();
        canonical.putInt("DataVersion",3955);
        canonical.put("data",Run111TrackedMetadataContractTest.baseline("PAUSED"));
        var initial=ImmutableBucketedNbtCheckpoint.externalize(canonical,parent,STORE);
        NbtIo.writeCompressed(initial.compactRoot(),parent);
        assertEquals(canonical,ImmutableBucketedNbtCheckpoint.expand(
                NbtIo.readCompressed(parent,NbtAccounter.unlimitedHeap()),parent,STORE));
        Files.writeString(world.resolve("level.dat"),"COLD_FIXTURE_NOT_A_GAME_WORLD");
        Files.writeString(world.resolve("worldprep-run21-paged-authorization.json"),
                "{\"schemaVersion\":1,\"mode\":\"CONTRA_RUN21_DEV_ONLY\",\"world\":\""+
                world.toRealPath()+"\",\"protectedOriginalsUnchanged\":true}");

        String mode=System.getProperty(PagedSavedDataRuntime.MODE_KEY);
        String path=System.getProperty(PagedSavedDataRuntime.WORLD_KEY);
        String strict=System.getProperty("proutlost.run90.strictIncrementalMetadata");
        String tracked=System.getProperty(TrackedRecordMap.FLAG);
        try {
            System.setProperty(PagedSavedDataRuntime.MODE_KEY,PagedSavedDataRuntime.DEV_MODE);
            System.setProperty(PagedSavedDataRuntime.WORLD_KEY,world.toRealPath().toString());
            System.setProperty("proutlost.run90.strictIncrementalMetadata","true");
            System.setProperty(TrackedRecordMap.FLAG,"true");
            var data=PagedSavedDataRuntime.withStore(world,STORE,
                    ()->WorldPrepSavedData.load(initial.compactRoot().getCompound("data"),null));
            var current=data.jobs().get(BATCH);
            data.jobs().put(BATCH,current.withCursor(8L));
            var delta=data.save(new CompoundTag(),null);
            assertEquals(1,Run111TrackedMetadataContractTest.patches(delta,"jobs").size());
            var captured=new CompoundTag();captured.put("data",delta);captured.putInt("DataVersion",3955);
            var candidate=PagedSavedDataRuntime.prepareWrite(captured,parent);

            var expected=canonical.copy();
            expected.getCompound("data").getList("jobs",Tag.TAG_COMPOUND)
                    .getCompound(0).putLong("cursor",8L);
            assertEquals(expected,ImmutableBucketedNbtCheckpoint.expand(candidate,parent,STORE));
            assertEquals(canonical,ImmutableBucketedNbtCheckpoint.expand(
                    NbtIo.readCompressed(parent,NbtAccounter.unlimitedHeap()),parent,STORE),
                    "Old cold root remains authoritative until committed");

            var staged=Files.createTempFile(parent.getParent(),"run111-cold-",".partial");
            NbtIo.writeCompressed(candidate,staged);
            Files.move(staged,parent,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            var cold=NbtIo.readCompressed(parent,NbtAccounter.unlimitedHeap());
            assertEquals(expected,ImmutableBucketedNbtCheckpoint.expand(cold,parent,STORE));
            var reopened=PagedSavedDataRuntime.withStore(world,STORE,
                    ()->WorldPrepSavedData.load(cold.getCompound("data"),null));
            assertEquals(8L,reopened.jobs().get(BATCH).cursor());
            assertEquals(0,Run111TrackedMetadataContractTest.patches(
                    reopened.save(new CompoundTag(),null),"jobs").size());

            // It must be impossible to switch to tracked mode after a Map
            // instance was created with legacy mutable views in this JVM.
            System.setProperty(TrackedRecordMap.FLAG,"false");
            var stale=PagedSavedDataRuntime.withStore(world,STORE,
                    ()->WorldPrepSavedData.load(cold.getCompound("data"),null));
            System.setProperty(TrackedRecordMap.FLAG,"true");
            assertThrows(IllegalStateException.class,
                    ()->stale.save(new CompoundTag(),null));
        } finally {
            restore(PagedSavedDataRuntime.MODE_KEY,mode);
            restore(PagedSavedDataRuntime.WORLD_KEY,path);
            restore("proutlost.run90.strictIncrementalMetadata",strict);
            restore(TrackedRecordMap.FLAG,tracked);
        }
    }
    private static void restore(String key,String original) {
        if(original==null)System.clearProperty(key);
        else System.setProperty(key,original);
    }
}
