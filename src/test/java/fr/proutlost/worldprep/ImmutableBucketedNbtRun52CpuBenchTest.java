package fr.proutlost.worldprep;

import fr.proutlost.worldprep.persistence.ImmutableBucketedNbtCheckpoint;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, serialized-on-HDD real filesystem speed comparison. NOT a
 * Minecraft throughput benchmark or a reason to migrate production.
 * Both modes retain identical durable page+SHA+fsync+atomic semantics.
 */
class ImmutableBucketedNbtRun52CpuBenchTest {
    @TempDir Path work;
    private static final String STORE="proutlost_worldprep";
    private static final String FLAG="proutlost.run52.parallelNbtBuckets";
    static long median(ArrayList<Long> list){
        var l=new ArrayList<Long>(list);l.sort(Long::compareTo);return l.get(l.size()/2);
    }
    static void set(boolean v){if(v)System.setProperty(FLAG,"true");else System.clearProperty(FLAG);}
    @Test void serialVersusFourWorkersIdenticalRealWriteDurableIncrementalBenchmark() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("proutlost.run52.benchmark"));
        String prior=System.getProperty(FLAG);
        try {
            var root=ImmutableBucketedNbtCheckpointTest.root(STORE,3000);
            var serial=work.resolve("serial/data/"+STORE+".dat");
            var parallel=work.resolve("parallel/data/"+STORE+".dat");
            set(false);
            long t0=System.nanoTime();
            var a=ImmutableBucketedNbtCheckpoint.externalize(root,serial,STORE);
            long initSerial=(System.nanoTime()-t0)/1_000_000;
            set(true);
            t0=System.nanoTime();
            var b=ImmutableBucketedNbtCheckpoint.externalize(root,parallel,STORE);
            long initParallel=(System.nanoTime()-t0)/1_000_000;
            assertEquals(a.compactRoot(),b.compactRoot());
            assertEquals(a.metrics().newPages(),b.metrics().newPages());
            var st=new ArrayList<Long>();
            var pt=new ArrayList<Long>();
            for(int i=0;i<8;i++){
                var jobs=root.getCompound("data").getList("jobs",Tag.TAG_COMPOUND);
                var rec=jobs.getCompound(17+i).copy();
                rec.putLong("cursor",1000000L+i);
                jobs.set(17+i,rec);
                ImmutableBucketedNbtCheckpoint.Capture sa,pb;
                long start;
                if(i%2==0){
                    set(false);start=System.nanoTime();sa=ImmutableBucketedNbtCheckpoint.externalize(root,serial,STORE);
                    st.add((System.nanoTime()-start)/1_000_000);
                    set(true);start=System.nanoTime();pb=ImmutableBucketedNbtCheckpoint.externalize(root,parallel,STORE);
                    pt.add((System.nanoTime()-start)/1_000_000);
                }else{
                    set(true);start=System.nanoTime();pb=ImmutableBucketedNbtCheckpoint.externalize(root,parallel,STORE);
                    pt.add((System.nanoTime()-start)/1_000_000);
                    set(false);start=System.nanoTime();sa=ImmutableBucketedNbtCheckpoint.externalize(root,serial,STORE);
                    st.add((System.nanoTime()-start)/1_000_000);
                }
                assertEquals(sa.compactRoot(),pb.compactRoot());
                assertEquals(sa.metrics().newPages(),pb.metrics().newPages());
                assertEquals(root,ImmutableBucketedNbtCheckpoint.expand(pb.compactRoot(),parallel,STORE));
            }
            long serialMedian=median(st),parallelMedian=median(pt);
            System.out.println("RUN52_REAL_FSYNC_PARALLEL_NBT_BENCH"+
                " recordsPerSection=3000 mode=FOUR_WORKER_VS_SERIAL"+
                " firstColdSerialMs="+initSerial+" firstColdParallelMs="+initParallel+
                " incrementalSerialMedianMs="+serialMedian+
                " incrementalParallelMedianMs="+parallelMedian+
                " incrementalSerialTotalMs="+st.stream().mapToLong(Long::longValue).sum()+
                " incrementalParallelTotalMs="+pt.stream().mapToLong(Long::longValue).sum()+
                " exactAllEightNBTGenParity=true"+
                " noMinecraftActiveWorldTouched=true");
        }finally{
            if(prior==null)System.clearProperty(FLAG);
            else System.setProperty(FLAG,prior);
        }
    }
}
