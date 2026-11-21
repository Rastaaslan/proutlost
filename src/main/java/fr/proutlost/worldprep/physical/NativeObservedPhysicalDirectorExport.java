package fr.proutlost.worldprep.physical;

import com.google.gson.*;
import fr.proutlost.contract.director.CatalogSnapshot;
import fr.proutlost.contract.director.StrictCodec;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.director.ObservedPhysicalCatalogProducer;
import fr.proutlost.worldprep.director.ObservedPhysicalG0Projection;
import fr.proutlost.worldprep.runtime.CandidateGuard;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** OP4 producer bridge: sealed observed D0 -> explicitly geometry-only G0.
 * No admission/J04 writes, terrain changes, author semantics or event authority. */
public final class NativeObservedPhysicalDirectorExport {
    private static final Gson JSON=new Gson();
    private static final ModelCodec<LocationImport> G0=new ModelCodec<>(LocationImport.class);
    private static final String STATE="observed-physical-export-current.json";
    private static final String STATUS="GEOMETRY_G0_TRANSPORT_READY_NOT_IMPORTED";
    private static final ExecutorService IO=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),
        task->{var t=new Thread(task,"worldprep-observed-director-export-io");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private record Captured(Path world,UUID directorWorld,long seed,String markerSha,ObservedPhysicalCatalogProducer.Scope scope,UUID survey){}
    public record Receipt(int version,UUID survey,UUID directorWorld,String sourceWorld,String sourceBuild,String catalogRevision,
            String catalogSha256,String catalogDigest,String requestSha256,String witnessSha256,String markerSha256,
            long g0Revision,String g0Sha256,String g0Digest,String transferName,int regions,int locations,int anchors,
            String mappingSha256,String status){}
    public record Result(UUID survey,long revision,int regions,int locations,String sha256,String transfer,String status){}
    private NativeObservedPhysicalDirectorExport(){}
    private static void require(boolean yes,String why)throws IOException{if(!yes)throw new IOException(why);}
    private static void noLinks(Path path)throws IOException{for(var p=path.toAbsolutePath().normalize();p!=null;p=p.getParent())require(!Files.isSymbolicLink(p),"symlinked geometry export path");}
    private static byte[] read(Path path,int bound)throws IOException{
        noLinks(path);require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path)>0&&Files.size(path)<=bound,"geometry export file missing/type/size");
        try(var in=Files.newInputStream(path)){byte[] bytes=in.readNBytes(bound+1);require(bytes.length<=bound,"geometry export file grew");return bytes;}
    }
    private static Captured capture(ServerLevel level,UUID survey)throws Exception{
        NativePhysicalRuntime.requireThread(level);CandidateGuard.requireCandidate(level);
        require(!WorldPrepConfig.ENABLED.get()&&!NativePhysicalRuntime.hasActive(level)&&!NativeQualificationRuntime.active(level)
            &&!NativeStructureReconciliation.active(level)&&!WorldPrepStructureModule.status(level).startsWith("RUNNING")
            &&!WorldPrepFeatureModule.active(level)&&!WorldPrepFaunaModule.active(level),"pause world preparation before observed G0 export");
        Path world=level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();byte[] marker=read(world.resolve("worldprep-candidate.json"),4096);
        var object=JsonParser.parseString(new String(marker,StandardCharsets.UTF_8)).getAsJsonObject();
        var scope=ObservedPhysicalCatalogProducer.Scope.valueOf(object.get("physicalSurveyScope").getAsString());
        return new Captured(world,DirectorStructureExportBridge.directorIdentity(level),level.getSeed(),BlobContent.hash(marker).value(),scope,Objects.requireNonNull(survey));
    }
    public static CompletionStage<Result> exportAsync(ServerLevel level,UUID survey)throws Exception{
        var captured=capture(level,survey);var server=level.getServer();
        // The immutable physical snapshot is read on IO. Capture and final native
        // identity/marker fence remain actor-owned; no actor waits on IO.
        return CompletableFuture.supplyAsync(()->{
            try{return publish(captured);}catch(Exception error){throw new CompletionException(error);}
        },IO).thenCompose(result->{
            var finished=new CompletableFuture<Result>();
            try{server.execute(()->{try{
                var actual=capture(level,survey);require(actual.equals(captured),"native world/marker identity changed during export; transport retained, no import occurred");finished.complete(result);
            }catch(Exception error){finished.completeExceptionally(error);}});}catch(RuntimeException error){finished.completeExceptionally(error);}
            return finished;
        });
    }
    private static Result publish(Captured owner)throws Exception{
        Path exports=owner.world().resolve("director-location-imports");noLinks(exports);Files.createDirectories(exports);
        Path lockFile=exports.resolve(".observed-physical-export.lock");noLinks(lockFile);
        try(var channel=FileChannel.open(lockFile,StandardOpenOption.CREATE,StandardOpenOption.WRITE)){
            java.nio.channels.FileLock lock;
            try{lock=channel.tryLock();}catch(java.nio.channels.OverlappingFileLockException busy){throw new IOException("observed G0 export already owned",busy);}
            require(lock!=null,"observed G0 export already owned");try(lock){return publishOwned(owner,exports);}
        }
    }
    private static Result publishOwned(Captured owner,Path exports)throws Exception{
        Path directory=owner.world().resolve("worldprep-surveys/exports").resolve(owner.survey().toString());
        var evidence=PhysicalStore.openReadOnly(directory.resolve("evidence"));
        var receipt=evidence.read("receipt",NativeObservedPhysicalCatalogExport.Receipt.class);
        String requestText=evidence.read("request",String.class);var request=ObservedPhysicalCatalogProducer.decode(requestText.getBytes(StandardCharsets.UTF_8));
        require(receipt.version()==1&&receipt.survey().equals(owner.survey())&&request.survey().equals(owner.survey())
            &&receipt.status().equals("PHYSICAL_GEOMETRY_ONLY_D0_SEMANTICS_UNAUTHORED_NOT_IMPORTED")
            &&receipt.requestSha256().equals(PhysicalStore.sha(requestText))&&receipt.candidateMarkerSha256().equals(owner.markerSha())
            &&request.candidateMarkerSha256().value().equals(owner.markerSha())&&request.scope()==owner.scope()&&request.expectedSeed()==owner.seed()
            &&receipt.scope().equals(owner.scope().name())&&receipt.targets()==request.targets().size()
            &&receipt.cells()==request.targets().stream().mapToLong(t->ObservedPhysicalCatalogProducer.volume(t.geometry())).sum(),"observed physical source receipt/request authority mismatch");
        require(PhysicalStore.sha(evidence.readText("witnesses")).equals(receipt.witnessesSha256()),"observed physical witness bytes changed");
        byte[] catalogBytes=read(directory.resolve("catalog.json"),fr.proutlost.contract.director.Limits.MAX_JSON_BYTES);
        String catalogText=new String(catalogBytes,StandardCharsets.UTF_8),catalogSha=BlobContent.hash(catalogBytes).value();
        require(catalogSha.equals(receipt.catalogSha256()),"observed physical catalogue bytes changed");
        var witnessed=evidence.read("witnesses",ObservedPhysicalCatalogProducer.Witness[].class);
        var capturedAt=java.time.Instant.parse(evidence.read("capture-time",String.class));
        require(capturedAt.toString().equals(receipt.capturedAt()),"physical capture time differs from receipt");
        var replayed=ObservedPhysicalCatalogProducer.export(request,new fr.proutlost.contract.director.Digest(PhysicalStore.sha(requestText)),capturedAt,List.of(witnessed));
        require(replayed.json()!=null&&replayed.json().equals(catalogText),"physical request/witnesses do not reproduce sealed catalogue bytes");
        var catalog=StrictCodec.decode(catalogText,CatalogSnapshot.class);
        require(StrictCodec.encode(catalog).equals(catalogText)&&catalog.world_id().equals(request.sourceWorld())&&catalog.build_id().equals(request.sourceBuild())
            &&catalog.catalog_revision().equals(request.revision()),"observed physical catalogue canonical source mismatch");
        Map<fr.proutlost.contract.director.Id,ObservedPhysicalCatalogProducer.Target> targets=new HashMap<>();for(var target:request.targets())targets.put(target.location(),target);
        require(catalog.entries().size()==targets.size(),"observed geometry export target coverage");
        for(var entry:catalog.entries())require(targets.containsKey(entry.location_id())&&entry.geometry().equals(targets.get(entry.location_id()).geometry()),"observed geometry differs from sealed request");
        var proof=new PhysicalStore(directory.resolve("g0-projection"));
        Path state=exports.resolve(STATE);JsonObject previousState=null;LocationImport previousSource=null;
        long currentRevision=0;String currentSha=null;
        if(Files.exists(state,LinkOption.NOFOLLOW_LINKS)){
            previousState=JsonParser.parseString(new String(read(state,8192),StandardCharsets.UTF_8)).getAsJsonObject();
            require(previousState.keySet().equals(Set.of("version","world","source_world","revision","catalog_sha256","g0_sha256","archive","survey","phase"))
                &&previousState.get("version").getAsInt()==1&&previousState.get("world").getAsString().equals(owner.directorWorld().toString())
                &&previousState.get("source_world").getAsString().equals(catalog.world_id().value()),"observed source namespace is pinned to its original physical world");
            String phase=previousState.get("phase").getAsString();require(Set.of("FROZEN_EXPORT","COMPLETE_EXPORT").contains(phase),"observed export phase");
            if(phase.equals("FROZEN_EXPORT"))require(previousState.get("survey").getAsString().equals(owner.survey().toString())
                &&previousState.get("catalog_sha256").getAsString().equals(catalogSha),"pending observed export retained; resume survey "+previousState.get("survey").getAsString()+" before another export");
            String archive=previousState.get("archive").getAsString();require(archive.matches("observed-physical-r[0-9]+-[0-9a-f]{64}\\.g0"),"observed source archive path");
            byte[] raw=read(exports.resolve(archive),1_048_576);previousSource=G0.decode(raw);
            require(BlobContent.hash(raw).value().equals(previousState.get("g0_sha256").getAsString())&&Arrays.equals(raw,G0.encode(previousSource))
                &&previousSource.worldId().value().equals(owner.directorWorld())&&previousSource.sourceNamespace().equals(ObservedPhysicalG0Projection.SOURCE_NAMESPACE)
                &&previousSource.exportRevision().value()==previousState.get("revision").getAsLong(),"observed producer state/archive mismatch");
            currentRevision=previousSource.exportRevision().value();currentSha=previousState.get("g0_sha256").getAsString();
        }
        if(proof.exists("receipt")){
            var retained=proof.read("receipt",Receipt.class);require(retained.version()==1&&retained.survey().equals(owner.survey())&&retained.directorWorld().equals(owner.directorWorld())
                &&retained.catalogSha256().equals(catalogSha)&&retained.status().equals(STATUS),"retained G0 projection binding changed");
            var projected=ObservedPhysicalG0Projection.project(owner.directorWorld(),retained.g0Revision(),catalog,catalogSha);
            String expectedSha=BlobContent.hash(G0.encode(projected.input())).value();
            String expectedTransfer="observed-r"+retained.g0Revision()+"-"+expectedSha.substring(0,16)+"_transfer";
            String expectedMapping=proof.write("mapping",mappingProof(projected));
            var expectedReceipt=new Receipt(1,owner.survey(),owner.directorWorld(),catalog.world_id().value(),catalog.build_id().value(),catalog.catalog_revision().value(),catalogSha,catalog.digest().value(),
                receipt.requestSha256(),receipt.witnessesSha256(),owner.markerSha(),retained.g0Revision(),expectedSha,projected.input().exportDigest().value(),expectedTransfer,
                projected.input().regions().size(),projected.input().locations().size(),projected.input().anchors().size(),expectedMapping,STATUS);
            require(retained.equals(expectedReceipt),"retained G0 projection bytes/mapping/derived receipt changed");
            // Resolve only a computed local filename, never a receipt-supplied path.
            require(currentRevision>=retained.g0Revision()&&(currentRevision!=retained.g0Revision()||Objects.equals(currentSha,expectedSha)),"retained observed source state conflict");
            publishTransport(exports.resolve(expectedTransfer),projected.input());
            if(previousState.get("phase").getAsString().equals("FROZEN_EXPORT")){
                previousState.addProperty("phase","COMPLETE_EXPORT");atomic(state,DirectorG0LocationExport.canonical(previousState).getBytes(StandardCharsets.UTF_8));
            }
            return new Result(owner.survey(),retained.g0Revision(),retained.regions(),retained.locations(),expectedSha,expectedTransfer,"RETAINED_GEOMETRY_TRANSPORT_NOT_CURRENT_SOURCE_OR_IMPORT");
        }
        Receipt reserved=proof.exists("plan")?proof.read("plan",Receipt.class):null;
        long revision=reserved!=null?reserved.g0Revision():previousSource==null?1:
            previousState.get("catalog_sha256").getAsString().equals(catalogSha)?previousSource.exportRevision().value():Math.addExact(previousSource.exportRevision().value(),1);
        var projected=ObservedPhysicalG0Projection.project(owner.directorWorld(),revision,catalog,catalogSha);byte[] raw=G0.encode(projected.input());
        require(raw.length<=1_048_576,"observed G0 source byte cap");String sha=BlobContent.hash(raw).value();
        String name="observed-physical-r"+revision+"-"+sha+".g0",transfer="observed-r"+revision+"-"+sha.substring(0,16)+"_transfer";
        immutable(exports.resolve(name),raw);String mappingSha=proof.write("mapping",mappingProof(projected));
        var done=new Receipt(1,owner.survey(),owner.directorWorld(),catalog.world_id().value(),catalog.build_id().value(),catalog.catalog_revision().value(),catalogSha,catalog.digest().value(),receipt.requestSha256(),receipt.witnessesSha256(),owner.markerSha(),revision,sha,projected.input().exportDigest().value(),transfer,projected.input().regions().size(),projected.input().locations().size(),projected.input().anchors().size(),mappingSha,STATUS);
        // A survey's immutable producer plan fixes its technical revision even
        // after another source snapshot is exported. It is not an import ACK.
        require(currentRevision!=revision||Objects.equals(currentSha,sha),"observed producer revision conflict; preserve both plans");
        require(currentRevision>=revision||currentRevision==revision-1,"observed producer revision gap");
        var pointer=new JsonObject();pointer.addProperty("version",1);pointer.addProperty("world",owner.directorWorld().toString());pointer.addProperty("source_world",catalog.world_id().value());
        pointer.addProperty("revision",revision);pointer.addProperty("catalog_sha256",catalogSha);pointer.addProperty("g0_sha256",sha);pointer.addProperty("archive",name);pointer.addProperty("survey",owner.survey().toString());pointer.addProperty("phase","FROZEN_EXPORT");
        if(currentRevision<=revision)atomic(state,DirectorG0LocationExport.canonical(pointer).getBytes(StandardCharsets.UTF_8));
        proof.write("plan",done);
        publishTransport(exports.resolve(transfer),projected.input());
        proof.write("receipt",done);
        if(currentRevision<=revision){pointer.addProperty("phase","COMPLETE_EXPORT");atomic(state,DirectorG0LocationExport.canonical(pointer).getBytes(StandardCharsets.UTF_8));}
        return new Result(owner.survey(),revision,done.regions(),done.locations(),sha,transfer,STATUS);
    }
    /** Store catalogue and per-location provenance once, rather than amplifying
     * long IDs/revisions once per geometry box inside a bounded receipt. */
    private static JsonObject mappingProof(ObservedPhysicalG0Projection.Projection projection){
        var first=projection.mappings().getFirst();var root=new JsonObject();root.addProperty("version",1);
        root.addProperty("source_world",first.sourceWorld().value());root.addProperty("source_build",first.sourceBuild().value());root.addProperty("catalog_revision",first.catalogRevision().value());
        var grouped=new TreeMap<fr.proutlost.contract.director.Id,List<ObservedPhysicalG0Projection.Mapping>>();
        for(var mapping:projection.mappings())grouped.computeIfAbsent(mapping.sourceLocation(),ignored->new ArrayList<>()).add(mapping);
        var locations=new JsonArray();for(var group:grouped.entrySet()){
            var location=new JsonObject();location.addProperty("source_location",group.getKey().value());location.addProperty("location_revision",group.getValue().getFirst().locationRevision().value());
            var boxes=new JsonArray();for(var mapping:group.getValue()){
                var box=new JsonObject();box.add("geometry",JsonParser.parseString(StrictCodec.encode(mapping.geometry())));
                box.addProperty("region_id",mapping.regionId().value().toString());box.addProperty("location_id",mapping.locationId().value().toString());boxes.add(box);
            }
            location.add("boxes",boxes);locations.add(location);
        }
        root.add("locations",locations);return root;
    }
    private static void publishTransport(Path target,LocationImport input)throws Exception{
        // Optional installed producer API; no importer or WAL implementation is invoked.
        var type=Class.forName("fr.proutlost.director.integration.locations.LocationTransferFiles");
        try{type.getMethod("writeImmutable",Path.class,LocationImport.class).invoke(null,target,input);}
        catch(java.lang.reflect.InvocationTargetException error){throw new IOException("installed paged G0 publisher refused; previous source retained",error.getCause());}
    }
    private static void immutable(Path path,byte[] bytes)throws IOException{
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){require(Arrays.equals(bytes,read(path,1_048_576)),"immutable observed G0 archive conflict");return;}atomic(path,bytes);
    }
    private static void atomic(Path path,byte[] bytes)throws IOException{
        noLinks(path);var temporary=Files.createTempFile(path.getParent(),".observed-g0-",".partial");
        try{try(var out=FileChannel.open(temporary,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);out.force(true);}
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(var directory=FileChannel.open(path.getParent(),StandardOpenOption.READ)){directory.force(true);}
        }finally{Files.deleteIfExists(temporary);}
    }
}
