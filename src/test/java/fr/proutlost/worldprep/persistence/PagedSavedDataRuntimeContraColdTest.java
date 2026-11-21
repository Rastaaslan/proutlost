package fr.proutlost.worldprep.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Callable;
import static org.junit.jupiter.api.Assertions.*;

/** Actual cold Contra NBT, never the running world. */
class PagedSavedDataRuntimeContraColdTest {
    private static final Path COLD=Path.of("/srv/storage/proutlost-s2-candidates/contra-20261027")
        .resolve("Contra-ecology-run6-cold-resume-snapshot-RUN7/data");
    private static final UUID ORIGINAL_BATCH=UUID.fromString("873e51c5-b528-4fb7-bc34-7e0221d6c59d");
    @TempDir Path temporary;

    private Path world() throws Exception {
        Path root=temporary.resolve("run21-isolated-copy");
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("level.dat"),"ISOLATED_NON_MINECRAFT_UNIT_TEST");
        Files.writeString(root.resolve(PagedSavedDataRuntime.MARKER),
            "{\"schemaVersion\":1,\"mode\":\"CONTRA_RUN21_DEV_ONLY\",\"world\":\""+
            root.toRealPath()+"\",\"protectedOriginalsUnchanged\":true}");
        return root;
    }
    private Path copy(Path root,String store)throws Exception {
        Path from=COLD.resolve(store+".dat");
        assertTrue(Files.isRegularFile(from));
        Path to=root.resolve("data/"+store+".dat");
        Files.copy(from,to);
        return to;
    }
    private <T> T withPinned(Path root,Callable<T> method)throws Exception {
        synchronized(PagedSavedDataRuntime.class){
            String m=System.getProperty(PagedSavedDataRuntime.MODE_KEY);
            String w=System.getProperty(PagedSavedDataRuntime.WORLD_KEY);
            try{
                System.setProperty(PagedSavedDataRuntime.MODE_KEY,PagedSavedDataRuntime.DEV_MODE);
                System.setProperty(PagedSavedDataRuntime.WORLD_KEY,root.toString());
                return method.call();
            }finally{
                if(m==null)System.clearProperty(PagedSavedDataRuntime.MODE_KEY);
                else System.setProperty(PagedSavedDataRuntime.MODE_KEY,m);
                if(w==null)System.clearProperty(PagedSavedDataRuntime.WORLD_KEY);
                else System.setProperty(PagedSavedDataRuntime.WORLD_KEY,w);
            }
        }
    }
    private static CompoundTag open(Path p)throws Exception {
        return NbtIo.readCompressed(p,NbtAccounter.unlimitedHeap());
    }
    private static CompoundTag envelope(CompoundTag data) {
        var r=new CompoundTag();r.putInt("DataVersion",3955);r.put("data",data);return r;
    }

    @Test void exactParentUuidCursorAndJobsSurviveNativeWriterAndColdFactoryReload()throws Exception{
        var w=world();var file=copy(w,WorldPrepSavedData.FILE_ID);
        var old=open(file);var oldSha=sha(COLD.resolve(WorldPrepSavedData.FILE_ID+".dat"));
        var initial=WorldPrepSavedData.load(old.getCompound("data"),null);
        var parent=initial.jobs().get(ORIGINAL_BATCH);
        assertNotNull(parent);
        assertEquals(33174L,parent.cursor());
        assertEquals(WorldPrepSavedData.JobState.PAUSED,parent.state());
        int jobs=initial.jobs().size(),areas=initial.areas().size();
        withPinned(w,()->{initial.persist(old,file);return null;});
        var paged=open(file);
        assertTrue(ImmutableBucketedNbtCheckpoint.isPaged(paged));
        assertEquals(old,ImmutableBucketedNbtCheckpoint.expand(paged,file,WorldPrepSavedData.FILE_ID));
        var reopened=withPinned(w,()->PagedSavedDataRuntime.withStore(w,WorldPrepSavedData.FILE_ID,
            ()->WorldPrepSavedData.load(paged.getCompound("data"),null)));
        assertEquals(jobs,reopened.jobs().size());assertEquals(areas,reopened.areas().size());
        assertEquals(33174L,reopened.jobs().get(ORIGINAL_BATCH).cursor());
        assertEquals(WorldPrepSavedData.JobState.PAUSED,reopened.jobs().get(ORIGINAL_BATCH).state());
        var j=reopened.jobs().get(ORIGINAL_BATCH);
        reopened.jobs().put(ORIGINAL_BATCH,j.withCursor(33175L));
        reopened.changed();
        withPinned(w,()->{reopened.persist(envelope(reopened.save(new CompoundTag(),null)),file);return null;});
        var after=open(file);
        var second=withPinned(w,()->PagedSavedDataRuntime.withStore(w,WorldPrepSavedData.FILE_ID,
            ()->WorldPrepSavedData.load(after.getCompound("data"),null)));
        assertEquals(33175L,second.jobs().get(ORIGINAL_BATCH).cursor());
        assertEquals(WorldPrepSavedData.JobState.PAUSED,second.jobs().get(ORIGINAL_BATCH).state());
        assertEquals(jobs,second.jobs().size());assertEquals(areas,second.areas().size());
        assertEquals(old,ImmutableBucketedNbtCheckpoint.expand(paged,file,WorldPrepSavedData.FILE_ID));
        assertEquals(oldSha,sha(COLD.resolve(WorldPrepSavedData.FILE_ID+".dat")));
        System.out.println("RUN21_COLD_WORLDPREP_NATIVE_FACTORY_RELOAD_PASS"+
            " originalUuid="+ORIGINAL_BATCH+" cursor=33175 jobs="+jobs+" areas="+areas);
    }

    @Test void originalBlockPlansJournalsAndPreviousPagedRootRemainReadable()throws Exception {
        var w=world();var file=copy(w,BlockWorldPrepData.FILE_ID);
        var before=open(file);
        var initial=BlockWorldPrepData.load(before.getCompound("data"),null);
        assertTrue(initial.compatible());assertTrue(initial.plans().size()>100);
        assertTrue(initial.journals().size()>100);
        int plans=initial.plans().size(),journals=initial.journals().size();
        withPinned(w,()->{initial.persist(before,file);return null;});
        var root=open(file);
        assertEquals(before,ImmutableBucketedNbtCheckpoint.expand(root,file,BlockWorldPrepData.FILE_ID));
        var reloaded=withPinned(w,()->PagedSavedDataRuntime.withStore(w,BlockWorldPrepData.FILE_ID,
            ()->BlockWorldPrepData.load(root.getCompound("data"),null)));
        assertEquals(plans,reloaded.plans().size());assertEquals(journals,reloaded.journals().size());
        var first=reloaded.journals().firstEntry().getValue();
        long count=first.pageCount();
        first.pageCount(count+1);
        reloaded.changed();
        withPinned(w,()->{reloaded.persist(envelope(reloaded.save(new CompoundTag(),null)),file);return null;});
        var after=withPinned(w,()->PagedSavedDataRuntime.withStore(w,BlockWorldPrepData.FILE_ID,
            ()->BlockWorldPrepData.load(openUnchecked(file).getCompound("data"),null)));
        assertEquals(count+1,after.journals().firstEntry().getValue().pageCount());
        assertEquals(journals,after.journals().size());
        assertEquals(before,ImmutableBucketedNbtCheckpoint.expand(root,file,BlockWorldPrepData.FILE_ID));
        System.out.println("RUN21_COLD_BLOCK_NATIVE_FACTORY_RELOAD_PASS plans="+plans+" journals="+journals);
    }
    private static CompoundTag openUnchecked(Path p){
        try{return open(p);}catch(Exception e){throw new IllegalStateException(e);}
    }

    @Test void unpinnedOrWrongWorldCannotLoadIndexOrModifyNativeDat()throws Exception{
        var w=world();var file=copy(w,WorldPrepSavedData.FILE_ID);
        var old=open(file);
        var index=withPinned(w,()->PagedSavedDataRuntime.prepareWrite(old,file));
        assertTrue(ImmutableBucketedNbtCheckpoint.isPaged(index));
        assertThrows(IllegalStateException.class,
            ()->WorldPrepSavedData.load(index.getCompound("data"),null));
        assertThrows(IllegalStateException.class,()->withPinned(w.resolve("data"),
            ()->PagedSavedDataRuntime.withStore(w,WorldPrepSavedData.FILE_ID,
                ()->WorldPrepSavedData.load(index.getCompound("data"),null))));
        var wrong=index.copy();
        wrong.getCompound("data").getCompound("__worldprep_bucket_index_v1")
            .putString("store","proutlost_worldprep_blocks");
        assertThrows(IllegalStateException.class,()->withPinned(w,
            ()->PagedSavedDataRuntime.withStore(w,WorldPrepSavedData.FILE_ID,
                ()->WorldPrepSavedData.load(wrong.getCompound("data"),null))));
        assertEquals(old,open(file),"No rejected load may replace the legacy root");
    }

    @Test void disabledModeKeepsAllOriginalLegacySavedData()throws Exception{
        var w=world();var file=copy(w,WorldPrepSavedData.FILE_ID);
        var before=open(file);
        assertFalse(ImmutableBucketedNbtCheckpoint.isPaged(before));
        assertEquals(before,PagedSavedDataRuntime.prepareWrite(before,file));
        assertEquals(33174L,WorldPrepSavedData.load(before.getCompound("data"),null)
            .jobs().get(ORIGINAL_BATCH).cursor());
        assertEquals(before,open(file));
    }

    private static String sha(Path p)throws Exception {
        var h=MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(p)){
            byte[] b=new byte[1024*1024];
            for(int n;(n=input.read(b))!=-1;)h.update(b,0,n);
        }
        return HexFormat.of().formatHex(h.digest());
    }
}
