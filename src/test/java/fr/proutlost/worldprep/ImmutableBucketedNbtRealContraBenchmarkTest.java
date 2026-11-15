package fr.proutlost.worldprep;

import fr.proutlost.worldprep.persistence.ImmutableBucketedNbtCheckpoint;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.Objects;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ImmutableBucketedNbtRealContraBenchmarkTest {
    private static final Path PROJECT=Path.of("/srv/storage/proutlost-s2-candidates/contra-20261027");
    private static final Path COLD=PROJECT.resolve("Contra-ecology-run6-cold-resume-snapshot-RUN7/data");
    private static final Path BENCH=PROJECT.resolve("contra-run20-real-immutable-nbt-bench-run4-20261028");

    static long millis(){return System.nanoTime()/1_000_000;}
    static long pages(Path dataDir)throws Exception{
        try(Stream<Path> s=Files.walk(dataDir.resolve("worldprep-paged-v1"))){
            return s.filter(p->p.getFileName().toString().endsWith(".nbt.gz")).count();
        }
    }
    static void commit(CompoundTag root,Path target)throws Exception{
        var staged=Files.createTempFile(target.getParent(),".run20-test-root-",".partial");
        NbtIo.writeCompressed(root,staged);
        try(var c=FileChannel.open(staged,StandardOpenOption.WRITE)){c.force(true);}
        Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        try(var c=FileChannel.open(target.getParent(),StandardOpenOption.READ)){c.force(true);}
    }
    static long gzBytes(CompoundTag root,Path dataDir,String name)throws Exception{
        var staged=dataDir.resolve(name);
        NbtIo.writeCompressed(root,staged);
        return Files.size(staged);
    }
    static void benchmark(String store)throws Exception{
        // Opt-in: never run the expensive real corpus when ordinary Gradle test runs.
        Assumptions.assumeTrue(Boolean.getBoolean("contra.run20.realBench"));
        Assumptions.assumeTrue(Files.isRegularFile(COLD.resolve(store+".dat")));
        Path work=BENCH.resolve(store);
        assertFalse(Files.exists(work),"Do not overwrite original real read-only benchmark attempts");
        Files.createDirectories(work.resolve("data"));
        Path target=work.resolve("data/"+store+".dat");
        Path source=COLD.resolve(store+".dat");
        Files.copy(source,target);
        CompoundTag baseline=NbtIo.readCompressed(source,NbtAccounter.unlimitedHeap());
        assertFalse(ImmutableBucketedNbtCheckpoint.isPaged(baseline));
        long t0=millis();
        var first=ImmutableBucketedNbtCheckpoint.externalize(baseline,target,store);
        long firstMs=millis()-t0;
        assertEquals(baseline,
                ImmutableBucketedNbtCheckpoint.expand(first.compactRoot(),target,store),
                "Every real Contra byte-semantic NBT field and list order must be preserved");
        assertEquals(baseline,NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap()),
                "Uncommitted paged leaves MUST NOT change the legacy root");
        long firstIndexCompressed=gzBytes(first.compactRoot(),work.resolve("data"),"first-index-only.bin");
        commit(first.compactRoot(),target);
        assertEquals(baseline,ImmutableBucketedNbtCheckpoint.expand(
                NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap()),target,store));

        var updated=baseline.copy();
        ListTag list;
        CompoundTag changed;
        if(store.equals("proutlost_worldprep")){
            list=updated.getCompound("data").getList("jobs",Tag.TAG_COMPOUND);
            var ix=-1;
            for(int i=0;i<list.size();i++)
                if("BATCH_ECOLOGY".equals(list.getCompound(i).getString("operation"))){ix=i;break;}
            assertTrue(ix>=0,"No exact saved original Contra parent");
            changed=list.getCompound(ix).copy();
            changed.putLong("cursor",changed.getLong("cursor")+1);
            list.set(ix,changed);
        }else{
            list=updated.getCompound("data").getList("journals",Tag.TAG_COMPOUND);
            assertFalse(list.isEmpty());
            changed=list.getCompound(0).copy();
            changed.putLong("pageCount",changed.getLong("pageCount")+1);
            list.set(0,changed);
        }
        t0=millis();
        var second=ImmutableBucketedNbtCheckpoint.externalize(updated,target,store);
        long secondMs=millis()-t0;
        assertTrue(second.metrics().newPages()>=1);
        assertTrue(second.metrics().newPages()<=3,
                "A single real NBT record change must only rewrite its stable bucket");
        assertEquals(updated,ImmutableBucketedNbtCheckpoint.expand(second.compactRoot(),target,store));
        assertEquals(baseline,ImmutableBucketedNbtCheckpoint.expand(first.compactRoot(),target,store),
                "Immutable earlier paged generation must remain recoverable");
        var previous=work.resolve("data/previous-verified-index.dat");
        Files.copy(target,previous);
        commit(second.compactRoot(),target);
        assertEquals(baseline,ImmutableBucketedNbtCheckpoint.expand(
                NbtIo.readCompressed(previous,NbtAccounter.unlimitedHeap()),target,store));
        assertEquals(updated,ImmutableBucketedNbtCheckpoint.expand(
                NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap()),target,store));
        long secondIndexCompressed=Files.size(target);

        System.out.println("RUN20_REAL_CONTRA_NBT_ROUNDTRIP_PASS store="+store+
            " legacyGzipBytes="+Files.size(source)+
            " pageCount="+first.metrics().referencedPages()+
            " firstNewPages="+first.metrics().newPages()+
            " firstPageBytes="+first.metrics().newPageBytes()+
            " firstIndexGzipBytes="+firstIndexCompressed+
            " firstMigrationMs="+firstMs+
            " changedRecordNewPages="+second.metrics().newPages()+
            " changedRecordNewPageGzipBytes="+second.metrics().newPageBytes()+
            " changedIndexGzipBytes="+secondIndexCompressed+
            " changedSnapshotMs="+secondMs+
            " retainedEarlierGeneration=true"+
            " legacyRootUntouchedBeforeCommit=true"+
            " noProductionFilesModified=true");
    }
    @Test void actualFrozenWorldPrepMetadataIsLosslessAndSingleJobUpdateIsPaged()throws Exception{
        benchmark("proutlost_worldprep");
    }
    @Test void actualFrozenBlockMetadataIsLosslessAndSingleJournalUpdateIsPaged()throws Exception{
        benchmark("proutlost_worldprep_blocks");
    }
}
