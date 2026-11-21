package fr.proutlost.worldprep.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class Run90ColdDeltaCheckpointTest {
    @TempDir Path temporary;
    static final String STORE="proutlost_worldprep";

    static CompoundTag area(int i){
        var t=new CompoundTag();
        t.putString("id","proutlost:batch_"+i);
        t.putString("dimension","minecraft:overworld");
        t.putInt("minX",i*16);t.putInt("minZ",16);
        t.putInt("maxX",i*16+15);t.putInt("maxZ",31);
        return t;
    }
    static CompoundTag job(int i,long cursor){
        var t=new CompoundTag();
        t.putUUID("id",new UUID(i,1000000L+i));
        t.putString("operation","PREVIEW_TREES");
        t.putString("area","proutlost:batch_"+i);
        t.putString("state","COMPLETED");t.putLong("cursor",cursor);
        t.putString("error","");
        return t;
    }
    static CompoundTag full(int n) {
        var envelope=new CompoundTag();var data=new CompoundTag();
        data.putInt("schema",4);
        var areas=new ListTag();var jobs=new ListTag();
        for(int i=0;i<n;i++){areas.add(area(i));jobs.add(job(i,i));}
        data.put("areas",areas);data.put("jobs",jobs);
        data.put("plans",new ListTag());data.put("snapshots",new ListTag());
        data.put("selections",new ListTag());data.put("protections",new ListTag());
        data.put("overrides",new ListTag());envelope.put("data",data);
        envelope.putInt("DataVersion",3955);
        return envelope;
    }
    Path baseline(CompoundTag initial) throws Exception{
        var target=temporary.resolve("data/"+STORE+".dat");
        Files.createDirectories(target.getParent());
        var checkpoint=ImmutableBucketedNbtCheckpoint.externalize(initial,target,STORE);
        NbtIo.writeCompressed(checkpoint.compactRoot(),target);
        assertEquals(initial,ImmutableBucketedNbtCheckpoint.expand(checkpoint.compactRoot(),target,STORE));
        return target;
    }
    static CompoundTag delta(CompoundTag changed,ListTag areas,ListTag jobs,int countAreas,int countJobs){
        var wrapper=new CompoundTag();var data=new CompoundTag();
        var source=changed.getCompound("data");
        data.putInt("schema",4);
        data.put("selections",source.getList("selections",Tag.TAG_COMPOUND).copy());
        data.put("protections",source.getList("protections",Tag.TAG_COMPOUND).copy());
        data.put("overrides",source.getList("overrides",Tag.TAG_COMPOUND).copy());
        var marker=new CompoundTag();
        marker.putInt("version",1);marker.putString("store",STORE);
        marker.put("areas",areas);marker.put("jobs",jobs);
        marker.putInt("areasCount",countAreas);marker.putInt("jobsCount",countJobs);
        data.put("__run90_incremental_v1",marker);wrapper.put("data",data);
        return wrapper;
    }
    @Test void editedRecordsAndAppendAreExactlyPreservedWithoutTouchingOldRoot() throws Exception {
        var old=full(500);var target=baseline(old);
        var updated=old.copy();var list=updated.getCompound("data").getList("jobs",Tag.TAG_COMPOUND);
        var first=list.getCompound(12).copy();first.putLong("cursor",900001L);list.set(12,first);
        var last=list.getCompound(485).copy();last.putLong("cursor",900002L);list.set(485,last);
        list.add(job(500,888L));
        updated.getCompound("data").getList("areas",Tag.TAG_COMPOUND).add(area(500));
        var jobs=new ListTag();jobs.add(first);jobs.add(last);jobs.add(job(500,888L));
        var areas=new ListTag();areas.add(area(500));
        var snapshot=ImmutableBucketedNbtCheckpoint.externalizeRun90Delta(
                delta(updated,areas,jobs,501,501),target,STORE);
        assertTrue(snapshot.metrics().newPages()>=1);
        assertTrue(snapshot.metrics().newPages()<=4);
        assertEquals(old,ImmutableBucketedNbtCheckpoint.expand(
                NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap()),target,STORE));
        assertEquals(updated,ImmutableBucketedNbtCheckpoint.expand(
                snapshot.compactRoot(),target,STORE));
        // Exactly the format expected by cold Minecraft load; no new schema.
        var next=Files.createTempFile(target.getParent(),"run90-root-",".partial");
        NbtIo.writeCompressed(snapshot.compactRoot(),next);
        Files.move(next,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        assertEquals(updated,ImmutableBucketedNbtCheckpoint.expand(
                NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap()),target,STORE));
    }
    @Test void unexpectedTotalCountRefusesWithoutPublishingRoot() throws Exception {
        var old=full(24);var target=baseline(old);
        var updated=old.copy();
        var jobs=new ListTag();jobs.add(job(24,24));
        var root=delta(updated,new ListTag(),jobs,24,24);
        String before=ImmutableBucketedNbtCheckpoint.fileSha256(target);
        assertThrows(IOException.class,()->ImmutableBucketedNbtCheckpoint.externalizeRun90Delta(root,target,STORE));
        assertEquals(before,ImmutableBucketedNbtCheckpoint.fileSha256(target));
    }
    @Test void duplicateDirtyKeyAndForeignStoreRefuse() throws Exception {
        var old=full(25);var target=baseline(old);
        var jobs=new ListTag();jobs.add(job(1,501));jobs.add(job(1,501));
        var root=delta(old,new ListTag(),jobs,25,25);
        assertThrows(IOException.class,()->ImmutableBucketedNbtCheckpoint.externalizeRun90Delta(root,target,STORE));
        root.getCompound("data").getCompound("__run90_incremental_v1").putString("store","external");
        assertThrows(IOException.class,()->ImmutableBucketedNbtCheckpoint.externalizeRun90Delta(root,target,STORE));
    }
    @Test void missingOrCorruptPriorPageFailsClosed() throws Exception {
        var old=full(12);var target=baseline(old);
        var root=NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap());
        var sections=root.getCompound("data").getCompound("__worldprep_bucket_index_v1").getList("sections",Tag.TAG_COMPOUND);
        var section=(CompoundTag)sections.stream().filter(t->((CompoundTag)t).getString("name").equals("jobs")).findFirst().orElseThrow();
        var reference=section.getList("buckets",Tag.TAG_COMPOUND).getCompound(0);
        Path file=target.getParent().resolve("worldprep-paged-v1").resolve(STORE)
                .resolve("jobs").resolve(reference.getString("sha256")+".nbt.gz");
        Files.writeString(file,"CORRUPT");
        var patch=new ListTag();patch.add(job(0,200));
        assertThrows(IOException.class,()->ImmutableBucketedNbtCheckpoint.externalizeRun90Delta(
                delta(old,new ListTag(),patch,12,12),target,STORE));
    }
    @Test void coldLoadedRealSavedDataCapturesOnlyChangesIncludingRestoredState() throws Exception {
        var old=full(160);var target=baseline(old);
        // Change the old persisted state to RUNNING: native load MUST normalize it
        // to PAUSED and delta MUST preserve that normalization on next save.
        var saved=old.copy();
        var active=saved.getCompound("data").getList("jobs",Tag.TAG_COMPOUND).getCompound(10);
        active.putString("state","RUNNING");
        var pre=ImmutableBucketedNbtCheckpoint.externalize(saved,target,STORE);
        NbtIo.writeCompressed(pre.compactRoot(),target);
        Path world=target.getParent().getParent();
        Files.writeString(world.resolve("level.dat"),"isolated-test-world");
        Files.writeString(world.resolve("worldprep-run21-paged-authorization.json"),
                "{\"schemaVersion\":1,\"mode\":\"CONTRA_RUN21_DEV_ONLY\",\"world\":\""+
                world.toRealPath()+"\",\"protectedOriginalsUnchanged\":true}");
        var priorMode=System.getProperty(PagedSavedDataRuntime.MODE_KEY);
        var priorWorld=System.getProperty(PagedSavedDataRuntime.WORLD_KEY);
        var priorOpt=System.getProperty("proutlost.run90.strictIncrementalMetadata");
        try{
            System.setProperty(PagedSavedDataRuntime.MODE_KEY,PagedSavedDataRuntime.DEV_MODE);
            System.setProperty(PagedSavedDataRuntime.WORLD_KEY,world.toRealPath().toString());
            System.setProperty("proutlost.run90.strictIncrementalMetadata","true");
            var data=PagedSavedDataRuntime.withStore(world,STORE,()->
                    WorldPrepSavedData.load(pre.compactRoot().getCompound("data"),null));
            var first=data.save(new CompoundTag(),null);
            var delta=first.getCompound("__run90_incremental_v1");
            assertEquals(1,delta.getList("jobs",Tag.TAG_COMPOUND).size(),
                    "Only persisted RUNNING->PAUSED state should change during native load");
            var prepared=PagedSavedDataRuntime.prepareWrite(
                    new CompoundTag(){{put("data",first);putInt("DataVersion",3955);}},target);
            var expected=saved.copy();
            // A current SavedData capture preserves the complete schema7 metadata,
            // including when its prior cold root used the supported schema4 form.
            expected.getCompound("data").putInt("schema",WorldPrepSavedData.SCHEMA);
            expected.getCompound("data").putInt("locationRegistrySchema",1);
            expected.getCompound("data").put("locations",fr.proutlost.worldprep.location.LocationRegistryNbt.save(
                    new fr.proutlost.worldprep.location.LocationRegistry()));
            expected.getCompound("data").put("analyses",new ListTag());
            expected.getCompound("data").getList("jobs",Tag.TAG_COMPOUND).getCompound(10).putString("state","PAUSED");
            assertEquals(expected,ImmutableBucketedNbtCheckpoint.expand(prepared,target,STORE));
            NbtIo.writeCompressed(prepared,target);
            var jobId=new UUID(10,1000010L);
            var current=data.jobs().get(jobId);
            data.jobs().put(jobId,current.withCursor(999_999));
            var nextDelta=data.save(new CompoundTag(),null);
            assertEquals(1,nextDelta.getCompound("__run90_incremental_v1")
                    .getList("jobs",Tag.TAG_COMPOUND).size());
            var next=PagedSavedDataRuntime.prepareWrite(
                    new CompoundTag(){{put("data",nextDelta);putInt("DataVersion",3955);}},target);
            expected.getCompound("data").getList("jobs",Tag.TAG_COMPOUND).getCompound(10).putLong("cursor",999_999);
            assertEquals(expected,ImmutableBucketedNbtCheckpoint.expand(next,target,STORE));
        }finally{
            restore(PagedSavedDataRuntime.MODE_KEY,priorMode);
            restore(PagedSavedDataRuntime.WORLD_KEY,priorWorld);
            restore("proutlost.run90.strictIncrementalMetadata",priorOpt);
        }
    }
    static void restore(String key,String value){
        if(value==null)System.clearProperty(key);else System.setProperty(key,value);
    }
}
