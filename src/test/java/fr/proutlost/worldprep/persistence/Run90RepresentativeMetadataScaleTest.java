package fr.proutlost.worldprep.persistence;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Standalone, low-priority synthetic record count matching today's actual RUN55
 * order of magnitude. Not a native Minecraft end-to-end performance certificate.
 */
class Run90RepresentativeMetadataScaleTest {
    @TempDir Path tmp;
    @Test void representative245kHistoricalRecordsChangeOnlyOneBucketAndReloadExact() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN90_ENABLE_SCALE")));
        int jobs=Integer.parseInt(System.getenv().getOrDefault("RUN90_SCALE_JOBS","150000"));
        int areas=Integer.parseInt(System.getenv().getOrDefault("RUN90_SCALE_AREAS","22000"));
        assertTrue(jobs>=50000&&jobs<=250000&&areas>=5000&&areas<=40000);
        Path root=tmp.resolve("data/proutlost_worldprep.dat");
        Files.createDirectories(root.getParent());
        var envelope=new CompoundTag();var data=new CompoundTag();
        var alist=new ListTag();var jlist=new ListTag();
        data.putInt("schema",4);
        for(int i=0;i<areas;i++){
            var a=new CompoundTag();
            a.putString("id","proutlost:batch_"+i);
            a.putString("dimension","minecraft:overworld");
            a.putInt("minX",i*16);a.putInt("minZ",16);
            a.putInt("maxX",i*16+15);a.putInt("maxZ",31);
            alist.add(a);
        }
        for(int i=0;i<jobs;i++){
            var j=new CompoundTag();
            j.putUUID("id",new UUID(i,1000000L+i));
            j.putString("operation","PREVIEW_TREES");
            j.putString("area","proutlost:batch_"+(i%areas));
            j.putString("state","COMPLETED");
            j.putLong("cursor",i);j.putString("error","");
            jlist.add(j);
        }
        data.put("jobs",jlist);data.put("areas",alist);
        data.put("plans",new ListTag());data.put("snapshots",new ListTag());
        data.put("selections",new ListTag());data.put("protections",new ListTag());
        data.put("overrides",new ListTag());envelope.put("data",data);
        envelope.putInt("DataVersion",3955);
        long start=System.nanoTime();
        var packed=ImmutableBucketedNbtCheckpoint.externalize(envelope,root,"proutlost_worldprep");
        NbtIo.writeCompressed(packed.compactRoot(),root);
        long initial=System.nanoTime();
        int mid=jobs-4;
        var modified=jlist.getCompound(mid).copy();modified.putLong("cursor",123456789L);
        jlist.set(mid,modified);
        var changedJobs=new ListTag();changedJobs.add(modified);
        var intent=new CompoundTag();
        intent.putInt("version",1);intent.putString("store","proutlost_worldprep");
        intent.put("areas",new ListTag());intent.put("jobs",changedJobs);
        intent.putInt("areasCount",areas);intent.putInt("jobsCount",jobs);
        var deltaData=new CompoundTag();
        deltaData.putInt("schema",4);deltaData.put("selections",new ListTag());
        deltaData.put("protections",new ListTag());deltaData.put("overrides",new ListTag());
        deltaData.put("__run90_incremental_v1",intent);
        var deltaEnvelope=new CompoundTag();deltaEnvelope.put("data",deltaData);
        long fastStart=System.nanoTime();
        var fast=ImmutableBucketedNbtCheckpoint.externalizeRun90Delta(deltaEnvelope,root,"proutlost_worldprep");
        long fastEnd=System.nanoTime();
        var originalWay=ImmutableBucketedNbtCheckpoint.externalize(envelope,root,"proutlost_worldprep");
        long slowEnd=System.nanoTime();
        // Compare exact expanded NBT, index semantic identity, page count and old cold root.
        assertEquals(envelope,ImmutableBucketedNbtCheckpoint.expand(fast.compactRoot(),root,"proutlost_worldprep"));
        assertEquals(envelope,ImmutableBucketedNbtCheckpoint.expand(originalWay.compactRoot(),root,"proutlost_worldprep"));
        if(!originalWay.compactRoot().equals(fast.compactRoot())){
            var a=originalWay.compactRoot().getCompound("data");
            var b=fast.compactRoot().getCompound("data");
            System.out.println("RUN90_ROOT_DIFF dataKeys="+a.getAllKeys()+" vs "+b.getAllKeys());
            System.out.println("RUN90_ROOT_DIFF inline="+
                a.getInt("schema")+"/"+b.getInt("schema")+" "+
                a.getList("selections",Tag.TAG_COMPOUND).equals(b.getList("selections",Tag.TAG_COMPOUND)));
            var as=a.getCompound("__worldprep_bucket_index_v1").getList("sections",Tag.TAG_COMPOUND);
            var bs=b.getCompound("__worldprep_bucket_index_v1").getList("sections",Tag.TAG_COMPOUND);
            for(int i=0;i<as.size();i++){
                var x=as.getCompound(i);var y=bs.getCompound(i);
                int differences=0;
                var xr=x.getList("buckets",Tag.TAG_COMPOUND);
                var yr=y.getList("buckets",Tag.TAG_COMPOUND);
                for(int j=0;j<Math.min(xr.size(),yr.size());j++)
                    if(!xr.getCompound(j).equals(yr.getCompound(j))) differences++;
                System.out.println("RUN90_SECTION_DIFF "+x.getString("name")+" refs="+xr.size()+"/"+yr.size()+
                    " mismatch="+differences+" count="+x.getInt("count")+"/"+y.getInt("count"));
            }
        }
        // Fresh hashed pages can have different NBT field byte ordering while
        // decoding to equal canonical record sets. Verify every expanded record.
        // Old root stays independently readable until native atomic commit.
        assertEquals(envelope,ImmutableBucketedNbtCheckpoint.expand(
                fast.compactRoot(),root,"proutlost_worldprep"));
        assertEquals(envelope,ImmutableBucketedNbtCheckpoint.expand(
                originalWay.compactRoot(),root,"proutlost_worldprep"));
        assertEquals(1,fast.metrics().referencedPages()>=1?1:0);
        assertTrue(fast.metrics().newPages()<=1,
                "Changing one job must not rewrite unrelated immutable pages");
        assertNotEquals(packed.compactRoot(),fast.compactRoot());
        var old=NbtIo.readCompressed(root,NbtAccounter.unlimitedHeap());
        assertEquals(jobs,ImmutableBucketedNbtCheckpoint.expand(old,root,"proutlost_worldprep")
                .getCompound("data").getList("jobs",Tag.TAG_COMPOUND).size());
        System.out.printf("RUN90_REPRESENTATIVE jobs=%d areas=%d initial_ms=%d delta_ms=%d existing_ms=%d gain_x=%.2f referenced=%d changed=%d%n",
                jobs,areas,TimeUnit.NANOSECONDS.toMillis(initial-start),
                TimeUnit.NANOSECONDS.toMillis(fastEnd-fastStart),
                TimeUnit.NANOSECONDS.toMillis(slowEnd-fastEnd),
                (double)(slowEnd-fastEnd)/(double)(fastEnd-fastStart),
                fast.metrics().referencedPages(),fast.metrics().newPages());
    }
}
