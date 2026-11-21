package fr.proutlost.worldprep.persistence;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockDeltaDurabilityTest {
    @org.junit.jupiter.api.BeforeAll static void minecraftVersion() { net.minecraft.SharedConstants.tryDetectVersion(); }
    @TempDir Path temporary;
    static final String STORE=BlockWorldPrepData.FILE_ID;
    static final String INDEX=PagedSavedDataRuntime.INDEX_MARKER;
    static BlockWorldPrepData.Plan plan(int n) {
        var p=new BlockWorldPrepData.Plan("proutlost:tile_"+n,"SOILS","development","input-"+n,"fp-"+n);
        p.planId(new UUID(123,n));p.entryCount(13);p.eligible(20);p.skipped(7);return p;
    }
    static BlockWorldPrepData.Journal journal(int n) {
        var j=new BlockWorldPrepData.Journal(new UUID(456,n),new UUID(789,n),
                new UUID(123,n),"proutlost:tile_"+n,"SOILS","fp-"+n);
        j.pageCount(1);j.entryCount(13);j.journalRoot("sha-"+n);return j;
    }
    static CompoundTag full(int n) {
        var d=new BlockWorldPrepData();
        for(int i=0;i<n;i++) {
            var p=plan(i);d.plans().put(BlockWorldPrepData.planKey(p.area,p.pass),p);
            var j=journal(i);d.journals().put(j.id,j);
        }
        var root=new CompoundTag();root.put("data",d.save(new CompoundTag(),null));
        root.putInt("DataVersion",3955);return root;
    }
    static CompoundTag envelope(CompoundTag data) {
        var t=new CompoundTag();t.put("data",data);t.putInt("DataVersion",3955);return t;
    }
    static Path baseline(Path base,CompoundTag initial) throws Exception {
        Path file=base.resolve("data/"+STORE+".dat");Files.createDirectories(file.getParent());
        var capture=ImmutableBucketedNbtCheckpoint.externalize(initial,file,STORE);
        NbtIo.writeCompressed(capture.compactRoot(),file);
        Files.writeString(base.resolve("level.dat"),"INDEPENDENT_FIXTURE");
        Files.writeString(base.resolve(PagedSavedDataRuntime.MARKER),
                "{\"schemaVersion\":1,\"mode\":\""+PagedSavedDataRuntime.DEV_MODE+
                "\",\"world\":\""+base.toRealPath()+"\",\"protectedOriginalsUnchanged\":true}");
        return file;
    }
    static final class Scope implements AutoCloseable {
        final Map<String,String> old=new LinkedHashMap<>();
        Scope(Path world) throws Exception {
            set(PagedSavedDataRuntime.MODE_KEY,PagedSavedDataRuntime.DEV_MODE);
            set(PagedSavedDataRuntime.WORLD_KEY,world.toRealPath().toString());
            set(BlockWorldPrepData.BLOCK_DELTA_FLAG,"true");
        }
        void set(String k,String v){ old.put(k,System.getProperty(k));System.setProperty(k,v); }
        public void close(){old.forEach((k,v)->{if(v==null)System.clearProperty(k);else System.setProperty(k,v);});}
    }
    static BlockWorldPrepData load(Path file) throws Exception {
        var compact=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());
        return PagedSavedDataRuntime.withStore(file.getParent().getParent(),STORE,
                ()->BlockWorldPrepData.load(compact.getCompound("data"),null));
    }
    static CompoundTag expand(CompoundTag root,Path file)throws Exception {
        return ImmutableBucketedNbtCheckpoint.expand(root,file,STORE);
    }
    static void publishRoot(CompoundTag root,Path file)throws Exception {
        var staged=Files.createTempFile(file.getParent(),"fixture-root-",".partial");
        NbtIo.writeCompressed(root,staged);
        try(var c=java.nio.channels.FileChannel.open(staged,StandardOpenOption.WRITE)){c.force(true);}
        Files.move(staged,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        try(var c=java.nio.channels.FileChannel.open(file.getParent(),StandardOpenOption.READ)){c.force(true);}
    }

    @Test void mutationsAppendsAndDeletionsRoundTripWithExactUnchangedLegacyFormat()throws Exception {
        var initial=full(400);var file=baseline(temporary,initial);
        try(var scope=new Scope(temporary)) {
            var data=load(file);
            var expected=BlockWorldPrepData.load(initial.getCompound("data"),null);
            for(var d:List.of(data,expected)) {
                var p=d.plans().get("proutlost:tile_11|SOILS");p.fingerprint("changed");
                p.entryCount(991);p.upstreamPlanId(new UUID(99,11));p.upstreamRoot("root");
                var j=d.journals().get(new UUID(456,11));j.pageCount(4);j.entryCount(991);j.journalRoot("new-root");
                var added=plan(401);d.plans().put(BlockWorldPrepData.planKey(added.area,added.pass),added);
                var addedJournal=journal(401);d.journals().put(addedJournal.id,addedJournal);
                d.plans().remove("proutlost:tile_8|SOILS");d.journals().remove(new UUID(456,8));
            }
            var capture=envelope(data.save(new CompoundTag(),null));
            var intent=capture.getCompound("data").getCompound(BlockWorldPrepData.BLOCK_DELTA_MARKER);
            assertEquals(2,intent.getList("plans",Tag.TAG_COMPOUND).size());
            assertEquals(2,intent.getList("journals",Tag.TAG_COMPOUND).size());
            assertEquals(1,intent.getList("plansDeleted",Tag.TAG_STRING).size());
            String oldSha=ImmutableBucketedNbtCheckpoint.fileSha256(file);
            var candidate=PagedSavedDataRuntime.prepareWrite(capture,file);
            assertEquals(oldSha,ImmutableBucketedNbtCheckpoint.fileSha256(file));
            assertEquals(initial,expand(NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap()),file));
            assertEquals(envelope(expected.save(new CompoundTag(),null)),expand(candidate,file));
            publishRoot(candidate,file);
            assertEquals(envelope(expected.save(new CompoundTag(),null)),expand(NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap()),file));
        }
    }

    @Test void everyPersistedMutableFieldIsTrackedAndCapturesAreImmutable()throws Exception {
        var file=baseline(temporary,full(30));
        try(var scope=new Scope(temporary)) {
            var data=load(file);
            var p=data.plans().firstEntry().getValue();
            p.fingerprint("x");p.planId(new UUID(32,32));p.entryCount(88);
            p.upstreamPlanId(new UUID(45,45));p.upstreamRoot("u");p.eligible(99);p.skipped(10);
            var j=data.journals().firstEntry().getValue();
            j.pageCount(3);j.entryCount(88);j.journalRoot("j");
            var first=envelope(data.save(new CompoundTag(),null));var frozen=first.copy();
            p.fingerprint("later");j.journalRoot("later");
            assertEquals(frozen,first,"A later record mutation must not mutate an in-flight capture");
            var next=PagedSavedDataRuntime.prepareWrite(first,file);publishRoot(next,file);
            var second=envelope(data.save(new CompoundTag(),null));
            var expanded=expand(PagedSavedDataRuntime.prepareWrite(second,file),file);
            var plans=expanded.getCompound("data").getList("plans",Tag.TAG_COMPOUND);
            assertEquals("later",plans.getCompound(0).getString("fingerprint"));
            assertEquals("later",expanded.getCompound("data").getList("journals",Tag.TAG_COMPOUND).getCompound(0).getString("journalRoot"));
        }
    }

    @Test void transientPlansAndNullPublicationTransitionsDoNotLoseOrInventRecords()throws Exception {
        var file=baseline(temporary,full(2));
        try(var scope=new Scope(temporary)) {
            var data=load(file);
            var transientPlan=new BlockWorldPrepData.Plan("proutlost:transient","SOILS","development","i","f");
            data.plans().put("proutlost:transient|SOILS",transientPlan);
            var first=PagedSavedDataRuntime.prepareWrite(envelope(data.save(new CompoundTag(),null)),file);
            assertEquals(2,expand(first,file).getCompound("data").getList("plans",Tag.TAG_COMPOUND).size());
            publishRoot(first,file);
            transientPlan.planId(UUID.randomUUID());
            var next=PagedSavedDataRuntime.prepareWrite(envelope(data.save(new CompoundTag(),null)),file);
            assertEquals(3,expand(next,file).getCompound("data").getList("plans",Tag.TAG_COMPOUND).size());
            publishRoot(next,file);
            transientPlan.planId(null);
            var last=PagedSavedDataRuntime.prepareWrite(envelope(data.save(new CompoundTag(),null)),file);
            assertEquals(2,expand(last,file).getCompound("data").getList("plans",Tag.TAG_COMPOUND).size());
        }
    }

    @Test void readonlyViewsForeignOwnersAndOffThreadMutationCannotBypassJournal()throws Exception {
        var file=baseline(temporary,full(4));
        try(var scope=new Scope(temporary)) {
            var data=load(file);var p=data.plans().firstEntry().getValue();
            assertThrows(UnsupportedOperationException.class,()->data.plans().firstEntry().setValue(plan(4)));
            assertThrows(UnsupportedOperationException.class,()->data.plans().values().clear());
            assertThrows(UnsupportedOperationException.class,()->data.plans().descendingMap().pollFirstEntry());
            assertThrows(IllegalStateException.class,()->new BlockWorldPrepData().plans().put(BlockWorldPrepData.planKey(p.area,p.pass),p));
            long before=p.entryCount();var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var thread=new Thread(()->{try{p.entryCount(919);}catch(Throwable e){failure.set(e);}});
            thread.start();thread.join();assertInstanceOf(IllegalStateException.class,failure.get());
            assertEquals(before,p.entryCount());
        }
    }

    @Test void corruptMissingForeignAndOmittedMetadataFailWithoutRootPublication()throws Exception {
        var file=baseline(temporary,full(40));
        try(var scope=new Scope(temporary)) {
            var data=load(file);data.plans().firstEntry().getValue().entryCount(99);
            var capture=envelope(data.save(new CompoundTag(),null));
            String original=ImmutableBucketedNbtCheckpoint.fileSha256(file);
            var foreign=capture.copy();foreign.getCompound("data").getCompound(BlockWorldPrepData.BLOCK_DELTA_MARKER).putString("store","foreign");
            assertThrows(IOException.class,()->PagedSavedDataRuntime.prepareWrite(foreign,file));
            var omitted=capture.copy();omitted.getCompound("data").getCompound(BlockWorldPrepData.BLOCK_DELTA_MARKER).putInt("plansCount",39);
            assertThrows(IOException.class,()->PagedSavedDataRuntime.prepareWrite(omitted,file));
            var root=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());
            var sec=root.getCompound("data").getCompound(INDEX).getList("sections",Tag.TAG_COMPOUND).getCompound(0);
            var ref=sec.getList("buckets",Tag.TAG_COMPOUND).getCompound(0);
            Path page=file.getParent().resolve("worldprep-paged-v1").resolve(STORE).resolve(sec.getString("name")).resolve(ref.getString("sha256")+".nbt.gz");
            byte[] bytes=Files.readAllBytes(page);
            Files.writeString(page,"corrupt-content");
            assertThrows(IOException.class,()->PagedSavedDataRuntime.prepareWrite(capture,file));
            Files.delete(page);
            assertThrows(IOException.class,()->PagedSavedDataRuntime.prepareWrite(capture,file));
            Files.write(page,bytes);
            assertEquals(original,ImmutableBucketedNbtCheckpoint.fileSha256(file));
        }
    }

    @Test void nativeCheckpointActuallyPublishesThroughAcknowledgedSavedData()throws Exception {
        var file=baseline(temporary,full(8));
        try(var scope=new Scope(temporary)) {
            var data=load(file);data.plans().firstEntry().getValue().entryCount(675);
            data.save(file.toFile(),null);data.flushNativeSave();
            assertTrue(data.checkpointReady());
            var full=expand(NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap()),file);
            assertEquals(675,full.getCompound("data").getList("plans",Tag.TAG_COMPOUND).getCompound(0).getLong("entryCount"));
        }
    }

    @Test void abruptCrashesBeforeAndAfterPublicationRecoverExactAdmittedRoot()throws Exception {
        for(String phase:List.of("AFTER_PAGES_DURABLE","BEFORE_ROOT_PUBLISH","AFTER_ROOT_DURABLE")) {
            Path world=temporary.resolve(phase);Files.createDirectory(world);
            var initial=full(16);var file=baseline(world,initial);
            String sha=ImmutableBucketedNbtCheckpoint.fileSha256(file);
            String cp=System.getProperty("proutlost.forkedRuntimeClasspath",System.getProperty("java.class.path"));
            var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),
                    "-Xmx384m","-XX:ActiveProcessorCount=1","-cp",cp,
                    BlockDeltaCrashChild.class.getName(),file.toString(),phase)
                    .redirectErrorStream(true).redirectOutput(world.resolve("abrupt-crash.log").toFile()).start();
            assertTrue(process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS),"Crash child must terminate");
            assertEquals(73,process.exitValue(),Files.readString(world.resolve("abrupt-crash.log")));
            try(var scope=new Scope(world)) {
                var loaded=load(file);long cursor=loaded.plans().firstEntry().getValue().entryCount();
                assertEquals(phase.equals("AFTER_ROOT_DURABLE")?777:13,cursor);
                var actual=expand(NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap()),file);
                if(!phase.equals("AFTER_ROOT_DURABLE")) {
                    assertEquals(sha,ImmutableBucketedNbtCheckpoint.fileSha256(file));assertEquals(initial,actual);
                }
                // Complete the same update after crash; no duplicate/missing record.
                loaded.plans().firstEntry().getValue().entryCount(777);loaded.changed();
                loaded.save(file.toFile(),null);loaded.flushNativeSave();
                var reopened=load(file);assertEquals(16,reopened.plans().size());assertEquals(16,reopened.journals().size());
                assertEquals(777,reopened.plans().firstEntry().getValue().entryCount());
            }
        }
    }
}
