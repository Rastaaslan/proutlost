package fr.proutlost.worldprep;

import fr.proutlost.contract.director.*;
import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.model.JsonValue;
import fr.proutlost.worldprep.director.CatalogPublication;
import fr.proutlost.worldprep.director.ObservedPhysicalCatalogProducer;
import fr.proutlost.worldprep.director.ObservedPhysicalCatalogProducer.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Pure request/export/persistence contract with explicitly synthetic witnesses.
 * The native LoadedCell/Anvil adapter has a separate staged Minecraft GameTest. */
public final class ObservedPhysicalCatalogProducerScenario {
    private ObservedPhysicalCatalogProducerScenario(){}
    private static int checks;
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
    private static void reject(Runnable action,String why){try{action.run();}catch(IllegalArgumentException|NullPointerException expected){checks++;return;}throw new AssertionError(why);}
    private static final Id WORLD=new Id("test:physical_source"),BUILD=new Id("test:physical_new_export"),DIM=new Id("minecraft:overworld"),SITE=new Id("test:observed_support");
    private static final Bounds BOX=new Bounds(DIM,new BlockPos(0,64,0),new BlockPos(2,66,2));
    private static final Digest SHA=new Digest("1".repeat(64));
    private static JsonValue.Text text(String value){return new JsonValue.Text(value);}
    private static JsonValue number(int value){return new JsonValue.Number(BigDecimal.valueOf(value));}
    private static JsonValue.ObjectValue target(){
        return new JsonValue.ObjectValue(Map.of("location_id",text(SITE.value()),"source_ref",text("proutlost:observed_existing_cells"),
            "geometry",new JsonValue.Array(List.of(CanonicalJsonV1.decode(StrictCodec.encode(BOX).getBytes(StandardCharsets.UTF_8)))),"tags",new JsonValue.Array(List.of()),
            "origin",text("OBSERVED_EXISTING_CELLS"),"module_directory",JsonValue.Nil.INSTANCE,"module_record_sha256",JsonValue.Nil.INSTANCE));
    }
    public static byte[] requestBytes(){
        return CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of("schema_version",number(1),"survey_id",text("00000000-0000-0000-0000-000000000301"),
            "source_world",text(WORLD.value()),"source_build",text(BUILD.value()),"revision",text("new-physical-qa-r1"),"expected_seed",text("27594267"),
            "candidate_marker_sha256",text(SHA.value()),"scope",text("SYNTHETIC_QA"),"targets",new JsonValue.Array(List.of(target())))));
    }
    public static void run(Path output)throws Exception{
        checks=0;var bytes=requestBytes();var request=ObservedPhysicalCatalogProducer.decode(bytes);
        check(request.targets().size()==1&&request.expectedSeed()==27594267L&&request.scope()==Scope.SYNTHETIC_QA,"closed survey request decoded");
        check(ObservedPhysicalCatalogProducer.volume(request.targets().getFirst().geometry())==27,"exact inclusive cell count");
        var sourceRequest=new String(bytes,StandardCharsets.UTF_8);
        reject(()->ObservedPhysicalCatalogProducer.decode(sourceRequest.replace("\"scope\":\"SYNTHETIC_QA\"","\"scope\":\"SYNTHETIC_QA\",\"unknown\":true").getBytes(StandardCharsets.UTF_8)),"unknown request key accepted");
        reject(()->ObservedPhysicalCatalogProducer.decode(sourceRequest.replace("\"schema_version\":1","\"schema_version\":1,\"schema_version\":1").getBytes(StandardCharsets.UTF_8)),"duplicate root key accepted");
        reject(()->ObservedPhysicalCatalogProducer.decode(sourceRequest.replace("\"expected_seed\":\"27594267\"","\"expected_seed\":\"027594267\"").getBytes(StandardCharsets.UTF_8)),"noncanonical seed accepted");
        reject(()->new Target(SITE,new Id("proutlost:observed_existing_cells"),List.of(BOX),Set.of(new Id("test:invented_tag")),Origin.OBSERVED_EXISTING_CELLS,null,null),"arbitrary observed tag became native metadata");
        reject(()->new Target(SITE,new Id("test:pretend_structure"),List.of(BOX),Set.of(),Origin.VERIFIED_NATIVE_STRUCTURE_MODULE,null,null),"native origin without record accepted");
        reject(()->new Request(request.survey(),WORLD,BUILD,request.revision(),request.expectedSeed(),SHA,Scope.SYNTHETIC_QA,List.of(request.targets().getFirst(),request.targets().getFirst())),"duplicate survey target accepted");
        var huge=new Bounds(DIM,new BlockPos(0,64,0),new BlockPos(512,66,512));
        reject(()->new Request(request.survey(),WORLD,BUILD,request.revision(),request.expectedSeed(),SHA,Scope.SYNTHETIC_QA,List.of(new Target(SITE,new Id("proutlost:observed_existing_cells"),List.of(huge),Set.of(),Origin.OBSERVED_EXISTING_CELLS,null,null))),"whole request overflow was truncated");
        reject(()->new Target(SITE,new Id("proutlost:observed_existing_cells"),List.of(new Bounds(DIM,new BlockPos(Integer.MAX_VALUE,64,0),new BlockPos(Integer.MAX_VALUE,64,0))),Set.of(),Origin.OBSERVED_EXISTING_CELLS,null,null),"integer endpoint could overflow native cell iteration");
        reject(()->new Witness(SITE,SHA,new Digest("2".repeat(64)),27,1,List.of()),"loaded/disk mismatch accepted");
        reject(()->ObservedPhysicalCatalogProducer.export(request,SHA,Instant.EPOCH,List.of()),"partial survey became visible");
        reject(()->ObservedPhysicalCatalogProducer.export(request,SHA,Instant.EPOCH,List.of(new Witness(SITE,SHA,SHA,26,1,List.of()))),"witness from another geometry accepted");
        var witness=new Witness(SITE,SHA,SHA,27,1,List.of());
        var exported=ObservedPhysicalCatalogProducer.export(request,SHA,Instant.parse("2026-11-18T00:00:00Z"),List.of(witness));
        check(exported.json()!=null,"synthetic witnessed geometry export refused: "+exported.reason());
        var snapshot=StrictCodec.decode(exported.json(),CatalogSnapshot.class);CatalogValidation.requireSealed(snapshot);
        check(snapshot.world_id().equals(WORLD)&&snapshot.build_id().equals(BUILD)&&snapshot.entries().size()==1,"new source identity/completeness lost");
        var entry=snapshot.entries().getFirst();
        check(entry.geometry().equals(List.of(BOX))&&entry.source_type()==fr.proutlost.contract.director.Vocabulary.SourceType.worldprep_prepared_site,"exact physical geometry retained");
        check(entry.capabilities().isEmpty()&&entry.anchors().isEmpty()&&entry.surfaces().isEmpty()&&entry.protection().isEmpty()&&entry.recovery_anchors().isEmpty()&&entry.presentation_channels().isEmpty(),"survey invented author semantics");
        check(entry.validation_evidence().stream().filter(e->e.validator_id().equals(new Id("proutlost:native_loaded_block_state_v1"))).count()==1,"D0 actual loaded-state witness missing/ambiguous");
        check(entry.validation_evidence().stream().anyMatch(e->e.validator_id().equals(new Id("proutlost:anvil_readback_v1"))&&e.digest().equals(SHA)),"independent disk witness missing");
        var nativeGeometry=List.of(BOX);var nativeId=ObservedPhysicalCatalogProducer.nativeSupportId(new UUID(1,2),nativeGeometry);
        var nativeTarget=new Target(nativeId,new Id("test:native_structure"),nativeGeometry,Set.of(new Id("test:verified_model_tag")),Origin.VERIFIED_NATIVE_STRUCTURE_MODULE,"worldprep-v2/structure-module/batch/placement-0",SHA);
        var nativeRequest=new Request(request.survey(),WORLD,BUILD,request.revision(),request.expectedSeed(),SHA,Scope.SYNTHETIC_QA,List.of(nativeTarget));
        reject(()->ObservedPhysicalCatalogProducer.export(nativeRequest,SHA,Instant.EPOCH,List.of(new Witness(nativeId,SHA,SHA,27,1,List.of()))),"module origin claimed without checked lineage");
        var origin=new EvidenceRef(new Id("test:synthetic_module_record"),SHA,new Id("test:synthetic_module_validator"));
        var nativeExport=ObservedPhysicalCatalogProducer.export(nativeRequest,SHA,Instant.EPOCH,List.of(new Witness(nativeId,SHA,SHA,27,1,List.of(origin))));
        check(StrictCodec.decode(nativeExport.json(),CatalogSnapshot.class).entries().getFirst().tags().equals(nativeTarget.tags()),"explicit verified native metadata lost");
        Files.createDirectories(output);var file=output.resolve("catalog.json");
        CatalogPublication.write(file,exported.json());CatalogPublication.write(file,exported.json());
        check(Files.readString(file).equals(exported.json()),"immutable publication/retry differs");
        boolean conflict=false;try{CatalogPublication.write(file,nativeExport.json());}catch(java.io.IOException expected){conflict=true;}
        check(conflict,"different survey source overwrote durable catalogue");
        Files.writeString(output.resolve("expected-sha256.txt"),Digests.sha256(exported.json()).value()+"\n");
        System.out.println("P008_OBSERVED_PHYSICAL_PRODUCER_PASS checks="+checks+" scope=SYNTHETIC_WITNESSES_PURE_PRODUCER_NO_MINECRAFT_OR_D0_RECEPTION");
    }
    public static void reopen(Path output)throws Exception{
        var text=Files.readString(output.resolve("catalog.json"));var source=StrictCodec.decode(text,CatalogSnapshot.class);CatalogValidation.requireSealed(source);
        if(!Digests.sha256(text).value().equals(Files.readString(output.resolve("expected-sha256.txt")).trim())||source.entries().size()!=1)
            throw new AssertionError("cold survey artifact readback mismatch");
        System.out.println("P008_OBSERVED_PHYSICAL_COLD_ARTIFACT_PASS scope=NEW_JVM_ARTIFACT_INTEGRITY_NOT_NATIVE_WORLD_RESTART");
    }
    public static void main(String[] args)throws Exception{if(args.length!=2)throw new IllegalArgumentException("produce|reopen private-path");if(args[0].equals("produce"))run(Path.of(args[1]));else if(args[0].equals("reopen"))reopen(Path.of(args[1]));else throw new IllegalArgumentException("unknown operation");}
}
