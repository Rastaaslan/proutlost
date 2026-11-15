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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Optional public-API bridge. Never edits Director's implementation, registry or WAL.
 * The explicit export command obtains Director's own SavedData identity and writes an
 * import document. A successful codec check is NOT a live import or event-engine ACK.
 */
public final class DirectorStructureExportBridge {
    private DirectorStructureExportBridge() {}
    public record Result(UUID worldId,long revision,int locations,int anchors,String sha256,
                         boolean rewritten,String state,String path) {}
    private static final String IDENTITY="fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity";
    private static final String INPUT="worldprep-structures.json";
    private static final int MAX_CATALOG_ENTRIES=4096;

    /** A bounded scan must refuse overflow, never publish a truncated catalog. */
    static List<Path> boundedEntries(Stream<Path> entries,String scope)throws IOException {
        var paths=entries.limit((long)MAX_CATALOG_ENTRIES+1).toList();
        if(paths.size()>MAX_CATALOG_ENTRIES)
            throw new IOException("Native export "+scope+" scan bound exceeded; no partial export");
        return paths.stream().sorted().toList();
    }

    @SuppressWarnings("unchecked")
    private static UUID directorIdentity(ServerLevel level)throws ReflectiveOperationException,IOException {
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
    public static Result export(ServerLevel level)throws Exception {
        NativePhysicalRuntime.requireThread(level);
        fr.proutlost.worldprep.runtime.CandidateGuard.requireCandidate(level);
        if(!fr.proutlost.worldprep.WorldPrepConfig.STRUCTURES_ONLY.get()
                ||fr.proutlost.worldprep.WorldPrepConfig.ENABLED.get())
            throw new IOException("Explicit structures-only mode required");
        if(WorldPrepStructureModule.status(level).startsWith("RUNNING"))
            throw new IOException("Wait for the active placement before exporting the catalog");
        Path world=level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path root=world.resolve("worldprep-v2/structure-module");
        var records=new ArrayList<NativeVerifiedStructureCatalog.NativeLocationRecord>();
        var ids=new HashSet<UUID>();
        try(var batches=Files.list(root)) {
            for(Path batch:boundedEntries(batches,"batch")) {
                if(!Files.isDirectory(batch,LinkOption.NOFOLLOW_LINKS))continue;
                // Only completed module batches are exported. Partial placements remain explicit evidence.
                if(!Files.isRegularFile(batch.resolve("complete.json"),LinkOption.NOFOLLOW_LINKS))continue;
                new PhysicalStore(batch).readText("complete");
                try(var sites=Files.list(batch)) {
                    for(Path site:boundedEntries(sites.filter(p->p.getFileName().toString()
                            .matches("placement-[0-9]+")),"placement")) {
                        var store=new PhysicalStore(site);
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
        UUID id=directorIdentity(level);
        Path directory=world.resolve("director-location-imports");
        if(Files.isSymbolicLink(directory))throw new IOException("Import directory must not be a symbolic link");
        Files.createDirectories(directory);
        Path file=directory.resolve(INPUT);
        JsonObject previous=null;
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
            if(Files.isSymbolicLink(file)||Files.size(file)>1048576)throw new IOException("Unexpected previous G0 file");
            previous=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        }
        JsonObject value=next(id,List.copyOf(records),previous);
        String body=DirectorG0LocationExport.canonical(value);
        DirectorG0LocationExport.requireBoundedBytes(body);
        validateWithInstalledDirector(value);
        boolean changed=previous==null||!previous.equals(value);
        if(changed) {
            Path temporary=Files.createTempFile(directory,".worldprep-", ".partial");
            try {
                try(var out=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                    ByteBuffer bytes=ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8));
                    while(bytes.hasRemaining())out.write(bytes);
                    out.force(true);
                }
                Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                try(var dir=FileChannel.open(directory,StandardOpenOption.READ)){dir.force(true);}
            }finally{Files.deleteIfExists(temporary);}
        }
        return new Result(id,Long.parseLong(value.get("export_revision").getAsString()),
            value.getAsJsonArray("locations").size(),value.getAsJsonArray("anchors").size(),
            DirectorG0LocationExport.sha256(body),changed,"DIRECTOR_CODEC_AND_DIGEST_PASS_NOT_IMPORTED",file.toString());
    }
}

