package fr.proutlost.director.memory;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Synthetic ACKed CAMP with no first RegionMemory, pending scope or pending anchor. */
public final class UnrememberedCampColdReadTests {
    private static final WorldId WORLD=new WorldId(new UUID(0x5030303850524f4fL,2));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final AnchorId CAMP=new AnchorId(new UUID(112,1));private static int assertions;
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    private static <T>T await(CompletionStage<T> pending)throws Exception{return pending.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static ConfigView profile(Path file)throws Exception{
        var raw=((JsonValue.ObjectValue)((JsonValue.ObjectValue)CanonicalJsonV1.decode(Files.readAllBytes(file))).values().get("values")).values();var values=new HashMap<String,JsonValue.Number>();raw.forEach((k,v)->values.put(k,(JsonValue.Number)v));
        return new ConfigView(new Revision(1),BlobContent.hash(Files.readAllBytes(file)),Calibration.DEFAULT_FOR_TESTING,values);
    }
    private static void append(JournalStore store,DurableRecord record,List<BlobContent> blobs)throws Exception{
        check(await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(record),blobs))).ackStatus()==AckStatus.DURABLE,"synthetic candidate state actually ACKed by J04");
    }
    private static void activate(JournalStore store)throws Exception{
        var name=new ResourceId("test:p008_unremembered_camp");var draft=new LocationImport(1,WORLD,name,new Revision(1),new Fingerprint("0".repeat(64)),List.of(),List.of(),List.of());
        var input=new LocationImport(1,WORLD,name,draft.exportRevision(),StandaloneLocationImporter.digest(draft),List.of(),List.of(),List.of());var transfer=LocationTransfer.prepare(input);
        for(var fragment:transfer.pages()){
            var blob=BlobContent.capture(new ResourceId("proutlost_director:location_transfer_fragment"),1,Base64.getDecoder().decode(fragment.bytesBase64()));append(store,new DurableMetadataSourcePage(transfer.manifest(),fragment.pageIndex(),blob.reference()),List.of(blob));
        }
        var page=new MetadataGenerationPage(LocationTransfer.identity(transfer.manifest()),name,input.exportRevision(),input.exportDigest(),0,1,List.of(),Map.of());
        var blob=BlobContent.capture(new ResourceId("proutlost_director:metadata_generation_page"),1,new ModelCodec<>(MetadataGenerationPage.class).encode(page));append(store,new DurableMetadataStage(transfer.manifest(),0,1,blob.reference()),List.of(blob));
        append(store,new DurableMetadataActivation(transfer.manifest(),Optional.empty(),1),List.of());
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("profile NEW isolated J04 directory required");var path=Path.of(args[1]);if(Files.exists(path))throw new IllegalArgumentException("fresh QA directory required");var settings=new MemorySettings(profile(Path.of(args[0])),false);
        var store=await(JournalStore.open(path,WORLD,new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128)));
        try{
            activate(store);append(store,new DurableAggregate(new AnchorAggregate(new AnchorRecord(new AggregateHeader(1,CAMP.value(),new Revision(1),new Sequence(0),new Tick(0)),CAMP,Optional.empty(),Set.of(),DIM,new BlockPosition(DIM,1,64,1000),"region.camp_radius_blocks",Set.of(new TagId("proutlost:camp")),AnchorOrigin.CAMP,EvidenceStatus.POSSIBLE,true))),List.of());
            var pinned=store.metadataReads().snapshot().orElseThrow();var region=new AggregateKey(AggregateKind.REGION,CAMP.value());var memory=new AggregateKey(AggregateKind.REGION_MEMORY,CAMP.value());
            check(!pinned.generations().isEmpty(),"real active G0 generation enables production scoped read path");
            check(store.metadataReads().metadataRevision(pinned,region).value()==0&&store.metadataReads().metadataRevision(pinned,memory).value()==0,"ACKed unremembered CAMP has neither REGION nor REGION_MEMORY");
            var demand=new BoundedMetadataProjection.Demand(Set.of(),Set.of(BoundedMetadataProjection.Demand.cell(new BlockPosition(DIM,1,64,1000),16)),16,Set.of(),Set.of());
            check(demand.pinned().isEmpty()&&demand.anchors().isEmpty(),"no pending region scope or pending anchor masks geometric selection");
            var selected=await(BoundedMetadataProjection.load(store,pinned,demand,settings));
            System.out.println("UNREMEMBERED_CAMP anchor_selected="+selected.memory().anchors().containsKey(CAMP)+" scopes="+selected.scopes().scopes().size()+" region_bodies="+selected.memory().regionRecords().size()+" memory_bodies="+selected.memory().regions().size());
            check(selected.memory().anchors().containsKey(CAMP),"ACKed spatial CAMP must remain readable before its first memory or pending J01 window");
            check(selected.scopes().scopes().contains(new ScopeRef(ScopeKind.REGION,CAMP.value())),"candidate reserved identity has an explicit absent-memory scope");
            check(selected.memory().regionRecords().isEmpty()&&selected.memory().regions().isEmpty(),"selection does not invent a physical region or a first memory");
            check(selected.currentHeads(store,pinned),"unremembered camp read is valid at its exact pinned cut");
            var first=new RegionMemory(new AggregateHeader(1,CAMP.value(),new Revision(1),new Sequence(0),new Tick(1)),new RegionId(CAMP.value()),Map.of(),new Tick(0),new Tick(0),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of()));
            append(store,new DurableAggregate(new RegionMemoryAggregate(first)),List.of());
            check(!selected.currentHeads(store,store.metadataReads().snapshot().orElseThrow()),"appearance of first memory invalidates the previously absent candidate memory");
            check(store.metadataCachedEntries()<=512,"cache bound remains512");
        }finally{await(store.closeAsync());}
        System.out.println("PASS UnrememberedCampColdReadTests assertions="+assertions+" scope=synthetic_actual_J04_ACKed_CAMP_no_first_memory_no_pending_window_no_native_claim");
    }
}
