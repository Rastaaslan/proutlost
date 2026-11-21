package fr.proutlost.worldprep;

import fr.proutlost.worldprep.persistence.ImmutableBucketedNbtCheckpoint;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.UUID;
import java.io.IOException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ImmutableBucketedNbtCheckpointTest {
    @TempDir Path temporary;

    static CompoundTag root(String store,int n){
        var envelope=new CompoundTag();
        var data=new CompoundTag();
        if(store.equals("proutlost_worldprep")) data.putInt("schema",4);
        else data.putInt("format",4);
        var areas=new ListTag();
        var jobs=new ListTag();
        var plans=new ListTag();
        var journals=new ListTag();
        for(int i=0;i<n;i++){
            var area=new CompoundTag();
            area.putString("id","proutlost:batch_"+i);
            area.putString("dimension","minecraft:overworld");
            area.putInt("minX",i*16);area.putInt("minZ",16);area.putInt("maxX",i*16+15);area.putInt("maxZ",31);
            areas.add(area);
            var job=new CompoundTag();
            job.putUUID("id",new UUID(i,1000000L+i));
            job.putString("operation","PREVIEW_TREES");
            job.putString("area","proutlost:batch_"+i);
            job.putString("state","COMPLETED");
            job.putLong("cursor",i);
            jobs.add(job);
            var plan=new CompoundTag();
            plan.putString("area","proutlost:batch_"+i);
            if(store.equals("proutlost_worldprep_blocks"))plan.putString("pass","SOILS");
            plan.putString("fingerprint","sha256:"+Integer.toHexString(i));
            plans.add(plan);
            var journal=new CompoundTag();
            journal.putUUID("id",new UUID(i+1,12345678L+i));
            journal.putString("area","proutlost:batch_"+i);
            journal.putString("journalRoot","chain-"+i);
            journals.add(journal);
        }
        var sortedPlans=new java.util.ArrayList<CompoundTag>();
        for(var planTag:plans)sortedPlans.add((CompoundTag)planTag);
        sortedPlans.sort(Comparator.comparing(plan->plan.getString("area")
                +(store.equals("proutlost_worldprep_blocks")?"|"+plan.getString("pass"):"")));
        var orderedPlans=new ListTag();
        for(var plan:sortedPlans)orderedPlans.add(plan);
        if(store.equals("proutlost_worldprep")){
            data.put("areas",areas);data.put("jobs",jobs);
            data.put("plans",orderedPlans);
            data.put("snapshots",new ListTag());
            data.put("selections",new ListTag());
            data.put("overrides",new ListTag());
            data.put("protections",new ListTag());
        }else {
            data.put("plans",orderedPlans);
            data.put("journals",journals);
        }
        envelope.put("data",data);envelope.putInt("DataVersion",3955);
        return envelope;
    }

    static long pages(Path dataDir) throws IOException{
        var folder=dataDir.resolve("worldprep-paged-v1");
        if(!Files.isDirectory(folder))return 0;
        try(Stream<Path> paths=Files.walk(folder)){
            return paths.filter(p->p.getFileName().toString().endsWith(".nbt.gz")).count();
        }
    }

    @Test void pagesRoundTripLosslesslyAndWriteOnlyChangedHashBuckets() throws Exception {
        var target=temporary.resolve("data/proutlost_worldprep.dat");
        var full=root("proutlost_worldprep",180);
        var first=ImmutableBucketedNbtCheckpoint.externalize(full,target,"proutlost_worldprep");
        assertTrue(ImmutableBucketedNbtCheckpoint.isPaged(first.compactRoot()));
        assertTrue(first.metrics().newPages()>0);
        assertEquals(full,ImmutableBucketedNbtCheckpoint.expand(
                first.compactRoot(),target,"proutlost_worldprep"));
        long previous=pages(target.getParent());
        var stable=ImmutableBucketedNbtCheckpoint.externalize(full,target,"proutlost_worldprep");
        assertEquals(0,stable.metrics().newPages());
        assertEquals(previous,pages(target.getParent()));
        assertEquals(first.compactRoot(),stable.compactRoot());

        var modified=full.copy();
        var jobs=modified.getCompound("data").getList("jobs",Tag.TAG_COMPOUND);
        var prior=jobs.getCompound(25).copy();
        prior.putLong("cursor",123456789L);
        jobs.set(25,prior);
        var newSnapshot=ImmutableBucketedNbtCheckpoint.externalize(modified,target,"proutlost_worldprep");
        assertTrue(newSnapshot.metrics().newPages()>=1);
        assertTrue(newSnapshot.metrics().newPages()<=2,
                "Changing one job must not reserialize hundreds of immutable pages");
        assertEquals(full,ImmutableBucketedNbtCheckpoint.expand(first.compactRoot(),target,"proutlost_worldprep"));
        assertEquals(modified,ImmutableBucketedNbtCheckpoint.expand(
                newSnapshot.compactRoot(),target,"proutlost_worldprep"));
    }

    @Test void blockMapsKeepTreeMapNaturalOrderAndAllValues() throws Exception {
        var target=temporary.resolve("data/proutlost_worldprep_blocks.dat");
        var full=root("proutlost_worldprep_blocks",220);
        var checkpoint=ImmutableBucketedNbtCheckpoint.externalize(full,target,"proutlost_worldprep_blocks");
        assertTrue(checkpoint.metrics().newPages()>0);
        // Synthetic jobs are ascending, but BlockWorldPrepData journals are
        // TreeMap UUID-sorted; testing order-preserving roundtrip on a sorted source.
        assertEquals(full,ImmutableBucketedNbtCheckpoint.expand(
                checkpoint.compactRoot(),target,"proutlost_worldprep_blocks"));
    }

    @Test void aCrashBeforeRootSwapLeavesPriorGenerationReadable() throws Exception {
        var target=temporary.resolve("data/proutlost_worldprep.dat");
        Files.createDirectories(target.getParent());
        var before=root("proutlost_worldprep",70);
        var old=ImmutableBucketedNbtCheckpoint.externalize(before,target,"proutlost_worldprep");
        NbtIo.writeCompressed(old.compactRoot(),target);
        var after=before.copy();
        after.getCompound("data").getList("jobs",Tag.TAG_COMPOUND).getCompound(0).putLong("cursor",4000);
        // Simulated power loss before the atomic root move: all newly published
        // hash-named pages exist, but the old gzip .dat is still authoritative.
        var unpublished=ImmutableBucketedNbtCheckpoint.externalize(after,target,"proutlost_worldprep");
        assertNotEquals(old.compactRoot(),unpublished.compactRoot());
        var actuallyOnDisk=NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap());
        assertEquals(before,ImmutableBucketedNbtCheckpoint.expand(actuallyOnDisk,target,"proutlost_worldprep"));
        assertEquals(after,ImmutableBucketedNbtCheckpoint.expand(unpublished.compactRoot(),target,"proutlost_worldprep"));
    }

    @Test void missingOrCorruptImmutablePageRefusesWithoutPartialFallback() throws Exception {
        var target=temporary.resolve("data/proutlost_worldprep.dat");
        var full=root("proutlost_worldprep",4);
        var paged=ImmutableBucketedNbtCheckpoint.externalize(full,target,"proutlost_worldprep");
        Path page;
        try(Stream<Path> paths=Files.walk(target.getParent().resolve("worldprep-paged-v1"))){
            page=paths.filter(p->p.getFileName().toString().endsWith(".nbt.gz")).findFirst().orElseThrow();
        }
        Files.writeString(page,"CORRUPTED");
        assertThrows(IOException.class,
                ()->ImmutableBucketedNbtCheckpoint.expand(paged.compactRoot(),target,"proutlost_worldprep"));
        assertThrows(IOException.class,
                ()->ImmutableBucketedNbtCheckpoint.externalize(full,target,"proutlost_worldprep"),
                "An immutable content-addressed page that changed must not be overwritten");
    }

    @Test void indexedNBTCanMoveWithDetachedWorldBackupWithoutAbsoluteReferences() throws Exception {
        var target=temporary.resolve("oldworld/data/proutlost_worldprep.dat");
        var full=root("proutlost_worldprep",50);
        var packed=ImmutableBucketedNbtCheckpoint.externalize(full,target,"proutlost_worldprep");
        Files.createDirectories(target.getParent());
        NbtIo.writeCompressed(packed.compactRoot(),target);
        Path from=target.getParent(),to=temporary.resolve("newworld/data");
        try(Stream<Path> paths=Files.walk(from)){
            for(var p:paths.toList()){
                Path dest=to.resolve(from.relativize(p));
                if(Files.isDirectory(p))Files.createDirectories(dest);
                else Files.copy(p,dest);
            }
        }
        var loaded=NbtIo.readCompressed(to.resolve(target.getFileName()),NbtAccounter.unlimitedHeap());
        assertEquals(full,ImmutableBucketedNbtCheckpoint.expand(
                loaded,to.resolve(target.getFileName()),"proutlost_worldprep"));
    }

    @Test void legacyFullNBTIsSupportedUnchangedForSafeMigration() throws Exception {
        var full=root("proutlost_worldprep",3);
        var loaded=ImmutableBucketedNbtCheckpoint.expand(
                full,temporary.resolve("data/proutlost_worldprep.dat"),"proutlost_worldprep");
        assertEquals(full,loaded);
        assertFalse(ImmutableBucketedNbtCheckpoint.isPaged(loaded));
    }

    @Test void dryRunRetentionNeverDropsCurrentOrPreviousOrProofOfFailure() throws Exception {
        var target=temporary.resolve("data/proutlost_worldprep.dat");
        var first=root("proutlost_worldprep",90);
        var a=ImmutableBucketedNbtCheckpoint.externalize(first,target,"proutlost_worldprep");
        var second=first.copy();
        second.getCompound("data").getList("jobs",Tag.TAG_COMPOUND)
                .getCompound(0).putLong("cursor",11111L);
        var b=ImmutableBucketedNbtCheckpoint.externalize(second,target,"proutlost_worldprep");
        var third=second.copy();
        third.getCompound("data").getList("jobs",Tag.TAG_COMPOUND)
                .getCompound(0).putLong("cursor",22222L);
        var c=ImmutableBucketedNbtCheckpoint.externalize(third,target,"proutlost_worldprep");
        var plan=fr.proutlost.worldprep.persistence.ImmutableBucketedNbtRetentionPlan.inspect(
                c.compactRoot(),b.compactRoot(),target,"proutlost_worldprep");
        assertTrue(plan.explicitlyDryRun());
        assertTrue(plan.detachedPages()>=1);
        assertEquals(plan.allPages()-plan.reachableFromEitherRoot(),plan.detachedPages());
        // EVEN the first old generation is still fully readable: dry-run
        // classification NEVER deletes a file or overwrites historical proofs.
        assertEquals(first,ImmutableBucketedNbtCheckpoint.expand(a.compactRoot(),target,"proutlost_worldprep"));
        assertEquals(second,ImmutableBucketedNbtCheckpoint.expand(b.compactRoot(),target,"proutlost_worldprep"));
        assertEquals(third,ImmutableBucketedNbtCheckpoint.expand(c.compactRoot(),target,"proutlost_worldprep"));
        assertThrows(IOException.class,()->
            fr.proutlost.worldprep.persistence.ImmutableBucketedNbtRetentionPlan.inspect(
                    c.compactRoot(),first,target,"proutlost_worldprep"));
    }

    @Test void unknownStoreIdentityNeverAuthorizesExternalPages() throws Exception {
        var full=root("proutlost_worldprep",3);
        assertThrows(IOException.class,()->ImmutableBucketedNbtCheckpoint.externalize(
                full,temporary.resolve("data/foreign_world.dat"),"foreign"));
        assertThrows(IOException.class,()->ImmutableBucketedNbtCheckpoint.expand(
                full,temporary.resolve("data/foreign_world.dat"),"foreign"));
    }
}
