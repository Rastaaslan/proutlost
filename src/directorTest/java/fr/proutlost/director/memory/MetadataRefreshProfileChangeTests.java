package fr.proutlost.director.memory;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Synthetic public refresh-contract test: changed radius must discover additional real J04 anchor. */
public final class MetadataRefreshProfileChangeTests {
    private static final WorldId WORLD=new WorldId(new UUID(0x5030303850524f4fL,2));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");private static int assertions;
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    private static <T>T await(CompletionStage<T> pending)throws Exception{return pending.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static ConfigView profile(Path file)throws Exception{
        var raw=((JsonValue.ObjectValue)((JsonValue.ObjectValue)CanonicalJsonV1.decode(Files.readAllBytes(file))).values().get("values")).values();var values=new HashMap<String,JsonValue.Number>();raw.forEach((k,v)->values.put(k,(JsonValue.Number)v));
        return new ConfigView(new Revision(1),BlobContent.hash(Files.readAllBytes(file)),Calibration.DEFAULT_FOR_TESTING,values);
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("profile NEW isolated J04 directory required");var path=Path.of(args[1]);if(Files.exists(path))throw new IllegalArgumentException("fresh synthetic QA store required");
        var small=profile(Path.of(args[0]));var values=new HashMap<>(small.values());values.put("region.camp_radius_blocks",new JsonValue.Number(new BigDecimal(64)));
        var changed=new ConfigView(small.revision().plus(1),BlobContent.hash(CanonicalJsonV1.encode(new JsonValue.ObjectValue(new HashMap<>(values)))),small.calibration(),values);
        var beforeSettings=new MemorySettings(small,false);var afterSettings=new MemorySettings(changed,false);
        var store=await(JournalStore.open(path,WORLD,new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128)));
        try{
            var activate=UnrememberedCampColdReadTests.class.getDeclaredMethod("activate",JournalStore.class);activate.setAccessible(true);activate.invoke(null,store);
            List<DurableRecord> records=new ArrayList<>();for(int index=0;index<2;index++){
                var id=new AnchorId(new UUID(113,index));var anchor=new AnchorRecord(new AggregateHeader(1,id.value(),new Revision(1),new Sequence(0),new Tick(1)),id,Optional.empty(),Set.of(),DIM,new BlockPosition(DIM,index==0?1:70,64,1000),"region.camp_radius_blocks",Set.of(new TagId("proutlost:camp")),AnchorOrigin.CAMP,EvidenceStatus.POSSIBLE,true);records.add(new DurableAggregate(new AnchorAggregate(anchor)));
            }
            check(await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),records,List.of()))).ackStatus()==AckStatus.DURABLE,"both real synthetic CAMP anchors ACKed atomically");
            var pinned=store.metadataReads().snapshot().orElseThrow();var demand=new BoundedMetadataProjection.Demand(Set.of(),Set.of(BoundedMetadataProjection.Demand.cell(new BlockPosition(DIM,1,64,1000),16)),16,Set.of(),Set.of());
            var previous=await(BoundedMetadataProjection.load(store,pinned,demand,beforeSettings));check(previous.memory().anchors().size()==1,"radius32 selects exactly one known real anchor");
            var expected=await(BoundedMetadataProjection.load(store,pinned,demand,afterSettings));check(expected.memory().anchors().size()==2,"radius64 legitimately selects both real anchors at the same J04 cut");
            var refreshed=await(BoundedMetadataProjection.refresh(store,previous,pinned,demand,afterSettings));
            System.out.println("REFRESH_PROFILE initial_anchors="+previous.memory().anchors().size()+" expected_anchors="+expected.memory().anchors().size()+" refreshed_anchors="+refreshed.memory().anchors().size()+" same_J04_cut=true changed_radius=32_to64");
            check(refreshed.states().equals(expected.states())&&refreshed.scopes().equals(expected.scopes()),"changed profile must reproduce complete actual J04 selection, never reuse old radius subset");
            check(refreshed.snapshot().cut().equals(pinned.cut())&&refreshed.currentHeads(store,pinned),"changed-profile fallback preserves authentic cut and exact selected head validation");
        }finally{await(store.closeAsync());}
        System.out.println("PASS MetadataRefreshProfileChangeTests assertions="+assertions+" scope=synthetic_actual_J04_changed_geometry_profile_no_production_config_change");
    }
}
