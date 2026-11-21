package fr.proutlost.director.memory;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.StandaloneLocationImporter;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Declared synthetic actual J04 scope proofs, not native discovery or Contra data. */
public final class ScopedRegionMemoryAbsenceTests {
    private static final WorldId WORLD=new WorldId(new UUID(0x5030303850524f4fL,1));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static int assertions;
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    private static <T>T await(CompletionStage<T> pending)throws Exception{return pending.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static UUID id(int n){return new UUID(111,n);}
    private static AggregateHeader header(UUID id,long revision){return new AggregateHeader(1,id,new Revision(revision),new Sequence(0),new Tick(revision));}
    private static RegionRecord region(RegionId id){return new RegionRecord(header(id.value(),1),id,DIM,RegionStatus.ACTIVE,new SupportShape(List.of(new Aabb(DIM,0,0,0,16,128,16)),List.of(),List.of()),Set.of(),new UnitValue(0),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of(new MemoryId(id.value())));}
    private static RegionMemory memory(RegionId id,long revision){return new RegionMemory(header(id.value(),revision),id,Map.of(),new Tick(0),new Tick(0),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of()));}
    private static void append(JournalStore store,DurableRecord record,List<BlobContent> blobs)throws Exception{
        check(await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(record),blobs))).ackStatus()==AckStatus.DURABLE,"synthetic J04 append durable");
    }
    private static void append(JournalStore store,AggregateState state)throws Exception{append(store,new DurableAggregate(state),List.of());}
    private static void legacySource(JournalStore store)throws Exception{
        var name=new ResourceId("test:p008_scope_proof_empty");var draft=new LocationImport(1,WORLD,name,new Revision(1),new Fingerprint("0".repeat(64)),List.of(),List.of(),List.of());
        var input=new LocationImport(1,WORLD,name,draft.exportRevision(),StandaloneLocationImporter.digest(draft),List.of(),List.of(),List.of());
        var identity=new FactId(UUID.nameUUIDFromBytes(("director-location-import-v1/"+WORLD.value()+"/"+name.value()+"/1/"+input.exportDigest().value()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var fact=new DirectorFact(identity,new FactType("proutlost_director:location_import_applied"),List.of(new ScopeRef(ScopeKind.WORLD,WORLD.value())),Optional.empty(),EvidenceStatus.CONFIRMED,
            Map.of("source_namespace",new JsonValue.Text(name.value()),"export_revision",new JsonValue.Text("1"),"export_digest",new JsonValue.Text(input.exportDigest().value()),"location_import_base64",new JsonValue.Text(Base64.getEncoder().encodeToString(new ModelCodec<>(LocationImport.class).encode(input)))),true,Optional.empty());
        append(store,new DurableFact(fact),List.of());
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("NEW isolated J04 directory required");var root=Path.of(args[0]);if(Files.exists(root))throw new IllegalArgumentException("fresh declared QA store required");
        var store=await(JournalStore.open(root,WORLD,new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128)));
        var selected=new RegionId(id(1));var scope=new ScopeSet(WORLD,Set.of(new ScopeRef(ScopeKind.REGION,selected.value())));ScopedAggregateCapture.View retained;
        try{
            var key=new AggregateKey(AggregateKind.REGION,selected.value());var memoryKey=new AggregateKey(AggregateKind.REGION_MEMORY,selected.value());
            append(store,new RegionAggregate(region(selected)));var pinned=store.metadataReads().snapshot().orElseThrow();var absent=await(ScopedAggregateCapture.load(store,pinned,Set.of(key),2));
            check(absent.sameMemoryHeads(store,pinned,scope,Set.of()),"selected region with proven absent memory is initially valid");
            append(store,new RegionMemoryAggregate(memory(selected,1)));var now=store.metadataReads().snapshot().orElseThrow();
            check(!absent.sameMemoryHeads(store,now,scope,Set.of()),"selected REGION_MEMORY absent-to-present must invalidate even when REGION header is unchanged");
            check(absent.sameMemoryHeads(store,pinned,scope,Set.of()),"historical pinned absence remains coherent at its own cut");
            var present=await(ScopedAggregateCapture.load(store,now,Set.of(key,memoryKey),2));check(present.sameMemoryHeads(store,now,scope,Set.of()),"both actual selected bodies remain valid");
            var outside=new RegionId(id(2));append(store,new RegionMemoryAggregate(memory(outside,1)));var outsideCurrent=store.metadataReads().snapshot().orElseThrow();
            check(present.sameMemoryHeads(store,outsideCurrent,scope,Set.of()),"offscope absent-to-present memory does not invalidate exact selected proof");
            append(store,new RegionMemoryAggregate(memory(outside,2)));check(present.sameMemoryHeads(store,store.metadataReads().snapshot().orElseThrow(),scope,Set.of()),"offscope memory revision does not invalidate selected bodies");
            append(store,new RegionMemoryAggregate(memory(selected,2)));check(!present.sameMemoryHeads(store,store.metadataReads().snapshot().orElseThrow(),scope,Set.of()),"present selected memory revision change remains fenced");
            var anchor=new AnchorId(id(3));var candidate=new RegionId(anchor.value());var candidateScope=new ScopeSet(WORLD,Set.of(new ScopeRef(ScopeKind.REGION,candidate.value())));
            append(store,new AnchorAggregate(new AnchorRecord(header(anchor.value(),1),anchor,Optional.empty(),Set.of(),DIM,new BlockPosition(DIM,1000,64,1000),"region.camp_radius_blocks",Set.of(new TagId("proutlost:abandoned_camp")),AnchorOrigin.CAMP,EvidenceStatus.POSSIBLE,true)));
            var candidateSnapshot=store.metadataReads().snapshot().orElseThrow();var candidateView=await(ScopedAggregateCapture.load(store,candidateSnapshot,Set.of(new AggregateKey(AggregateKind.ANCHOR,anchor.value())),1));
            check(candidateView.sameMemoryHeads(store,candidateSnapshot,candidateScope,Set.of(anchor)),"actual CAMP candidate starts with absent REGION and absent memory");
            append(store,new RegionMemoryAggregate(memory(candidate,1)));var candidateCurrent=store.metadataReads().snapshot().orElseThrow();
            check(store.metadataReads().metadataRevision(candidateCurrent,new AggregateKey(AggregateKind.REGION,candidate.value())).value()==0,"candidate REGION is still actually absent");
            check(!candidateView.sameMemoryHeads(store,candidateCurrent,candidateScope,Set.of(anchor)),"candidate REGION_MEMORY absent-to-present invalidates without requiring a REGION creation");
            var refreshed=store.metadataReads().snapshot().orElseThrow();retained=await(ScopedAggregateCapture.load(store,refreshed,Set.of(key,memoryKey),2));var hydration=store.metadataHydrationPageLoads();
            check(retained.sameMemoryHeads(store,refreshed,scope,Set.of()),"refreshed exact scope remains valid");check(store.metadataHydrationPageLoads()==hydration,"scope proof revalidation performs no metadata hydration");
            legacySource(store);var sourceChanged=store.metadataReads().snapshot().orElseThrow();
            check(refreshed.generations().equals(sourceChanged.generations())&&!refreshed.sources().equals(sourceChanged.sources()),"actual legacy source descriptor changes independently of generation map");
            check(!retained.sameMemoryHeads(store,sourceChanged,scope,Set.of()),"source provenance change cannot reuse an older selected proof");
            check(store.metadataCachedEntries()<=512,"metadata cache bound retained");await(store.checkpoint());
        }finally{await(store.closeAsync());}
        var reopened=await(JournalStore.open(root,WORLD,new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128)));
        try{check(!retained.sameMemoryHeads(reopened,reopened.metadataReads().snapshot().orElseThrow(),scope,Set.of()),"new session cannot reuse prior proof after reopen");}finally{await(reopened.closeAsync());}
        System.out.println("PASS ScopedRegionMemoryAbsenceTests assertions="+assertions+" scope=synthetic_actual_J04_selected_candidate_absence_sources_session_no_native_claim");
    }
}
