package fr.proutlost.worldprep.persistence;

import com.google.gson.JsonParser;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.*;
import java.util.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure standalone JUnit using RUN55's certified historical cold A as immutable
 * READ-ONLY input. No live world access and no Minecraft server.
 * Copies ONLY files referenced by its paged parent NBT root onto HDD.
 */
class Run90ActualContraColdParentTest {
    static final String STORE="proutlost_worldprep";
    static final String MODE="CONTRA_RUN21_DEV_ONLY";
    static final UUID BATCH=UUID.fromString("873e51c5-b528-4fb7-bc34-7e0221d6c59d");
    static final Path ROOT=Path.of("/srv/storage/proutlost-s2-candidates/contra-20261027");
    static final Path DEV=ROOT.resolve("contra-run90-cold-only-scaling-fix-dev-20261029");
    static final Path SOURCE=ROOT.resolve("Contra-Run46-cold-A-RUN55");
    @Test void authentic95kColdParentSparseCheckpointAndRealFullReopen() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN90_REAL_COLD_EXACT")));
        var sourceFile=SOURCE.resolve("data/"+STORE+".dat");
        assertTrue(Files.isRegularFile(sourceFile));
        assertFalse(Files.isSymbolicLink(sourceFile));
        String sourceHash=ImmutableBucketedNbtCheckpoint.fileSha256(sourceFile);
        var original=NbtIo.readCompressed(sourceFile,NbtAccounter.unlimitedHeap());
        assertTrue(ImmutableBucketedNbtCheckpoint.isPaged(original));
        // All the source's referenced pages are SHA-authenticated before copying.
        var fullSource=ImmutableBucketedNbtCheckpoint.expand(original,sourceFile,STORE);
        var destWorld=DEV.resolve("run90-actual-cold-a-only-parent-fixture-v2");
        assertFalse(Files.exists(destWorld),"Refuse accidental duplicate or overwrite of fixture");
        var target=destWorld.resolve("data/"+STORE+".dat");
        Files.createDirectories(target.getParent());
        var sourcePages=sourceFile.getParent().resolve("worldprep-paged-v1/"+STORE);
        var destPages=target.getParent().resolve("worldprep-paged-v1/"+STORE);
        var sections=original.getCompound("data")
                .getCompound("__worldprep_bucket_index_v1")
                .getList("sections",Tag.TAG_COMPOUND);
        int count=0;
        for(var raw:sections){
            var section=(CompoundTag)raw;
            String name=section.getString("name");
            for(var refRaw:section.getList("buckets",Tag.TAG_COMPOUND)){
                var ref=(CompoundTag)refRaw;
                String digest=ref.getString("sha256");
                assertTrue(digest.matches("[0-9a-f]{64}"));
                Path in=sourcePages.resolve(name).resolve(digest+".nbt.gz");
                Path out=destPages.resolve(name).resolve(digest+".nbt.gz");
                assertTrue(Files.isRegularFile(in)&&!Files.isSymbolicLink(in));
                Files.createDirectories(out.getParent());
                Files.copy(in,out,StandardCopyOption.COPY_ATTRIBUTES);
                count++;
            }
        }
        assertTrue(count>1000);
        Files.copy(sourceFile,target,StandardCopyOption.COPY_ATTRIBUTES);
        assertEquals(fullSource,ImmutableBucketedNbtCheckpoint.expand(
                NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap()),target,STORE),
                "HDD-reconstructed cold source must preserve every real record");
        Files.writeString(destWorld.resolve("level.dat"),"ONLY_METADATA_DEV_FIXTURE");
        Files.writeString(destWorld.resolve("worldprep-run21-paged-authorization.json"),
            "{\"schemaVersion\":1,\"mode\":\""+MODE+"\",\"world\":\""+
            destWorld.toRealPath()+"\",\"protectedOriginalsUnchanged\":true}");
        var priorMode=System.getProperty(PagedSavedDataRuntime.MODE_KEY);
        var priorWorld=System.getProperty(PagedSavedDataRuntime.WORLD_KEY);
        var priorIncrement=System.getProperty("proutlost.run90.strictIncrementalMetadata");
        try{
            System.setProperty(PagedSavedDataRuntime.MODE_KEY,MODE);
            System.setProperty(PagedSavedDataRuntime.WORLD_KEY,destWorld.toRealPath().toString());
            var parent=PagedSavedDataRuntime.withStore(destWorld,STORE,
                ()->WorldPrepSavedData.load(original.getCompound("data"),null));
            assertTrue(parent.jobs().size()>50000);
            assertTrue(parent.areas().size()>5000);
            assertTrue(parent.plans().isEmpty()&&parent.snapshots().isEmpty(),
                    "RUN90 sparse path must refuse unexpected earlier biome data");
            var job=parent.jobs().get(BATCH);
            assertNotNull(job);
            assertTrue(job.cursor()>=90000&&job.cursor()<645160);
            parent.jobs().put(BATCH,job.withCursor(job.cursor()+1)
                    .withState(WorldPrepSavedData.JobState.RUNNING));
            System.setProperty("proutlost.run90.strictIncrementalMetadata","false");
            long baselineCaptureStarted=System.nanoTime();
            var canonicalData=parent.save(new CompoundTag(),null);
            long baselineCaptureEnded=System.nanoTime();
            var canonical=new CompoundTag();
            canonical.put("data",canonicalData);canonical.putInt("DataVersion",original.getInt("DataVersion"));
            System.setProperty("proutlost.run90.strictIncrementalMetadata","true");
            long sparseCaptureStarted=System.nanoTime();
            var captured=parent.save(new CompoundTag(),null);
            long sparseCaptureEnded=System.nanoTime();
            var changed=captured.getCompound("__run90_incremental_v1")
                    .getList("jobs",Tag.TAG_COMPOUND);
            assertTrue(changed.size()>=1 && changed.size()<=3,
                    "Only cold-load normalized and one parent cursor must be touched");
            var wrapped=new CompoundTag();
            wrapped.put("data",captured);
            wrapped.putInt("DataVersion",original.getInt("DataVersion"));
            long sparseWriteStarted=System.nanoTime();
            var optimized=PagedSavedDataRuntime.prepareWrite(wrapped,target);
            long sparseWriteEnded=System.nanoTime();
            long baselineWriteStarted=System.nanoTime();
            var baselineWritten=ImmutableBucketedNbtCheckpoint.externalize(canonical,target,STORE).compactRoot();
            long baselineWriteEnded=System.nanoTime();
            assertEquals(canonical,ImmutableBucketedNbtCheckpoint.expand(baselineWritten,target,STORE),
                    "Original full writer must preserve identical real Contra state");
            assertEquals(canonical,ImmutableBucketedNbtCheckpoint.expand(optimized,target,STORE),
                    "Exact original Contra metadata, every job/area/plan, must match legacy snapshot");
            var tmp=Files.createTempFile(target.getParent(),".run90-parent-",".partial");
            NbtIo.writeCompressed(optimized,tmp);
            Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            var after=NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap());
            assertEquals(canonical,ImmutableBucketedNbtCheckpoint.expand(after,target,STORE),
                    "Cold reread after atomic replacement must restore exact canonical metadata");
            var reopened=PagedSavedDataRuntime.withStore(destWorld,STORE,
                ()->WorldPrepSavedData.load(after.getCompound("data"),null));
            assertEquals(job.cursor()+1,reopened.jobs().get(BATCH).cursor());
            assertEquals(parent.jobs().size(),reopened.jobs().size());
            assertEquals(parent.areas().size(),reopened.areas().size());
            assertEquals(sourceHash,ImmutableBucketedNbtCheckpoint.fileSha256(sourceFile),
                    "Certified original A .dat must remain byte-identical");
            assertEquals(fullSource,ImmutableBucketedNbtCheckpoint.expand(
                    NbtIo.readCompressed(sourceFile,NbtAccounter.unlimitedHeap()),sourceFile,STORE),
                    "Certified A source and all referenced pages remain unchanged");
            long slowNs=baselineCaptureEnded-baselineCaptureStarted
                    +baselineWriteEnded-baselineWriteStarted;
            long fastNs=sparseCaptureEnded-sparseCaptureStarted
                    +sparseWriteEnded-sparseWriteStarted;
            assertTrue(slowNs>0 && fastNs>0);
            System.out.printf("RUN90_TRUE_CONTRA_COLD_95K UUID=%s cursorBefore=%d cursorAfter=%d jobs=%d areas=%d sourceSHA=%s referencedPagesCopied=%d legacyCaptureMs=%d legacyPagesMs=%d sparseCaptureMs=%d sparsePagesMs=%d fullCheckpointGainX=%.2f%n",
                BATCH,job.cursor(),job.cursor()+1,parent.jobs().size(),parent.areas().size(),sourceHash,count,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(baselineCaptureEnded-baselineCaptureStarted),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(baselineWriteEnded-baselineWriteStarted),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(sparseCaptureEnded-sparseCaptureStarted),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(sparseWriteEnded-sparseWriteStarted),
                (double)slowNs/fastNs);
        }finally{
            restore(PagedSavedDataRuntime.MODE_KEY,priorMode);
            restore(PagedSavedDataRuntime.WORLD_KEY,priorWorld);
            restore("proutlost.run90.strictIncrementalMetadata",priorIncrement);
        }
    }
    static void restore(String key,String value){
        if(value==null)System.clearProperty(key);else System.setProperty(key,value);
    }
}
