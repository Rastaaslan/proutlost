package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.bootstrap.DirectorNativeLocationGenerationPlan;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Declared synthetic J04 visible-head transitions, not canonical author data,
 * a physical survey, Minecraft gameplay or a performance measurement. */
public final class MetadataSelectionCensusTests {
    private static final WorldId WORLD=new WorldId(new UUID(808,1201));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final ResourceId SOURCE=new ResourceId("qa:selection_census");
    private static final Set<AggregateKind> KINDS=Set.of(AggregateKind.REGION,AggregateKind.REGION_MEMORY);
    private static final StoreOptions OPTIONS=new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128);
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static <T>T await(CompletionStage<T> value)throws Exception{return value.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static Map<AggregateKind,Sequence> token(JournalStore store,MetadataReadSnapshot snapshot){
        return ((MetadataSelectionReadPort)store.metadataReads()).metadataSelectionVersions(snapshot,KINDS).orElseThrow();
    }
    private static Map<AggregateKind,Sequence> token(JournalStore store){return token(store,store.metadataSnapshot().orElseThrow());}
    private static void append(JournalStore store,List<DurableRecord> records,List<BlobContent> blobs)throws Exception{
        check(await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),records,blobs))).ackStatus()==AckStatus.DURABLE,"real J04 append durable");
    }
    private static void append(JournalStore store,AggregateState row)throws Exception{append(store,List.of(new DurableAggregate(row)),List.of());}
    private static AggregateHeader header(UUID id,long revision){return new AggregateHeader(1,id,new Revision(revision),new Sequence(0),new Tick(revision));}
    private static LocationImport source(){
        var regions=List.of(new ImportedRegion(new RegionId(new UUID(808,1202)),DIM,new ImportBounds(0,0,0,16,128,16),Set.of()),
            new ImportedRegion(new RegionId(new UUID(808,1203)),DIM,new ImportBounds(10000,0,0,10016,128,16),Set.of()));
        var draft=new LocationImport(1,WORLD,SOURCE,new Revision(1),new Fingerprint("0".repeat(64)),regions,List.of(),List.of());
        return new LocationImport(1,WORLD,SOURCE,new Revision(1),LocationTransferContract.digest(draft),regions,List.of(),List.of());
    }
    private static List<AggregateState> install(JournalStore store)throws Exception{
        var input=source();var transfer=LocationTransferContract.prepare(input);var before=token(store);var pinned=store.metadataSnapshot().orElseThrow();
        for(var fragment:transfer.pages()){
            var blob=BlobContent.capture(new ResourceId("proutlost_director:location_transfer_fragment"),1,Base64.getDecoder().decode(fragment.bytesBase64()));
            append(store,List.of(new DurableMetadataSourcePage(transfer.manifest(),fragment.pageIndex(),blob.reference())),List.of(blob));
        }
        var plan=new DirectorNativeLocationGenerationPlan(WORLD,input,null,new Tick(1),key->store.metadataOwner(pinned,key));
        for(int n=0;n<plan.pageCount();n++){
            var planned=plan.planPage(plan.pageKeys(n),Map.of());
            var page=new MetadataGenerationPage(LocationTransferContract.identity(transfer.manifest()),SOURCE,new Revision(1),input.exportDigest(),n,plan.pageCount(),planned.states(),planned.beforeRevisions());
            var blob=BlobContent.capture(new ResourceId("proutlost_director:metadata_generation_page"),1,new ModelCodec<>(MetadataGenerationPage.class).encode(page));
            append(store,List.of(new DurableMetadataStage(transfer.manifest(),n,plan.pageCount(),blob.reference())),List.of(blob));
        }
        check(token(store).equals(before),"hidden source and metadata pages never enter visible semantic census");
        append(store,List.of(new DurableMetadataActivation(transfer.manifest(),Optional.empty(),plan.pageCount())),List.of());
        var snapshot=store.metadataSnapshot().orElseThrow();var changed=token(store,snapshot);
        check(changed.get(AggregateKind.REGION).value()>0,"atomic active REGION selection enters census");
        check(changed.get(AggregateKind.REGION_MEMORY).value()==0,"new empty cold memories add no timed camp dependency");
        var page=store.metadataKeysPage(snapshot,KINDS,Optional.empty(),96).orElseThrow();
        var query=new StoreQuery(WORLD,Set.copyOf(page.keys()),new Sequence(0),Optional.empty(),96);var read=await(store.readMetadata(snapshot,query));
        ReadContracts.validate(read,query,snapshot.session());check(read.status()==ReadStatus.AVAILABLE,"actual activated cold rows available");
        check(token(store).equals(changed),"hydrating active cold bodies never changes semantic token");
        return read.page().orElseThrow().aggregates().stream().map(RestoredAggregate::state).toList();
    }
    private static RegionRecord neutralUpdate(RegionRecord v){return new RegionRecord(header(v.header().id(),v.header().revision().value()+1),v.regionId(),v.dimension(),v.status(),v.supportShape(),v.anchorIds(),new UnitValue(.75),v.parentIds(),v.aliases(),v.membershipRevision(),new RegionPersonality(Map.of(RegionTrait.values()[0],new UnitValue(.3))),v.memoryRefs());}
    private static RegionRecord semanticUpdate(RegionRecord v,int field){
        var dimension=field==0?new DimensionId("minecraft:the_nether"):v.dimension();
        var shape=field==0?new SupportShape(List.of(new Aabb(dimension,10000,0,0,10016,128,16)),List.of(),List.of()):
            field==1?new SupportShape(List.of(new Aabb(dimension,0,0,0,16,128,16)),List.of(),List.of()):v.supportShape();
        return new RegionRecord(header(v.header().id(),v.header().revision().value()+1),v.regionId(),dimension,field==2?RegionStatus.DORMANT:v.status(),shape,
            field==3?Set.of(new AnchorId(new UUID(808,1220))):v.anchorIds(),v.importance(),field==4?Set.of(new RegionId(new UUID(808,1221))):v.parentIds(),
            field==5?Set.of(new RegionId(new UUID(808,1222))):v.aliases(),field==6?v.membershipRevision().plus(1):v.membershipRevision(),v.personality(),
            field==7?Set.of(new MemoryId(new UUID(808,1223))):v.memoryRefs());
    }
    private static RegionMemory memory(RegionId id,long revision,Set<AnchorId> camps){
        var routines=new HashMap<AnchorId,CampRoutineMemory>();
        for(var anchor:camps)routines.put(anchor,new CampRoutineMemory(anchor,List.of(),revision,new DurationTicks(revision),
            new ActivityHistogram(Map.of(),Map.of(),Map.of(),Map.of()),new UnitValue(.5)));
        return new RegionMemory(header(id.value(),revision),id,Map.of(),new Tick(revision),new Tick(0),Set.of(),Set.of(),new RegionActivityState(routines,Map.of()));
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("NEW isolated result directory required");
        var path=Path.of(args[0]);if(Files.exists(path))throw new IllegalArgumentException("fresh synthetic test directory required");
        var store=await(JournalStore.open(path,WORLD,OPTIONS));Map<AggregateKind,Sequence> finalTokens;
        MetadataReadSnapshot pinned;Map<AggregateKind,Sequence> pinnedTokens;
        try{
            var rows=install(store);var region=rows.stream().filter(row->row instanceof RegionAggregate r&&r.value().regionId().equals(source().regions().get(1).regionId())).map(row->((RegionAggregate)row).value()).findFirst().orElseThrow();
            pinned=store.metadataSnapshot().orElseThrow();pinnedTokens=token(store,pinned);
            var exactBefore=store.metadataHeadVersions(store.metadataSnapshot().orElseThrow(),KINDS).orElseThrow();
            region=neutralUpdate(region);append(store,new RegionAggregate(region));
            check(token(store).equals(pinnedTokens),"cold override with only header/personality/importance preserves spatial selection");
            check(!store.metadataHeadVersions(store.metadataSnapshot().orElseThrow(),KINDS).orElseThrow().equals(exactBefore),"ordinary exact head version still changes; semantics are a distinct API");
            for(int field=0;field<8;field++){
                var before=token(store);region=semanticUpdate(region,field);append(store,new RegionAggregate(region));
                var after=token(store);check(!after.get(AggregateKind.REGION).equals(before.get(AggregateKind.REGION)),"spatial/membership field change invalidates semantic REGION census field="+field);
                check(after.get(AggregateKind.REGION_MEMORY).equals(before.get(AggregateKind.REGION_MEMORY)),"REGION geometry does not rewrite camp-reference census");
            }
            var memoryId=source().regions().get(1).regionId();var noCamp=token(store);append(store,new RegionMemoryAggregate(memory(memoryId,2,Set.of())));
            check(token(store).equals(noCamp),"existing empty memory counter update is not a selection change");
            var absentId=new RegionId(new UUID(808,1240));append(store,new RegionMemoryAggregate(memory(absentId,1,Set.of())));
            check(token(store).equals(noCamp),"new empty offscope memory is guarded by scoped absence, not global timed census");
            var campA=new AnchorId(new UUID(808,1241));var campB=new AnchorId(new UUID(808,1242));
            append(store,new RegionMemoryAggregate(memory(memoryId,3,Set.of(campA))));var firstCamp=token(store);
            check(!firstCamp.get(AggregateKind.REGION_MEMORY).equals(noCamp.get(AggregateKind.REGION_MEMORY)),"first CAMP key creates timed dependency and invalidates");
            append(store,new RegionMemoryAggregate(memory(memoryId,4,Set.of(campA))));
            check(token(store).equals(firstCamp),"same camp key with changing counters/windows leaves selection stable");
            append(store,new RegionMemoryAggregate(memory(memoryId,5,Set.of(campB))));var switched=token(store);
            check(!switched.get(AggregateKind.REGION_MEMORY).equals(firstCamp.get(AggregateKind.REGION_MEMORY)),"camp identity replacement invalidates timed dependency");
            append(store,new RegionMemoryAggregate(memory(memoryId,6,Set.of())));finalTokens=token(store);
            check(!finalTokens.get(AggregateKind.REGION_MEMORY).equals(switched.get(AggregateKind.REGION_MEMORY)),"last camp removal invalidates timed dependency");
            check(token(store,pinned).equals(pinnedTokens),"pinned semantic maps remain unchanged across all visible updates");
            check(store.metadataCachedEntries()<=512,"semantic index retains no cold bodies above cache512");
            try{store.metadataSelectionVersions(store.metadataSnapshot().orElseThrow(),Set.of(AggregateKind.WORLD));throw new AssertionError("unrelated semantic kind accepted");}catch(IllegalArgumentException expected){checks++;}
            await(store.checkpoint());
        }finally{await(store.closeAsync());}
        var cold=await(JournalStore.open(path,WORLD,OPTIONS));try{
            check(token(cold).equals(finalTokens),"checkpoint/new J04 session reconstructs exact writer-derived semantic tokens");
            check(((MetadataSelectionReadPort)cold.metadataReads()).metadataSelectionVersions(pinned,KINDS).isEmpty(),"new session refuses prior store opaque snapshot");
        }finally{await(cold.closeAsync());}
        System.out.println("PASS MetadataSelectionCensusTests assertions="+checks+" scope=synthetic_actual_J04_no_Minecraft_no_performance_claim");
    }
}
