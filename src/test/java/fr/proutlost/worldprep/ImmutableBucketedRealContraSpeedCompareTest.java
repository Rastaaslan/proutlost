package fr.proutlost.worldprep;

import fr.proutlost.worldprep.persistence.ImmutableBucketedNbtCheckpoint;
import net.minecraft.nbt.*;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Fair isolated real-corpus comparison: BOTH use GZIP level 1 and
 * fsync(temp), ATOMIC_MOVE(new root), fsync(directory) at every iteration.
 * Full source capture is intentionally excluded; this measures persistence
 * phase only and cannot be interpreted as end-to-end ecological throughput.
 */
class ImmutableBucketedRealContraSpeedCompareTest {
    private static final Path ROOT=Path.of("/srv/storage/proutlost-s2-candidates/contra-20261027");
    private static final Path COLD=ROOT.resolve("Contra-ecology-run6-cold-resume-snapshot-RUN7/data");
    private static final Path WORK=ROOT.resolve("contra-run20-real-fair-persistence-speed-20261028");
    private static long now(){return System.nanoTime();}

    private static long commitGzip1(CompoundTag full,Path target)throws Exception{
        long start=now();
        Files.createDirectories(target.getParent());
        Path temp=Files.createTempFile(target.getParent(),".fair-gzip1-",".partial");
        try(var raw=Files.newOutputStream(temp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING);
            var buffer=new BufferedOutputStream(raw,256*1024);
            var gzip=new GZIPOutputStream(buffer,256*1024){{def.setLevel(1);}};
            var out=new DataOutputStream(gzip)){
            NbtIo.write(full,out);
        }
        try(var file=FileChannel.open(temp,StandardOpenOption.WRITE)){file.force(true);}
        Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        try(var dir=FileChannel.open(target.getParent(),StandardOpenOption.READ)){dir.force(true);}
        return (now()-start)/1_000_000;
    }
    private static void mutate(CompoundTag root,String store,int version){
        var data=root.getCompound("data");
        if(store.equals("proutlost_worldprep")){
            var jobs=data.getList("jobs",Tag.TAG_COMPOUND);
            int i=-1;
            for(int n=0;n<jobs.size();n++)
                if("BATCH_ECOLOGY".equals(jobs.getCompound(n).getString("operation"))){i=n;break;}
            assertTrue(i>=0);
            var p=jobs.getCompound(i).copy();
            p.putLong("cursor",33000L+version);
            jobs.set(i,p);
        }else{
            var list=data.getList("journals",Tag.TAG_COMPOUND);
            assertFalse(list.isEmpty());
            var p=list.getCompound(0).copy();
            p.putLong("pageCount",500L+version);
            list.set(0,p);
        }
    }
    static void measure(String store)throws Exception{
        Assumptions.assumeTrue(Boolean.getBoolean("contra.run20.realBench"));
        Path input=COLD.resolve(store+".dat");
        assertTrue(Files.isRegularFile(input));
        Path work=WORK.resolve(store);
        assertFalse(Files.exists(work),"Never reuse a previous measurement attempt");
        Path old=work.resolve("legacy/data/"+store+".dat");
        Path paged=work.resolve("paged/data/"+store+".dat");
        Files.createDirectories(old.getParent());
        Files.createDirectories(paged.getParent());
        var original=NbtIo.readCompressed(input,NbtAccounter.unlimitedHeap());
        var legacy=original.copy();
        var compact=original.copy();
        var baseline=ImmutableBucketedNbtCheckpoint.externalize(compact,paged,store);
        commitGzip1(baseline.compactRoot(),paged);
        commitGzip1(original,old);
        var traditionalMs=new ArrayList<Long>();
        var pageMs=new ArrayList<Long>();
        int newPages=0;
        long pageBytes=0;
        final int ITERATIONS=10;
        for(int i=1;i<=ITERATIONS;i++){
            mutate(legacy,store,i);
            mutate(compact,store,i);
            long t=commitGzip1(legacy,old);
            traditionalMs.add(t);
            long t0=now();
            var delta=ImmutableBucketedNbtCheckpoint.externalize(compact,paged,store);
            commitGzip1(delta.compactRoot(),paged);
            pageMs.add((now()-t0)/1_000_000);
            newPages+=delta.metrics().newPages();
            pageBytes+=delta.metrics().newPageBytes();
        }
        var finalLegacy=NbtIo.readCompressed(old,NbtAccounter.unlimitedHeap());
        var finalPaged=ImmutableBucketedNbtCheckpoint.expand(
            NbtIo.readCompressed(paged,NbtAccounter.unlimitedHeap()),paged,store);
        assertEquals(legacy,finalLegacy);
        assertEquals(compact,finalPaged);
        assertEquals(finalLegacy,finalPaged);
        assertEquals(ITERATIONS,newPages,"One modified record -> one immutable page per update");
        var oldSorted=new ArrayList<Long>(traditionalMs);oldSorted.sort(Long::compare);
        var newSorted=new ArrayList<Long>(pageMs);newSorted.sort(Long::compare);
        System.out.println("RUN20_REAL_FAIR_PERSISTENCE_SPEED"+
            " store="+store+" legacyLevel1MedianMs="+oldSorted.get(ITERATIONS/2)+
            " pagedLevel1MedianMs="+newSorted.get(ITERATIONS/2)+
            " legacyLevel1TotalMs="+traditionalMs.stream().mapToLong(Long::longValue).sum()+
            " pagedLevel1TotalMs="+pageMs.stream().mapToLong(Long::longValue).sum()+
            " iterations="+ITERATIONS+" newPageCount="+newPages+
            " newPageCompressedBytes="+pageBytes+
            " equalFinalMinecraftNBT=true"+
            " originalSourceUnmodified=true"+
            " testOnlyNoLiveMinecraftPatch=true");
    }
    @Test void fairGzip1CompareTrueSavedData()throws Exception{measure("proutlost_worldprep");}
    @Test void fairGzip1CompareTrueBlocksData()throws Exception{measure("proutlost_worldprep_blocks");}
}
