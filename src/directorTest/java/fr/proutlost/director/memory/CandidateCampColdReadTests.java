package fr.proutlost.director.memory;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Declared synthetic raw camp windows through real reducer/J04/cold reader. */
public final class CandidateCampColdReadTests {
    private static final WorldId WORLD=new WorldId(new UUID(0x5030303844454e53L,1));private static final AnchorId ANCHOR=new AnchorId(new UUID(808,702));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");private static int assertions;
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    private static <T>T await(CompletionStage<T> pending)throws Exception{return pending.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static ConfigView profile(Path file)throws Exception{
        var data=((JsonValue.ObjectValue)((JsonValue.ObjectValue)CanonicalJsonV1.decode(Files.readAllBytes(file))).values().get("values")).values();var values=new HashMap<String,JsonValue.Number>();data.forEach((k,v)->values.put(k,(JsonValue.Number)v));return new ConfigView(new Revision(1),BlobContent.hash(Files.readAllBytes(file)),Calibration.DEFAULT_FOR_TESTING,values);
    }
    private static void append(JournalStore store,List<AggregateState> states)throws Exception{
        var records=states.stream().map(s->(DurableRecord)new DurableAggregate(s)).toList();check(await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),records,List.of()))).ackStatus()==AckStatus.DURABLE,"reduced candidate state actually durable through J04");
    }
    private static ObservationBatch window(MemoryView memory,int index){
        long start=index*1600L,end=start+200;var observation=new ObservationId(new UUID(808,710+index));
        var raw=new CampEpisodeObservation(observation,new RegionId(ANCHOR.value()),ANCHOR,new EvidenceWindowId(new UUID(808,720+index)),new EpisodeId(new UUID(808,730+index)),new Tick(start),new Tick(end),new DurationTicks(200),ActivityWindows.emptyHistogram());
        var context=new SnapshotBatch(new RequestId(new UUID(808,740+index)),memory.scopes(),new Tick(end),memory.revisions(),new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),memory.anchors(),memory.locations(),List.of(),Map.of()),Set.of(),Set.of(),HealthMode.NORMAL);
        return new ObservationBatch(List.of(observation),List.of(),memory.revisions(),context,List.of(raw),List.of(),Map.of());
    }
    private static BoundedMetadataProjection.Demand demand(boolean pending){return new BoundedMetadataProjection.Demand(Set.of(),Set.of(BoundedMetadataProjection.Demand.cell(new BlockPosition(DIM,1,64,1000),16)),16,
        pending?Set.of(new ScopeRef(ScopeKind.REGION,ANCHOR.value())):Set.of(),pending?Set.of(ANCHOR):Set.of());}
    private static void candidate(BoundedMetadataProjection.View selected,long revision){var memory=selected.memory();
        System.out.println("CANDIDATE_CAMP_READ stored_revision="+revision+" selected_memory="+memory.regions().size()+" selected_anchors="+memory.anchors().size()+" physical_regions="+memory.regionRecords().size());
        check(memory.regions().size()==1&&memory.regions().getFirst().header().revision().value()==revision,"RED_REPRO durable candidate camp memory must survive scoped cold read before RegionRecord exists");
        check(memory.anchors().containsKey(ANCHOR)&&memory.regionRecords().isEmpty(),"candidate anchor remains known without inventing a physical region");}
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("profile NEW isolated directory required");var path=Path.of(args[1]);if(Files.exists(path))throw new IllegalArgumentException("fresh declared QA directory required");var settings=new MemorySettings(profile(Path.of(args[0])),false);
        var options=new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128);var store=await(JournalStore.open(path,WORLD,options));
        try{
            var sourceFactory=DenseMetadataProjectionTests.class.getDeclaredMethod("denseSource");sourceFactory.setAccessible(true);
            var install=DenseMetadataProjectionTests.class.getDeclaredMethod("install",JournalStore.class,LocationImport.class);install.setAccessible(true);
            install.invoke(null,store,(LocationImport)sourceFactory.invoke(null));
            check(!store.metadataSnapshot().orElseThrow().generations().isEmpty(),"actual large G0 generation is active, enabling native scoped-reader path");
            var anchor=new AnchorAggregate(new AnchorRecord(new AggregateHeader(1,ANCHOR.value(),new Revision(1),new Sequence(0),new Tick(0)),ANCHOR,Optional.empty(),Set.of(),DIM,new BlockPosition(DIM,1,64,1000),"region.camp_radius_blocks",Set.of(new TagId("proutlost:camp")),AnchorOrigin.CAMP,EvidenceStatus.POSSIBLE,true));append(store,List.of(anchor));
            var before=MemoryProjection.of(new ScopeSet(WORLD,Set.of(new ScopeRef(ScopeKind.REGION,ANCHOR.value()))),List.of(anchor));
            var input=window(before,0);var reduced=new MemoryReducer(settings).reduce(before,input,new DirectorClock(new Tick(200),0,java.time.Instant.EPOCH));
            check(reduced.aggregateChanges().stream().anyMatch(c->c.next() instanceof RegionMemoryAggregate)&&reduced.aggregateChanges().stream().noneMatch(c->c.next() instanceof RegionAggregate),"first genuine synthetic window creates only candidate memory below discovery gate");
            append(store,reduced.aggregateChanges().stream().map(AggregateChange::next).toList());
            var snapshot=store.metadataSnapshot().orElseThrow();check(store.metadataRevision(snapshot,new AggregateKey(AggregateKind.REGION_MEMORY,ANCHOR.value())).value()==1,"candidate memory revision1 exists in actual J04");
            candidate(await(BoundedMetadataProjection.load(store,snapshot,demand(false),settings)),1);
            candidate(await(BoundedMetadataProjection.load(store,snapshot,demand(true),settings)),1);
            await(store.checkpoint());
        }finally{await(store.closeAsync());}
        int confirmedVisits;
        var cold=await(JournalStore.open(path,WORLD,options));try{
            var selected=await(BoundedMetadataProjection.load(cold,cold.metadataSnapshot().orElseThrow(),demand(false),settings));candidate(selected,1);
            var input=window(selected.memory(),1);var reduced=new MemoryReducer(settings).reduce(selected.memory(),input,new DirectorClock(new Tick(1800),0,java.time.Instant.EPOCH));
            append(cold,reduced.aggregateChanges().stream().map(AggregateChange::next).toList());
            var next=await(BoundedMetadataProjection.load(cold,cold.metadataSnapshot().orElseThrow(),demand(false),settings));candidate(next,2);
            check(next.memory().regions().getFirst().activity().camps().get(ANCHOR).independentVisits()==2,"second native reduction keeps first durable visit and independent anchor/memory CAS");
            check(next.currentHeads(cold,cold.metadataSnapshot().orElseThrow()),"candidate coverage is authoritative only at exact current heads");
            int visits=2;
            while(next.memory().regionRecords().isEmpty()&&visits<16){
                var more=window(next.memory(),visits);long end=visits*1600L+200;
                var transition=new MemoryReducer(settings).reduce(next.memory(),more,new DirectorClock(new Tick(end),0,java.time.Instant.EPOCH));
                append(cold,transition.aggregateChanges().stream().map(AggregateChange::next).toList());visits++;
                next=await(BoundedMetadataProjection.load(cold,cold.metadataSnapshot().orElseThrow(),demand(false),settings));
                check(next.memory().regions().getFirst().activity().camps().get(ANCHOR).independentVisits()==visits,"successive actual durable windows accumulate instead of restarting candidate");
            }
            confirmedVisits=visits;
            var discovered=next.memory().regionRecords().get(new RegionId(ANCHOR.value()));var confirmed=next.memory().anchors().get(ANCHOR);
            check(discovered!=null&&discovered.status()==RegionStatus.ACTIVE&&discovered.anchorIds().contains(ANCHOR),"configured genuine routine gate eventually creates actual RegionRecord through normal reducer");
            check(confirmed.evidence()==EvidenceStatus.CONFIRMED&&confirmed.regionIds().equals(Set.of(discovered.regionId())),"native discovery atomically confirms and attaches the original anchor");
            System.out.println("CANDIDATE_CAMP_DISCOVERY durable_visits="+visits+" actual_region_revision="+discovered.header().revision().value()+" anchor_revision="+confirmed.header().revision().value());
            await(cold.checkpoint());
        }finally{await(cold.closeAsync());}
        var finalCold=await(JournalStore.open(path,WORLD,options));try{
            var recovered=await(BoundedMetadataProjection.load(finalCold,finalCold.metadataSnapshot().orElseThrow(),demand(false),settings));
            check(recovered.memory().regionRecords().containsKey(new RegionId(ANCHOR.value()))&&recovered.memory().anchors().get(ANCHOR).evidence()==EvidenceStatus.CONFIRMED,"discovered real region and confirmed anchor survive another fresh J04 session");
            check(recovered.memory().regions().getFirst().activity().camps().get(ANCHOR).independentVisits()==confirmedVisits,"all consumed candidate windows remain durable after discovery/reopen");
        }finally{await(finalCold.closeAsync());}
        System.out.println("PASS CandidateCampColdReadTests assertions="+assertions+" scope=synthetic_actual_reducer_J04_no_Minecraft_physical_witness");
    }
}
