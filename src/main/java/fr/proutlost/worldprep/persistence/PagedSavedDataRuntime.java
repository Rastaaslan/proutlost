package fr.proutlost.worldprep.persistence;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * DEV-only, exact-world opt-in adapter for vanilla SavedData factories.
 *
 * DimensionDataStorage calls SavedData.load(CompoundTag, Registries) with
 * only the inner "data" tag. A scoped owner-thread context supplies the
 * immutable, explicitly pinned .dat path to the byte-verified page expander.
 * No global default, no implicit upgrade of any legacy world, no context
 * leakage between dimensions or stores, and never an empty map on failure.
 *
 * Production WorldPrep remains legacy unless a separate crash-tested
 * migration and launcher have expressly opted in for the right world.
 */
public final class PagedSavedDataRuntime {
    public static final String MODE_KEY="proutlost.worldprep.paged.mode";
    public static final String WORLD_KEY="proutlost.worldprep.paged.world";
    public static final String DEV_MODE="CONTRA_RUN21_DEV_ONLY";
    public static final String RESTART_MODE="CONTRA_RUN35_SIGNED_COLD_EXACT_BATCH_RESUME";
    public static final String RUN46_MODE="CONTRA_RUN46_SIGNED_COLD_PARALLEL_RESUME";
    // Only separately certified, cold post-RUN55 continuation may use RUN90 delta metadata.
    public static final String RUN92_MODE="CONTRA_RUN92_SIGNED_COLD_INCREMENTAL_CONTINUATION";
    public static final String RUN92_MARKER="worldprep-run92-poststop-cold-authorization.json";
    public static final String RUN92_ADMISSION_FILE="CONTRA_RUN92_POSTSTOP_COLD_ADMISSION_20261029.json";
    public static final String MARKER="worldprep-run21-paged-authorization.json";
    public static final String RESTART_MARKER="worldprep-run35-paged-authorization.json";
    public static final String RUN46_MARKER="worldprep-run46-parallel-authorization.json";
    public static final Path ROOT=Path.of("/srv/storage/proutlost-s2-candidates/contra-20261027");
    public static final String ADMISSION_FILE="CONTRA_RUN35_COLD_SOURCE_ADMISSION_20261028.json";
    public static final String RUN46_ADMISSION_FILE="CONTRA_RUN46_COLD_ADMISSION_20261028.json";
    public static final String INDEX_MARKER="__worldprep_bucket_index_v1";

    private static final Set<String> STORES=Set.of(
        WorldPrepSavedData.FILE_ID,BlockWorldPrepData.FILE_ID);
    private record LoadContext(Path path,String store,Thread owner) {}
    private static final ThreadLocal<LoadContext> CONTEXT=new ThreadLocal<>();

    /** The system property exists only on the separately approved DEV launcher. */
    public static boolean configured(){
        String mode=System.getProperty(MODE_KEY,"");
        if(mode.isBlank())return false;
        if(!DEV_MODE.equals(mode)&&!RESTART_MODE.equals(mode)&&!RUN46_MODE.equals(mode)&&!RUN92_MODE.equals(mode))
            throw new IllegalStateException("Unsupported non-default paged runtime mode: "+mode);
        return true;
    }

    private static Path authoritativeWorld(Path candidate) throws IOException {
        if(!configured())
            throw new IOException("Paged metadata requires explicit DEV opt-in");
        String pinned=System.getProperty(WORLD_KEY,"");
        if(pinned.isBlank())
            throw new IOException("No DEV paged world pinned at JVM startup");
        Path expected=Path.of(pinned).toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path actual=candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if(!actual.equals(expected)
            ||!Files.isRegularFile(actual.resolve("level.dat"),LinkOption.NOFOLLOW_LINKS)
            ||Files.isSymbolicLink(actual.resolve("data")))
            throw new IOException("Nonmatching or unprepared DEV Minecraft world");
        String mode=System.getProperty(MODE_KEY);
        Path marker=actual.resolve(RUN92_MODE.equals(mode)?RUN92_MARKER:
            (RUN46_MODE.equals(mode)?RUN46_MARKER:
            (RESTART_MODE.equals(mode)?RESTART_MARKER:MARKER)));
        if(Files.isSymbolicLink(marker)||!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)
            ||Files.size(marker)>4096)
            throw new IOException("Missing bounded cold-world paged authorization");
        com.google.gson.JsonObject pinnedDoc;
        try{
            pinnedDoc=JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
        }catch(RuntimeException bad){throw new IOException("Malformed cold-world authorization",bad);}
        if(pinnedDoc.get("schemaVersion").getAsInt()!=1
            ||!mode.equals(pinnedDoc.get("mode").getAsString())
            ||!actual.toString().equals(pinnedDoc.get("world").getAsString())
            ||pinnedDoc.get("protectedOriginalsUnchanged")==null
            ||!pinnedDoc.get("protectedOriginalsUnchanged").getAsBoolean())
            throw new IOException("Wrong cold-world paged authorization marker");
        if(RESTART_MODE.equals(mode)){
            // Only the isolated, independently SNAPSHOT-CERTIFIED Contra
            // production-candidate path is eligible, never an arbitrary world.
            Path only=Path.of("/home/damien/contra-ecology-run35-paged/Contra-worldprep-modern-run35");
            if(!actual.equals(only))throw new IOException("Unexpected RUN35 world location");
            Path admission=ROOT.resolve(ADMISSION_FILE);
            if(Files.isSymbolicLink(admission)||!Files.isRegularFile(admission,LinkOption.NOFOLLOW_LINKS)
                ||Files.size(admission)>1048576)
                throw new IOException("No RUN35 cold admission evidence");
            String admissionHash=ImmutableBucketedNbtCheckpoint.fileSha256(admission);
            if(!pinnedDoc.get("coldAdmissionSha256").getAsString().equals(admissionHash))
                throw new IOException("RUN35 pinned cold admission SHA mismatch");
            var proof=JsonParser.parseString(Files.readString(admission)).getAsJsonObject();
            if(!"CONTRA_RUN35_EXACT_BATCH_COLD_DUPLICATE_ADMISSION_AND_TWO_BACKUPS_VERIFIED".equals(
                    proof.get("status").getAsString())
                ||!actual.toString().equals(proof.get("candidateWorld").getAsString())
                ||!"873e51c5-b528-4fb7-bc34-7e0221d6c59d".equals(
                    proof.get("originalBatch").getAsString()))
                throw new IOException("RUN35 exact cold batch source not independently certified");
        }
        if(RUN92_MODE.equals(mode)){
            Path only=Path.of("/home/damien/contra-ecology-run46-parallel/Contra-worldprep-modern-run46");
            if(!actual.equals(only))throw new IOException("RUN92 exact native world differs");
            Path admitted=ROOT.resolve(RUN92_ADMISSION_FILE);
            if(Files.isSymbolicLink(admitted)
               || !Files.isRegularFile(admitted,LinkOption.NOFOLLOW_LINKS)
               || Files.size(admitted)>1048576)
                throw new IOException("RUN92 unique cold admission missing");
            if(!ImmutableBucketedNbtCheckpoint.fileSha256(admitted)
                    .equals(pinnedDoc.get("coldAdmissionSha256").getAsString()))
                throw new IOException("RUN92 cold admission SHA differs from world marker");
            var proof=JsonParser.parseString(Files.readString(admitted)).getAsJsonObject();
            if(!"RUN92_POSTSTOP_COLD_RUN90_DELTA_ADMITTED_AFTER_RUN72_RUN78_RUN77"
                    .equals(proof.get("status").getAsString())
               || !actual.toString().equals(proof.get("candidateWorld").getAsString())
               || !"873e51c5-b528-4fb7-bc34-7e0221d6c59d"
                    .equals(proof.get("originalBatch").getAsString())
               || proof.get("savedParentCursor").getAsLong()!=pinnedDoc.get("savedParentCursor").getAsLong()
               || !proof.get("newABBackupProofSha256").getAsString().matches("[0-9a-f]{64}")
               || !proof.get("compiledProofSHA256").getAsString().matches("[0-9a-f]{64}"))
                throw new IOException("RUN92 exact original UUID, cold metadata or preserved cursor mismatch");
        }
        if(RUN46_MODE.equals(mode)){
            Path only=Path.of("/home/damien/contra-ecology-run46-parallel/Contra-worldprep-modern-run46");
            if(!actual.equals(only))throw new IOException("RUN46 unexpected world location");
            Path admission=ROOT.resolve(RUN46_ADMISSION_FILE);
            if(Files.isSymbolicLink(admission)||!Files.isRegularFile(admission,LinkOption.NOFOLLOW_LINKS)
                ||Files.size(admission)>1048576)
                throw new IOException("RUN46 missing cold admission");
            String admissionHash=ImmutableBucketedNbtCheckpoint.fileSha256(admission);
            if(!admissionHash.equals(pinnedDoc.get("coldAdmissionSha256").getAsString()))
                throw new IOException("RUN46 cold admission SHA mismatch");
            var proof=JsonParser.parseString(Files.readString(admission)).getAsJsonObject();
            if(!"RUN46_COLD_PARALLEL_CODE_AND_TWO_BACKUPS_SHA_ADMITTED".equals(proof.get("status").getAsString())
                ||!actual.toString().equals(proof.get("candidateWorld").getAsString())
                ||!"873e51c5-b528-4fb7-bc34-7e0221d6c59d".equals(proof.get("originalBatch").getAsString())
                ||proof.get("savedParentCursor").getAsLong()!=pinnedDoc.get("savedParentCursor").getAsLong())
                throw new IOException("RUN46 original UUID or cold state not certified");
        }
        return actual;
    }

    private static Path target(Path world,String store) throws IOException {
        if(!STORES.contains(store))throw new IOException("Unknown WorldPrep SavedData ID");
        Path p=world.resolve("data").resolve(store+".dat");
        if(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Cold-original metadata data file must preexist");
        return p.toAbsolutePath().normalize();
    }

    public static <T> T withStore(Path pinnedWorld,String store,Supplier<T> loader) {
        Objects.requireNonNull(loader);
        if(!configured())return loader.get();
        LoadContext previous=CONTEXT.get();
        try{
            Path p=target(authoritativeWorld(pinnedWorld),store);
            if(previous!=null)
                throw new IllegalStateException("Unexpected nested WorldPrep page-load scope");
            CONTEXT.set(new LoadContext(p,store,Thread.currentThread()));
            return loader.get();
        }catch(IOException e){
            throw new IllegalStateException("WorldPrep page-load world identity refused",e);
        }finally{
            if(previous==null)CONTEXT.remove();
            else CONTEXT.set(previous);
        }
    }

    public static <T> T withServerLevel(ServerLevel level,String store,Supplier<T> loader) {
        Objects.requireNonNull(level);
        if(!configured()||!level.dimension().equals(Level.OVERWORLD))return loader.get();
        return withStore(level.getServer().getWorldPath(LevelResource.ROOT),store,loader);
    }

    /** Only called from the Minecraft native Factory.load callbacks. */
    public static CompoundTag expandLoad(CompoundTag nativeData,String store){
        if(!nativeData.contains(INDEX_MARKER,Tag.TAG_COMPOUND))
            return nativeData;
        if(!configured())
            throw new IllegalStateException("Paged metadata found but loader is disabled; REFUSE all empty fallback");
        var scope=CONTEXT.get();
        if(scope==null||scope.owner()!=Thread.currentThread()||!scope.store().equals(store))
            throw new IllegalStateException("No exact owner-thread scope for paged store "+store);
        var wrapped=new CompoundTag();
        wrapped.put("data",nativeData);
        try{
            // Every hash is re-read after restart; no cached file contents are
            // trusted as authentication of the reconstructed NBT.
            var full=ImmutableBucketedNbtCheckpoint.expand(wrapped,scope.path(),store);
            return full.getCompound("data");
        }catch(IOException e){
            throw new IllegalStateException("Corrupt/missing paged WorldPrep data REFUSED: "+store,e);
        }
    }

    /** Called only by AcknowledgedSavedData's existing fail-closed writer. */
    public static CompoundTag prepareWrite(CompoundTag captured,Path file)throws IOException{
        if(!configured())return captured;
        Path realFile=file.toAbsolutePath().normalize();
        String filename=realFile.getFileName().toString();
        if(!filename.endsWith(".dat"))throw new IOException("Unexpected checkpoint filename");
        String store=filename.substring(0,filename.length()-4);
        Path world=realFile.getParent().getParent();
        Path expected=target(authoritativeWorld(world),store);
        if(!expected.equals(realFile))throw new IOException("Cross-world SavedData output refused");
        if(!captured.contains("data",Tag.TAG_COMPOUND))
            throw new IOException("Missing metadata envelope");
        // Pages are durably published BEFORE AcknowledgedSavedData publishes
        // the small root. A failure keeps the older root authoritative.
        if(captured.getCompound("data").contains("__run90_incremental_v1",Tag.TAG_COMPOUND)){
            if(!Boolean.getBoolean("proutlost.run90.strictIncrementalMetadata")
                    || !Set.of(DEV_MODE,RUN92_MODE).contains(System.getProperty(MODE_KEY))
                    ||!"proutlost_worldprep".equals(store))
                throw new IOException("RUN90 sparse write lacks explicit isolated opt-in");
            return ImmutableBucketedNbtCheckpoint.externalizeRun90Delta(
                    captured,realFile,store).compactRoot();
        }
        if(captured.getCompound("data").contains(BlockWorldPrepData.BLOCK_DELTA_MARKER,Tag.TAG_COMPOUND)) {
            if(!Boolean.getBoolean(BlockWorldPrepData.BLOCK_DELTA_FLAG)
                    ||!Set.of(DEV_MODE,RUN92_MODE).contains(System.getProperty(MODE_KEY))
                    ||!BlockWorldPrepData.FILE_ID.equals(store))
                throw new IOException("Block delta lacks exact cold opt-in/store");
            return ImmutableBucketedNbtCheckpoint.externalizeBlockDelta(captured,realFile,store).compactRoot();
        }
        return ImmutableBucketedNbtCheckpoint.externalize(captured,realFile,store).compactRoot();
    }

    private PagedSavedDataRuntime(){}
}
