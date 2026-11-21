package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.*;
import fr.proutlost.director.memory.MemoryProjection;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Synthetic J04/J01 revision domains, not a native observation or canonical catalogue.
 * RegionRecord and RegionMemory have independent headers; logical REGION scope CAS
 * is advanced by real DurableMemoryDelta records. It must never move backwards.
 */
public final class MetadataScopeCasTests {
    private MetadataScopeCasTests(){}
    private static final WorldId WORLD=new WorldId(new UUID(9308,1));
    private static final RegionId REGION=new RegionId(new UUID(9308,2));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final ResourceId SOURCE=new ResourceId("fixture:metadata_scope_cas");
    private static final ScopeRef SCOPE=new ScopeRef(ScopeKind.REGION,REGION.value());
    private static final AggregateKey REGION_KEY=new AggregateKey(AggregateKind.REGION,REGION.value());
    private static final AggregateKey MEMORY_KEY=new AggregateKey(AggregateKind.REGION_MEMORY,REGION.value());
    private static final StoreOptions OPTIONS=new StoreOptions(1_048_620,4_194_304,16_777_216,33_554_432,32,0,128);
    private static int assertions;
    private static void check(boolean condition,String label){assertions++;if(!condition)throw new AssertionError(label);}
    private static <T>T await(CompletionStage<T> result)throws Exception{return result.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static LocationImport source(long revision){
        var regions=List.of(new ImportedRegion(REGION,DIM,new ImportBounds(0,0,0,1,1,1),Set.of()));
        var raw=new LocationImport(1,WORLD,SOURCE,new Revision(revision),new Fingerprint("0".repeat(64)),regions,List.of(),List.of());
        return new LocationImport(1,WORLD,SOURCE,raw.exportRevision(),StandaloneLocationImporter.digest(raw),regions,List.of(),List.of());
    }
    private static RegionAggregate region(long revision,long tick){
        return new RegionAggregate(new RegionRecord(new AggregateHeader(1,REGION.value(),new Revision(revision),new Sequence(0),new Tick(tick)),REGION,DIM,RegionStatus.ACTIVE,
            new SupportShape(List.of(new Aabb(DIM,0,0,0,1,1,1)),List.of(),List.of()),Set.of(),new UnitValue(0),Set.of(),Set.of(),new Revision(revision),new RegionPersonality(Map.of()),Set.of(new MemoryId(REGION.value()))));
    }
    private static DecayValue visit(long revision){return new DecayValue(revision,new Tick(revision*10),"fixture.visit_half_life",revision,new UnitValue(1));}
    private static RegionMemoryAggregate memory(long revision){
        Map<MemoryComponent,DecayValue> components=revision==1?Map.of():Map.of(MemoryComponent.VISIT,visit(revision));
        return new RegionMemoryAggregate(new RegionMemory(new AggregateHeader(1,REGION.value(),new Revision(revision),new Sequence(0),new Tick(revision==1?10:revision*10)),REGION,
            components,new Tick(revision==1?0:revision*10),new Tick(0),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of())));
    }
    private static CommitAck append(JournalStore store,List<DurableRecord> records,List<BlobContent> blobs,Map<ScopeRef,Revision> expected)throws Exception{
        return await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),expected,records,blobs)));
    }
    private static void durable(CommitAck ack,String label){check(ack.ackStatus()==AckStatus.DURABLE&&ack.durableSequence().value()>0,label);}
    private static LocationTransferManifest stage(JournalStore store,LocationImport source,List<AggregateState> states,Map<AggregateKey,Revision> guards)throws Exception{
        var prepared=LocationTransfer.prepare(source);
        for(var raw:prepared.pages()){
            var blob=BlobContent.capture(new ResourceId("proutlost_director:location_transfer_fragment"),1,Base64.getDecoder().decode(raw.bytesBase64()));
            durable(append(store,List.of(new DurableMetadataSourcePage(prepared.manifest(),raw.pageIndex(),blob.reference())),List.of(blob),Map.of()),"original raw source stage genuinely durable");
        }
        var page=new MetadataGenerationPage(LocationTransfer.identity(prepared.manifest()),SOURCE,source.exportRevision(),source.exportDigest(),0,1,states,guards);
        var blob=BlobContent.capture(new ResourceId("proutlost_director:metadata_generation_page"),1,new ModelCodec<>(MetadataGenerationPage.class).encode(page));
        durable(append(store,List.of(new DurableMetadataStage(prepared.manifest(),0,1,blob.reference())),List.of(blob),Map.of()),"hidden aggregate plan page genuinely durable");
        return prepared.manifest();
    }
    private static CommitAck memoryDelta(JournalStore store,long before)throws Exception{
        long after=before+1;
        return append(store,List.of(new DurableAggregate(memory(after)),new DurableMemoryDelta(new AggregateDelta(SCOPE,new Revision(before),new Revision(after),Map.of(MemoryComponent.VISIT,visit(after))))),List.of(),Map.of(SCOPE,new Revision(before)));
    }
    private static MemoryView actualMemory(JournalStore store)throws Exception{
        var snapshot=store.metadataSnapshot().orElseThrow();var query=new StoreQuery(WORLD,Set.of(REGION_KEY,MEMORY_KEY),new Sequence(0),Optional.empty(),2);
        var read=await(store.readMetadata(snapshot,query));ReadContracts.validate(read,query,snapshot.session());
        check(read.status()==ReadStatus.AVAILABLE&&!read.page().orElseThrow().hasMore(),"both actual current J04 heads are available at one pinned cut");
        var states=read.page().orElseThrow().aggregates().stream().map(RestoredAggregate::state).toList();
        check(states.size()==2,"actual region and memory heads both present");
        return MemoryProjection.of(new ScopeSet(WORLD,Set.of(new ScopeRef(ScopeKind.WORLD,WORLD.value()))),states);
    }
    private static void positiveIndependentHeaders(Path root)throws Exception{
        Files.createDirectories(root);Path directory=root.resolve("store");var store=await(JournalStore.open(directory,WORLD,OPTIONS));
        try{
            var original=stage(store,source(1),List.of(region(1,10),memory(1)),Map.of(REGION_KEY,new Revision(0),MEMORY_KEY,new Revision(0)));
            durable(append(store,List.of(new DurableMetadataActivation(original,Optional.empty(),1)),List.of(),Map.of(SCOPE,new Revision(0))),"positive initial generation is actually durable");
            // Deliberately no DurableMemoryDelta: the independent aggregate header
            // advances while the actual logical REGION scope remains one.
            for(long revision=2;revision<=5;revision++)durable(append(store,List.of(new DurableAggregate(memory(revision))),List.of(),Map.of(SCOPE,new Revision(1))),"memory header-only update preserves exact logical REGION scope one");
            var before=actualMemory(store);
            check(before.regionRecords().get(REGION).header().revision().value()==1&&before.regions().getFirst().header().revision().value()==5,"positive independent region/memory headers are one/five");
            var next=stage(store,source(2),List.of(region(2,60)),Map.of(REGION_KEY,new Revision(1),MEMORY_KEY,new Revision(5)));
            var applied=append(store,List.of(new DurableMetadataActivation(next,Optional.of(LocationTransfer.identity(original)),1)),List.of(),Map.of(SCOPE,new Revision(1)));
            durable(applied,"metadata activation uses actual logical scope one rather than unrelated memory header five");
            var after=actualMemory(store);
            check(after.regionRecords().get(REGION).header().revision().value()==2,"positive new RegionRecord revision two is really visible");
            check(after.regions().getFirst().header().revision().value()==5&&after.regions().getFirst().components().get(MemoryComponent.VISIT).equals(visit(5)),"positive activation preserves exact independent RegionMemory five and VISIT");
            check(store.metadataSnapshot().orElseThrow().generations().get(SOURCE).equals(LocationTransfer.identity(next)),"positive complete new source generation is actually active");
            System.out.println("INDEPENDENT_HEADER_ONLY_MEMORY_REV=5 ACTUAL_SCOPE_BEFORE=1 ACTIVATION="+applied.ackStatus()+" REGION_RECORD_AFTER=2 MEMORY_VISIT_RETAINED=true");
            await(store.checkpoint());
        }finally{await(store.closeAsync());}
        store=await(JournalStore.open(directory,WORLD,OPTIONS));
        try{var cold=actualMemory(store);
            check(cold.regionRecords().get(REGION).header().revision().value()==2&&cold.regions().getFirst().header().revision().value()==5,"positive independent header revisions survive cold restart");
            check(cold.regions().getFirst().components().get(MemoryComponent.VISIT).equals(visit(5)),"positive retained VISIT survives cold restart");
            check(await(store.readMetadataSource(store.metadataSnapshot().orElseThrow(),SOURCE)).orElseThrow().equals(source(2)),"positive exact new source survives cold restart");
        }finally{await(store.closeAsync());}
    }
    public static void main(String[] args)throws Exception{
        boolean fixed=args.length>0&&args[0].equals("fixed");Path root=args.length>1?Path.of(args[1]):Files.createTempDirectory("P008-metadata-scope-cas-");Files.createDirectories(root);
        var store=await(JournalStore.open(root.resolve("store"),WORLD,OPTIONS));
        try{
            check(!store.readOnlySafe(),"actual private J04 is writable");
            var original=stage(store,source(1),List.of(region(1,10),memory(1)),Map.of(REGION_KEY,new Revision(0),MEMORY_KEY,new Revision(0)));
            durable(append(store,List.of(new DurableMetadataActivation(original,Optional.empty(),1)),List.of(),Map.of(SCOPE,new Revision(0))),"initial real generation activates region and memory rev1");
            for(long before=1;before<=4;before++)durable(memoryDelta(store,before),"actual RegionMemory plus DurableMemoryDelta advances REGION scope to "+(before+1));
            var before=actualMemory(store);
            check(before.regionRecords().get(REGION).header().revision().value()==1,"metadata RegionRecord revision remains independently at one");
            check(before.regions().getFirst().header().revision().value()==5,"actual RegionMemory header has reached five");
            check(before.revisions().get(SCOPE).value()==5,"actual MemoryProjection REGION revision equals five");
            check(before.regions().getFirst().components().get(MemoryComponent.VISIT).equals(visit(5)),"real VISIT component has survived all four deltas");
            System.out.println("BEFORE_REGION_RECORD_REV=1 REGION_MEMORY_REV=5 MEMORY_PROJECTION_SCOPE_REV="+before.revisions().get(SCOPE).value());
            var proposed=stage(store,source(2),List.of(region(2,60)),Map.of(REGION_KEY,new Revision(1),MEMORY_KEY,new Revision(5)));
            var activation=append(store,List.of(new DurableMetadataActivation(proposed,Optional.of(LocationTransfer.identity(original)),1)),List.of(),Map.of(SCOPE,new Revision(5)));
            check(activation.ackStatus()==(fixed?AckStatus.REJECTED:AckStatus.DURABLE),fixed?"conservative activation refuses divergent logical REGION scope CAS":"reproduction activation genuinely committed from exact scope five");
            var retained=actualMemory(store);
            check(retained.regions().getFirst().components().get(MemoryComponent.VISIT).equals(visit(5)),"metadata import does not fabricate or discard VISIT");
            check(retained.revisions().get(SCOPE).value()==5,"actual memory projection still reports REGION scope five");
            var next=memoryDelta(store,5);
            System.out.println("ACTIVATION="+activation.ackStatus()+" MEMORY_PROJECTION_SCOPE_REV="+retained.revisions().get(SCOPE).value()+" NEXT_MEMORY_DELTA_5_TO_6="+next.ackStatus()+" NEXT_SEQ="+next.durableSequence().value());
            durable(next,"next genuine memory delta five-to-six remains admissible; metadata must not lower logical scope CAS");
            var finalMemory=actualMemory(store);
            check(finalMemory.revisions().get(SCOPE).value()==6&&finalMemory.regions().getFirst().components().get(MemoryComponent.VISIT).equals(visit(6)),"actual next delta and VISIT are coherent at revision six");
            if(fixed){check(store.metadataSnapshot().orElseThrow().generations().get(SOURCE).equals(LocationTransfer.identity(original)),"refused metadata generation leaves old active source intact");
                check(finalMemory.regionRecords().get(REGION).header().revision().value()==1,"conservative refusal leaves original RegionRecord revision intact");}
        }finally{await(store.closeAsync());}
        if(fixed)positiveIndependentHeaders(root.resolve("independent-memory-headers"));
        System.out.println("PASS MetadataScopeCasTests assertions="+assertions+" mode="+(fixed?"fixed":"before")+" scope=ACTUAL_J04_J01_SYNTHETIC_REVISION_DOMAINS_NO_NATIVE_WORLD");
    }
}
