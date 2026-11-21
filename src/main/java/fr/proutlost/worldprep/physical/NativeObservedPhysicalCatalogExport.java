package fr.proutlost.worldprep.physical;

import fr.proutlost.contract.director.*;
import fr.proutlost.integration.physical.NativeLoadedAndDiskCellWitness;
import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.director.CatalogPublication;
import fr.proutlost.worldprep.director.ObservedPhysicalCatalogProducer;
import fr.proutlost.worldprep.director.ObservedPhysicalCatalogProducer.*;
import fr.proutlost.worldprep.runtime.CandidateGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** Explicit admin observation of an already prepared, privately staged candidate.
 * Writes only immutable export/evidence sidecars; never saves/mutates/loads terrain,
 * starts WorldPrep or activates a Director/J04 source. */
public final class NativeObservedPhysicalCatalogExport {
    public record Receipt(int version,UUID survey,String requestSha256,String candidateMarkerSha256,
                          String witnessesSha256,String catalogSha256,String capturedAt,int targets,
                          long cells,String scope,String status) {}
    public record Result(UUID survey,String catalog,String sha256,int targets,long cells,String status) {}
    private static final String STATUS="PHYSICAL_GEOMETRY_ONLY_D0_SEMANTICS_UNAUTHORED_NOT_IMPORTED";
    private NativeObservedPhysicalCatalogExport(){}
    private static void require(boolean condition,String why)throws IOException{if(!condition)throw new IOException(why);}
    private static void noLinks(Path path)throws IOException{
        for(Path cursor=path.toAbsolutePath().normalize();cursor!=null;cursor=cursor.getParent())
            require(!Files.isSymbolicLink(cursor),"survey symbolic link refused");
    }
    private static byte[] read(Path path,int bound)throws IOException{
        noLinks(path);require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path)<=bound,"missing/oversized survey file: "+path.getFileName());
        try(var in=Files.newInputStream(path)){var bytes=in.readNBytes(bound+1);require(bytes.length<=bound,"survey file grew beyond bound");return bytes;}
    }
    public static Result export(ServerLevel level,String filename)throws Exception{
        NativePhysicalRuntime.requireThread(level);CandidateGuard.requireCandidate(level);
        require(filename.matches("[a-zA-Z0-9_-][a-zA-Z0-9_.-]{0,90}\\.json")&&!filename.contains(".."),"survey requires one local JSON filename");
        require(!WorldPrepConfig.ENABLED.get()&&!NativePhysicalRuntime.hasActive(level)&&!NativeQualificationRuntime.active(level)
            &&!NativeStructureReconciliation.active(level),"pause active world preparation before observation");
        require(!WorldPrepStructureModule.status(level).startsWith("RUNNING")&&!WorldPrepFeatureModule.active(level)
            &&!WorldPrepFaunaModule.active(level),"active standalone module prevents survey");
        Path world=level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();noLinks(world);
        var requestBytes=read(world.resolve("worldprep-surveys/requests").resolve(filename),ObservedPhysicalCatalogProducer.MAX_REQUEST_BYTES);
        var request=ObservedPhysicalCatalogProducer.decode(requestBytes);String requestText=new String(requestBytes,StandardCharsets.UTF_8);
        var requestSha=Digests.sha256(requestText);byte[] marker=read(world.resolve("worldprep-candidate.json"),4096);
        String markerText=new String(marker,StandardCharsets.UTF_8);
        require(Digests.sha256(markerText).equals(request.candidateMarkerSha256()),"survey candidate marker SHA differs");
        var markerObject=com.google.gson.JsonParser.parseString(markerText).getAsJsonObject();
        require(markerObject.has("physicalSurveyScope")&&markerObject.get("physicalSurveyScope").getAsString().equals(request.scope().name()),"candidate must explicitly authorize this physical survey scope");
        require(level.getSeed()==request.expectedSeed(),"survey seed differs from actual loaded candidate");
        // Preflight all immutable origin bindings before recording a snapshot. Merely having
        // a G0 tag/location/intent is never accepted as native module provenance.
        Path output=world.resolve("worldprep-surveys/exports").resolve(request.survey().toString());noLinks(output);
        Path catalog=output.resolve("catalog.json");var store=new PhysicalStore(output.resolve("evidence"));
        if(store.exists("receipt")){
            var prior=store.read("receipt",Receipt.class);
            require(prior.version()==1&&prior.survey().equals(request.survey())&&prior.requestSha256().equals(requestSha.value())
                &&prior.candidateMarkerSha256().equals(request.candidateMarkerSha256().value())&&prior.status().equals(STATUS)
                &&prior.targets()==request.targets().size()&&prior.scope().equals(request.scope().name())
                &&prior.cells()==request.targets().stream().mapToLong(t->ObservedPhysicalCatalogProducer.volume(t.geometry())).sum(),"retained survey receipt conflicts with this request");
            require(store.read("request",String.class).equals(requestText),"retained exact survey request differs");
            require(PhysicalStore.sha(store.readText("witnesses")).equals(prior.witnessesSha256()),"retained survey witnesses differ");
            String retained=new String(read(catalog,Limits.MAX_JSON_BYTES),StandardCharsets.UTF_8);
            require(Digests.sha256(retained).value().equals(prior.catalogSha256()),"retained physical export SHA differs");
            validate(request,retained);
            return new Result(request.survey(),catalog.toString(),prior.catalogSha256(),prior.targets(),prior.cells(),"RETAINED_SNAPSHOT_VERIFIED_NOT_CURRENT_OBSERVATION_OR_IMPORT");
        }
        var origins=new HashMap<Id,List<EvidenceRef>>();
        for(var target:request.targets())origins.put(target.location(),origin(world,target,request.scope()));
        store.write("request",requestText);
        if(!store.exists("capture-time"))store.write("capture-time",Instant.now().toString());
        Instant capturedAt=Instant.parse(store.read("capture-time",String.class));
        var witnesses=new ArrayList<Witness>();long cells=0;
        for(var target:request.targets()){
            var current=NativeLoadedAndDiskCellWitness.capture(level.getServer(),target.geometry());
            cells=Math.addExact(cells,current.cells());
            witnesses.add(new Witness(target.location(),current.loadedDigest(),current.diskDigest(),current.cells(),current.chunks(),origins.get(target.location())));
        }
        var exported=ObservedPhysicalCatalogProducer.export(request,requestSha,capturedAt,witnesses);
        require(exported.json()!=null,"observed physical exporter refused: "+exported.reason());
        validate(request,exported.json());
        String witnessesSha=store.write("witnesses",witnesses);
        CatalogPublication.write(catalog,exported.json());
        String sha=Digests.sha256(exported.json()).value();
        var receipt=new Receipt(1,request.survey(),requestSha.value(),request.candidateMarkerSha256().value(),witnessesSha,sha,capturedAt.toString(),request.targets().size(),cells,request.scope().name(),STATUS);
        store.write("receipt",receipt);
        return new Result(request.survey(),catalog.toString(),sha,request.targets().size(),cells,STATUS);
    }
    private static void validate(Request request,String json)throws IOException{
        var snapshot=StrictCodec.decode(json,CatalogSnapshot.class);CatalogValidation.requireSealed(snapshot);
        require(snapshot.world_id().equals(request.sourceWorld())&&snapshot.build_id().equals(request.sourceBuild())&&snapshot.catalog_revision().equals(request.revision()),"survey export source identity differs");
        var expected=request.targets().stream().map(Target::location).collect(java.util.stream.Collectors.toSet());
        require(expected.equals(snapshot.entries().stream().map(StaticLocationCatalogEntry::location_id).collect(java.util.stream.Collectors.toSet()))&&snapshot.entries().size()==request.targets().size(),"survey export is not complete");
        for(var entry:snapshot.entries())require(entry.capabilities().isEmpty()&&entry.anchors().isEmpty()&&entry.surfaces().isEmpty()&&entry.protection().isEmpty()
            &&entry.presentation_channels().isEmpty()&&entry.recovery_anchors().isEmpty(),"geometry-only survey acquired unprovided semantics");
    }
    private static List<EvidenceRef> origin(Path world,Target target,Scope scope)throws IOException{
        if(target.origin()==Origin.OBSERVED_EXISTING_CELLS)return List.of();
        String directory=target.moduleDirectory();
        require(directory.matches("worldprep-v2/structure-module/[a-zA-Z0-9_-]+/placement-[0-9]+"),"native survey origin must be an exact completed structure-module placement");
        Path site=world.resolve(directory).normalize();noLinks(site);
        require(site.startsWith(world)&&Files.isDirectory(site,LinkOption.NOFOLLOW_LINKS),"native survey origin directory unavailable");
        require(Files.isRegularFile(site.getParent().resolve("complete.json"),LinkOption.NOFOLLOW_LINKS),"native module batch is incomplete");
        new PhysicalStore(site.getParent()).readText("complete");var records=new PhysicalStore(site);
        require(!records.exists("rollback-intent"),"native survey origin was rolled back");
        var recordBytes=read(site.resolve("director-native-location-candidate.json"),PhysicalStore.MAX_BYTES);
        require(Digests.sha256(new String(recordBytes,StandardCharsets.UTF_8)).equals(target.moduleRecordSha256()),"native survey record bytes changed");
        var record=records.read("director-native-location-candidate",NativeVerifiedStructureCatalog.NativeLocationRecord.class);
        var disk=records.read("native-disk-verified",NativeRegistryStructureAudit.Receipt.class);
        var declared=records.read("native-all-chunks-placed",NativeRegistryStructurePass.NativeReceipt.class);
        require(record.schema()==1&&record.status().equals("PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED")&&disk.version()==1
            &&disk.status().equals("PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED")&&record.operation().equals(disk.operation())
            &&record.structureRegistryId().equals(disk.registryId())&&record.diskReceiptSha256().equals(PhysicalStore.sha(records.readText("native-disk-verified")))
            &&record.generationPlanSha256().equals(PhysicalStore.sha(records.readText("native-plan")))&&record.generationPlanSha256().equals(disk.nativePlanSha256())&&disk.changedBlocks()>0,"native survey origin proof mismatch");
        require(declared.operation().equals(record.operation())&&declared.registryId().equals(record.structureRegistryId())&&declared.dimension().equals(record.dimension())
            &&declared.completedChunks()==declared.totalChunks()&&declared.totalChunks()==disk.chunks()&&declared.changedBlocks()>0
            &&declared.state().equals("PLACED_UNVERIFIED_DISK"),"native survey placement receipt is incomplete");
        require(scope==Scope.SYNTHETIC_QA||declared.conditions()==NativeRegistryStructurePass.Conditions.NATIVE
            ||declared.conditions()==NativeRegistryStructurePass.Conditions.IMPORT_EXISTING_TERRAIN,"LAB biome/forced placement cannot become a physical source by survey");
        require(target.location().equals(ObservedPhysicalCatalogProducer.nativeSupportId(record.locationId(),target.geometry()))
            &&target.sourceRef().value().equals(record.structureRegistryId())
            &&record.tags().containsAll(target.tags().stream().map(Id::value).toList()),"native survey support identity/metadata mismatch");
        for(var bounds:target.geometry())require(bounds.dimension_id().value().equals(record.dimension())
            &&bounds.min().x()>=record.minX()&&bounds.min().y()>=record.minY()&&bounds.min().z()>=record.minZ()
            &&bounds.max().x()<record.maxXExclusive()&&bounds.max().y()<record.maxYExclusive()&&bounds.max().z()<record.maxZExclusive(),"observed support exceeds native placement geometry");
        String suffix=record.locationId().toString();
        return List.of(new EvidenceRef(new Id("proutlost:survey_native_origin/"+suffix),new Digest(record.diskReceiptSha256()),new Id("proutlost:native_structure_origin_v1")),
            new EvidenceRef(new Id("proutlost:survey_native_record/"+suffix),target.moduleRecordSha256(),new Id("proutlost:immutable_module_record_v1")));
    }
}
