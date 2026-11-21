package fr.proutlost.director.memory;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.admin.server.GlobalRegionTagCensus;
import fr.proutlost.director.integration.locations.StandaloneLocationImporter;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Explicit synthetic actual-J04 current tag search; no physical Contra claim. */
public final class GlobalRegionTagCensusTests {
    private static final WorldId WORLD=new WorldId(new UUID(0x5030303844454e53L,1));
    private static int assertions;
    private static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private static <T>T await(CompletionStage<T> pending)throws Exception{return pending.toCompletableFuture().get(60,TimeUnit.SECONDS);}
    private static AggregateHeader next(AggregateHeader before){return new AggregateHeader(1,before.id(),before.revision().plus(1),before.lastSequence(),new Tick(before.updatedTick().value()+1));}
    private static void append(JournalStore store,AggregateState state)throws Exception{
        check(await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(new DurableAggregate(state)),List.of()))).ackStatus()==AckStatus.DURABLE,"actual synthetic J04 update durable");
    }
    private static LocationImport source()throws Exception{
        var factory=DenseMetadataProjectionTests.class.getDeclaredMethod("denseSource");factory.setAccessible(true);var original=(LocationImport)factory.invoke(null);
        var r=original.regions().getFirst();var region=new ImportedRegion(r.regionId(),r.dimension(),r.bounds(),Set.of(new TagId("proutlost:clearing")));
        var l=original.locations().getFirst();var locations=new ArrayList<ImportedLocation>();locations.add(new ImportedLocation(l.locationId(),l.externalId(),l.regionIds(),l.dimension(),l.bounds(),Set.of(new TagId("proutlost:dungeon"))));
        locations.add(new ImportedLocation(new LocationId(new UUID(54,1)),"unanchored_qa",Set.of(r.regionId()),r.dimension(),r.bounds(),Set.of(new TagId("proutlost:quiet"))));
        var draft=new LocationImport(1,WORLD,original.sourceNamespace(),original.exportRevision(),new Fingerprint("0".repeat(64)),List.of(region),locations,original.anchors());
        return new LocationImport(1,WORLD,draft.sourceNamespace(),draft.exportRevision(),StandaloneLocationImporter.digest(draft),draft.regions(),draft.locations(),draft.anchors());
    }
    private static AggregateState read(JournalStore store,MetadataReadSnapshot snapshot,AggregateKey key)throws Exception{
        var query=new StoreQuery(WORLD,Set.of(key),new Sequence(0),Optional.empty(),1);var result=await(store.metadataReads().readMetadata(snapshot,query));ReadContracts.validate(result,query,snapshot.session());return result.page().orElseThrow().aggregates().getFirst().state();
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("NEW isolated result directory required");var path=Path.of(args[0]);if(Files.exists(path))throw new IllegalArgumentException("fresh synthetic directory required");
        var options=new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128);var source=source();var region=source.regions().getFirst().regionId();
        var store=await(JournalStore.open(path,WORLD,options));Map<RegionId,Set<TagId>> expected;
        try{
            var install=DenseMetadataProjectionTests.class.getDeclaredMethod("install",JournalStore.class,LocationImport.class);install.setAccessible(true);install.invoke(null,store,source);
            var pinned=store.metadataReads().snapshot().orElseThrow();var initial=await(GlobalRegionTagCensus.load(store,pinned));
            check(initial.get(region).containsAll(Set.of(new TagId("proutlost:clearing"),new TagId("proutlost:dungeon"),new TagId("proutlost:quiet"),new TagId("proutlost:structure"),new TagId("proutlost:anchor_center"))),"source region/location including unanchored and cold anchor tags all join current region");
            check(store.metadataCachedEntries()<=512,"full600-anchor fold preserves J04 cache512");
            var anchor=((AnchorAggregate)read(store,pinned,new AggregateKey(AggregateKind.ANCHOR,source.anchors().getLast().anchorId().value()))).value();
            append(store,new AnchorAggregate(new AnchorRecord(next(anchor.header()),anchor.anchorId(),anchor.locationId(),anchor.regionIds(),anchor.dimension(),anchor.position(),anchor.radiusProfile(),anchor.tags(),anchor.origin(),anchor.evidence(),false)));
            var location=((LocationAggregate)read(store,pinned,new AggregateKey(AggregateKind.LOCATION,new UUID(54,1)))).value();
            append(store,new LocationAggregate(new LocationRecord(next(location.header()),location.locationId(),location.sourceNamespace(),location.externalId(),location.dimension(),location.bounds(),location.tags(),location.anchorIds(),location.importRevision(),false)));
            var current=await(GlobalRegionTagCensus.load(store,store.metadataReads().snapshot().orElseThrow()));
            check(!current.get(region).contains(new TagId("proutlost:structure"))&&!current.get(region).contains(new TagId("proutlost:quiet")),"inactive anchor/location bodies do not contribute current tags");
            check(await(GlobalRegionTagCensus.load(store,pinned)).equals(initial),"old pinned tag census remains coherent after runtime tombstones");
            var runtimeId=new AnchorId(new UUID(55,1));var dimension=source.regions().getFirst().dimension();
            append(store,new AnchorAggregate(new AnchorRecord(new AggregateHeader(1,runtimeId.value(),new Revision(1),new Sequence(0),new Tick(3)),runtimeId,Optional.empty(),Set.of(region),dimension,
                new BlockPosition(dimension,1,64,1),"region.camp_radius_blocks",Set.of(new TagId("proutlost:abandoned_camp")),AnchorOrigin.CAMP,EvidenceStatus.CONFIRMED,true)));
            current=await(GlobalRegionTagCensus.load(store,store.metadataReads().snapshot().orElseThrow()));
            check(current.get(region).contains(new TagId("proutlost:abandoned_camp")),"actual unlisted runtime anchor contributes current regional tag");
            check(await(store.metadataReads().readMetadataSource(store.metadataReads().snapshot().orElseThrow(),source.sourceNamespace())).orElseThrow().equals(source),"tag index never rewrites original authored G0 source");
            expected=current;await(store.checkpoint());
        }finally{await(store.closeAsync());}
        var cold=await(JournalStore.open(path,WORLD,options));try{
            var snapshot=cold.metadataReads().snapshot().orElseThrow();check(await(GlobalRegionTagCensus.load(cold,snapshot)).equals(expected),"checkpoint/new J04 session restores exact source and current tags");
            var location=((LocationAggregate)read(cold,snapshot,new AggregateKey(AggregateKind.LOCATION,source.locations().getFirst().locationId().value()))).value();
            append(cold,new LocationAggregate(new LocationRecord(next(location.header()),location.locationId(),location.sourceNamespace(),location.externalId(),location.dimension(),location.bounds(),Set.of(new TagId("proutlost:quiet")),location.anchorIds(),location.importRevision(),true)));
            try{await(GlobalRegionTagCensus.load(cold,cold.metadataReads().snapshot().orElseThrow()));throw new AssertionError("source/body corruption accepted as tag absence");}
            catch(ExecutionException failure){check(failure.getCause() instanceof IllegalStateException,"source/body mismatch refuses search instead of false empty result");}
            var record=((RegionAggregate)read(cold,cold.metadataReads().snapshot().orElseThrow(),new AggregateKey(AggregateKind.REGION,region.value()))).value();
            append(cold,new RegionAggregate(new RegionRecord(next(record.header()),record.regionId(),record.dimension(),RegionStatus.ARCHIVED,record.supportShape(),record.anchorIds(),record.importance(),record.parentIds(),record.aliases(),record.membershipRevision(),record.personality(),record.memoryRefs())));
            check(await(GlobalRegionTagCensus.load(cold,cold.metadataReads().snapshot().orElseThrow())).isEmpty(),"archived regions carry no active tag-search matches");
        }finally{await(cold.closeAsync());}
        System.out.println("PASS GlobalRegionTagCensusTests assertions="+assertions+" scope=synthetic_actual_J04_sources_pages96_cache512_no_Minecraft_no_Contra");
    }
}
