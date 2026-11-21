package fr.proutlost.worldprep.physical;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.concurrent.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Optional public-API bridge. Never edits Director's implementation, registry or WAL.
 * The explicit export command obtains Director's own SavedData identity and writes an
 * import document or immutable paged message directory. Export recovery state
 * is producer-only. A codec/export receipt is NOT a native J04 or event-engine ACK.
 */
public final class DirectorStructureExportBridge {
    private DirectorStructureExportBridge() {}
    public record Result(UUID worldId,long revision,int locations,int anchors,String sha256,
                         boolean rewritten,String state,String path) {}
    private static final String IDENTITY="fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity";
    private static final String INPUT="worldprep-structures.json";
    private static final int MAX_CATALOG_ENTRIES=4096;
    private static final String EXPORT_STATE="worldprep-structures-export-current.json";
    private static final ExecutorService EXPORT_IO=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2),r->{var t=new Thread(r,"worldprep-director-export-io");t.setDaemon(true);return t;},
        new ThreadPoolExecutor.AbortPolicy());
    private record Captured(UUID worldId,Path directory,Path moduleRoot,String moduleStatus) {}

    /** A bounded scan must refuse overflow, never publish a truncated catalog. */
    static List<Path> boundedEntries(Stream<Path> entries,String scope)throws IOException {
        var paths=entries.limit((long)MAX_CATALOG_ENTRIES+1).toList();
        if(paths.size()>MAX_CATALOG_ENTRIES)
            throw new IOException("Native export "+scope+" scan bound exceeded; no partial export");
        return paths.stream().sorted().toList();
    }

    @SuppressWarnings("unchecked")
    static UUID directorIdentity(ServerLevel level)throws ReflectiveOperationException,IOException {
        Class<?> type=Class.forName(IDENTITY);
        var factory=(SavedData.Factory<SavedData>)type.getField("FACTORY").get(null);
        String key=(String)type.getField("KEY").get(null);
        var data=level.getServer().overworld().getDataStorage();
        var identity=data.computeIfAbsent(factory,key);
        var world=type.getMethod("worldId").invoke(identity);
        UUID id=(UUID)world.getClass().getMethod("value").invoke(world);
        data.save();
        return id;
    }
    /** Validates the exact installed Director codec and canonical digest, without submitting an import. */
    static void validateWithInstalledDirector(JsonObject value)throws ReflectiveOperationException,IOException {
        Class<?> model=Class.forName("fr.proutlost.director.api.model.LocationImport");
        Class<?> codecType=Class.forName("fr.proutlost.director.api.codec.ModelCodec");
        Object codec=codecType.getConstructor(Class.class).newInstance(model);
        byte[] bytes=DirectorG0LocationExport.canonical(value).getBytes(StandardCharsets.UTF_8);
        Object input=codecType.getMethod("decode",byte[].class).invoke(codec,(Object)bytes);
        Class<?> importer=Class.forName("fr.proutlost.director.integration.locations.StandaloneLocationImporter");
        // The codec accepts larger documents than the native J04 ingress. Validate
        // the installed consumer's unchanged envelope before touching the prior file.
        try { importer.getMethod("requireNativeEnvelope",model).invoke(null,input); }
        catch(java.lang.reflect.InvocationTargetException refused) {
            throw new IOException("Installed native G0 envelope refused; previous export retained",refused.getCause());
        }
        Object digest=importer.getMethod("digest",model).invoke(null,input);
        Object hex=digest.getClass().getMethod("value").invoke(digest);
        if(!value.get("export_digest").getAsString().equals(hex))
            throw new IOException("Installed Director canonical digest mismatch");
    }
    static JsonObject next(UUID world,List<NativeVerifiedStructureCatalog.NativeLocationRecord> records,
                           JsonObject previous)throws IOException {
        long revision=1L;
        if(previous!=null) {
            if(!world.toString().equals(previous.get("world_id").getAsString())
                    ||!DirectorG0LocationExport.SOURCE_NAMESPACE.equals(previous.get("source_namespace").getAsString()))
                throw new IOException("Previous export world/source changed");
            var body=previous.deepCopy();String digest=body.remove("export_digest").getAsString();
            if(!DirectorG0LocationExport.sha256(DirectorG0LocationExport.canonical(body)).equals(digest))
                throw new IOException("Previous export digest changed");
            revision=Long.parseLong(previous.get("export_revision").getAsString());
            var same=DirectorG0LocationExport.build(world,revision,records);
            if(same.equals(previous))return same;
            revision=Math.addExact(revision,1L);
        }
        return DirectorG0LocationExport.build(world,revision,records);
    }
    private static String requireExportAllowed(ServerLevel level)throws IOException {
        NativePhysicalRuntime.requireThread(level);
        fr.proutlost.worldprep.runtime.CandidateGuard.requireCandidate(level);
        if(!fr.proutlost.worldprep.WorldPrepConfig.STRUCTURES_ONLY.get()
                ||fr.proutlost.worldprep.WorldPrepConfig.ENABLED.get())
            throw new IOException("Explicit structures-only mode required");
        String status=WorldPrepStructureModule.status(level);
        if(status.startsWith("RUNNING"))throw new IOException("Wait for the active placement before exporting the catalog");
        return status;
    }
    private static Captured capture(ServerLevel level)throws Exception {
        String status=requireExportAllowed(level);
        Path world=level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        // Director identity/SavedData remain actor-owned. No catalogue Files
        // traversal or physical receipt hydration runs in this capture step.
        UUID id=directorIdentity(level);
        return new Captured(id,world.resolve("director-location-imports"),world.resolve("worldprep-v2/structure-module"),status);
    }
    /** IO-only scan of immutable, checksum-bound completed physical receipts. */
    static List<NativeVerifiedStructureCatalog.NativeLocationRecord> collectRecords(Path root)throws IOException {
        noLinks(root);
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Existing structure-module receipt directory required");
        var records=new ArrayList<NativeVerifiedStructureCatalog.NativeLocationRecord>();
        var ids=new HashSet<UUID>();
        try(var batches=Files.list(root)) {
            for(Path batch:boundedEntries(batches,"batch")) {
                if(!Files.isDirectory(batch,LinkOption.NOFOLLOW_LINKS))continue;
                // Only completed module batches are exported. Partial placements remain explicit evidence.
                if(!Files.isRegularFile(batch.resolve("complete.json"),LinkOption.NOFOLLOW_LINKS))continue;
                PhysicalStore.openReadOnly(batch).readText("complete");
                try(var sites=Files.list(batch)) {
                    for(Path site:boundedEntries(sites.filter(p->p.getFileName().toString()
                            .matches("placement-[0-9]+")),"placement")) {
                        var store=PhysicalStore.openReadOnly(site);
                        if(store.exists("rollback-intent"))continue;
                        if(!store.exists("director-native-location-candidate"))continue;
                        if(records.size()>=MAX_CATALOG_ENTRIES)throw new IOException("Native export catalog bound");
                        var record=store.read("director-native-location-candidate",
                            NativeVerifiedStructureCatalog.NativeLocationRecord.class);
                        var disk=store.read("native-disk-verified",NativeRegistryStructureAudit.Receipt.class);
                        if(!record.operation().equals(disk.operation())
                                ||!record.structureRegistryId().equals(disk.registryId())
                                ||!record.diskReceiptSha256().equals(PhysicalStore.sha(store.readText("native-disk-verified")))
                                ||!record.generationPlanSha256().equals(PhysicalStore.sha(store.readText("native-plan")))
                                ||!"PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED".equals(disk.status()))
                            throw new IOException("Native export physical receipt binding changed");
                        if(!ids.add(record.locationId()))throw new IOException("Duplicate native location ID");
                        records.add(record);
                    }
                }
            }
        }
        if(records.isEmpty())throw new IOException("No completed physical structures to export");
        return List.copyOf(records);
    }
    /** The command captures native identity on its actor, then delegates the
     * bounded receipt scan, codec checks, immutable message publication and fsync to IO.
     * Completion is an export receipt only; it never submits to Director.
     */
    public static CompletionStage<Result> exportAsync(ServerLevel level)throws Exception {
        var captured=capture(level);var server=level.getServer();
        return CompletableFuture.supplyAsync(()->{
            try{return collectRecords(captured.moduleRoot());}
            catch(IOException error){throw new CompletionException(error);}
        },EXPORT_IO).thenCompose(records->{
            var allowed=new CompletableFuture<List<NativeVerifiedStructureCatalog.NativeLocationRecord>>();
            // Never wait on the actor or IO owner. A new/changed placement
            // session during the scan invalidates this complete candidate.
            try{server.execute(()->{
                try{
                    String status=requireExportAllowed(level);
                    Path world=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                    if(!captured.directory().getParent().equals(world)||!status.equals(captured.moduleStatus()))
                        throw new IOException("Structure module/world changed during export scan; no publication");
                    allowed.complete(records);
                }catch(Exception error){allowed.completeExceptionally(error);}
            });}catch(RuntimeException unavailable){allowed.completeExceptionally(unavailable);}
            return allowed;
        }).thenCompose(records->CompletableFuture.supplyAsync(()->{
            try{return publish(captured.worldId(),captured.directory(),records);}
            catch(Exception error){throw new CompletionException(error);}
        },EXPORT_IO));
    }
    /** Synchronous compatibility API. Native command integrations use exportAsync. */
    public static Result export(ServerLevel level)throws Exception {
        var captured=capture(level);
        return publish(captured.worldId(),captured.directory(),collectRecords(captured.moduleRoot()));
    }
    /** Closed installed-codec/digest check for the complete source. It does not
     * relax requireNativeEnvelope: large sources must use the message directory.
     */
    private static Object installedSource(JsonObject value)throws ReflectiveOperationException,IOException {
        Class<?> model=Class.forName("fr.proutlost.director.api.model.LocationImport");
        Class<?> codecType=Class.forName("fr.proutlost.director.api.codec.ModelCodec");
        Object codec=codecType.getConstructor(Class.class).newInstance(model);
        byte[] bytes=DirectorG0LocationExport.canonical(value).getBytes(StandardCharsets.UTF_8);
        DirectorG0LocationExport.requireBoundedBytes(new String(bytes,StandardCharsets.UTF_8));
        Object input=codecType.getMethod("decode",byte[].class).invoke(codec,(Object)bytes);
        byte[] roundtrip=(byte[])codecType.getMethod("encode",Object.class).invoke(codec,input);
        if(!Arrays.equals(bytes,roundtrip))throw new IOException("Installed Director canonical source differs");
        Class<?> importer=Class.forName("fr.proutlost.director.integration.locations.StandaloneLocationImporter");
        Object digest=importer.getMethod("digest",model).invoke(null,input);
        if(!value.get("export_digest").getAsString().equals(digest.getClass().getMethod("value").invoke(digest)))
            throw new IOException("Installed Director canonical digest mismatch");
        return input;
    }
    /** One exporter IO owner calls this method. The frozen export pointer is
     * internal producer recovery state, explicitly not a native import receipt.
     */
    static Result publish(UUID id,Path directory,List<NativeVerifiedStructureCatalog.NativeLocationRecord> records)throws Exception {
        directory=directory.toAbsolutePath().normalize();noLinks(directory);Files.createDirectories(directory);
        Path lockFile=directory.resolve(".worldprep-structure-export.lock");noLinks(lockFile);
        try(var channel=FileChannel.open(lockFile,StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock lock;
            try{lock=channel.tryLock();}catch(java.nio.channels.OverlappingFileLockException busy){throw new IOException("Producer export already owned",busy);}
            if(lock==null)throw new IOException("Producer export already owned");
            try(lock){return publishOwned(id,directory,records);}
        }
    }
    private static Result publishOwned(UUID id,Path directory,List<NativeVerifiedStructureCatalog.NativeLocationRecord> records)throws Exception {
        JsonObject previous=previous(directory);
        JsonObject value=next(id,records,previous);
        String body=DirectorG0LocationExport.canonical(value);
        DirectorG0LocationExport.requireBoundedBytes(body);
        Object input=installedSource(value);
        long revision=Long.parseLong(value.get("export_revision").getAsString());
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        long entries=(long)value.getAsJsonArray("regions").size()+value.getAsJsonArray("locations").size()+value.getAsJsonArray("anchors").size();
        boolean small=bytes.length<=32768&&entries<=96;
        if(small)validateWithInstalledDirector(value);
        // Validate the installed paged API before changing any producer pointer.
        Class<?> transferFiles=small?null:Class.forName("fr.proutlost.director.integration.locations.LocationTransferFiles");
        Class<?> model=input.getClass();
        var publisher=small?null:transferFiles.getMethod("writeImmutable",Path.class,model);
        String sourceHash=DirectorG0LocationExport.sha256(body);
        Path archive=directory.resolve("worldprep-structures-r"+revision+"-"+sourceHash+".g0");
        immutable(archive,bytes);
        var state=new JsonObject();state.addProperty("schema_version",1);
        state.addProperty("scope","FROZEN_EXPORT_ONLY_NOT_NATIVE_IMPORT");
        state.addProperty("source_archive",archive.getFileName().toString());
        state.addProperty("source_sha256",sourceHash);
        // This reserves an immutable export revision before publishing its
        // transport. Reopening after a crash can finish these exact same bytes.
        atomic(directory.resolve(EXPORT_STATE),DirectorG0LocationExport.canonical(state).getBytes(StandardCharsets.UTF_8));
        Path target;
        boolean changed=previous==null||!previous.equals(value);
        String status;
        if(small){
            target=directory.resolve(INPUT);atomic(target,bytes);
            status="DIRECTOR_CODEC_AND_DIGEST_PASS_NOT_IMPORTED";
        }else{
            String name="worldprep-r"+revision+"-"+sourceHash.substring(0,16)+"_transfer";
            target=directory.resolve(name);
            try{publisher.invoke(null,target,input);}
            catch(java.lang.reflect.InvocationTargetException error){throw new IOException("Paged Director export refused; prior transport retained",error.getCause());}
            status="DIRECTOR_PAGED_TRANSPORT_READY_NOT_IMPORTED";
        }
        return new Result(id,revision,value.getAsJsonArray("locations").size(),
            value.getAsJsonArray("anchors").size(),sourceHash,changed,status,target.toString());
    }
    private static JsonObject previous(Path directory)throws IOException {
        Path state=directory.resolve(EXPORT_STATE);
        if(!Files.exists(state,LinkOption.NOFOLLOW_LINKS)) {
            Path legacy=directory.resolve(INPUT);
            return Files.exists(legacy,LinkOption.NOFOLLOW_LINKS)?json(legacy,1048576):null;
        }
        var pointer=json(state,8192);
        if(!pointer.keySet().equals(Set.of("schema_version","scope","source_archive","source_sha256"))
                ||pointer.get("schema_version").getAsInt()!=1
                ||!pointer.get("scope").getAsString().equals("FROZEN_EXPORT_ONLY_NOT_NATIVE_IMPORT"))
            throw new IOException("Invalid producer export recovery pointer");
        String name=pointer.get("source_archive").getAsString(),hash=pointer.get("source_sha256").getAsString();
        if(!name.matches("worldprep-structures-r[0-9]+-[a-f0-9]{64}\\.g0")||!hash.matches("[a-f0-9]{64}"))
            throw new IOException("Invalid export archive reference");
        byte[] archived=bytes(directory.resolve(name),1048576);
        JsonObject value;
        try{value=JsonParser.parseString(new String(archived,StandardCharsets.UTF_8)).getAsJsonObject();}
        catch(RuntimeException bad){throw new IOException("Invalid frozen export source",bad);}
        String canonical=DirectorG0LocationExport.canonical(value);
        if(!Arrays.equals(archived,canonical.getBytes(StandardCharsets.UTF_8))
                ||!DirectorG0LocationExport.sha256(canonical).equals(hash))
            throw new IOException("Frozen export archive digest/bytes changed");
        return value;
    }
    private static JsonObject json(Path path,int max)throws IOException {
        try{return JsonParser.parseString(new String(bytes(path,max),StandardCharsets.UTF_8)).getAsJsonObject();}
        catch(RuntimeException bad){throw new IOException("Invalid producer export JSON",bad);}
    }
    private static byte[] bytes(Path path,int max)throws IOException {
        noLinks(path);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>max)
            throw new IOException("Unexpected producer export file");
        byte[] bytes;try(var in=Files.newInputStream(path)){bytes=in.readNBytes(max+1);}
        if(bytes.length>max)throw new IOException("Producer export file grew above bound");
        return bytes;
    }
    private static void noLinks(Path path)throws IOException {
        for(var p=path.toAbsolutePath().normalize();p!=null;p=p.getParent())
            if(Files.isSymbolicLink(p))throw new IOException("Symlink export path refused");
    }
    private static void immutable(Path target,byte[] bytes)throws IOException {
        noLinks(target);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)||Files.size(target)!=bytes.length
                    ||!Arrays.equals(Files.readAllBytes(target),bytes))
                throw new IOException("Immutable producer export differs; retained");
            return;
        }
        write(target,bytes,false);
    }
    private static void atomic(Path target,byte[] bytes)throws IOException {write(target,bytes,true);}
    private static void write(Path target,byte[] bytes,boolean replace)throws IOException {
        noLinks(target);Path temp=Files.createTempFile(target.getParent(),".worldprep-export-",".partial");
        try {
            try(var out=FileChannel.open(temp,StandardOpenOption.WRITE)) {
                var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);out.force(true);
            }
            if(replace)Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
            try(var dir=FileChannel.open(target.getParent(),StandardOpenOption.READ)){dir.force(true);}
        }finally{Files.deleteIfExists(temp);}
    }
}
