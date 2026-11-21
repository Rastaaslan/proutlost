package fr.proutlost.director.memory;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Synthetic actual-J04 reproductions of new cold heads omitted by body-only
 * revision checks. Foreign significant records are declared historical rows;
 * no player census, Minecraft performance or canonical data is fabricated. */
public final class SelectionCensusTests {
    private static final WorldId WORLD=new WorldId(new UUID(808,90));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final ResourceId SOURCE=new ResourceId("test:p008_census");
    private static final StoreOptions OPTIONS=new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128);
    private static int checks;
    private static UUID id(int domain,int n){return new UUID(domain,n);}
    private static AggregateHeader header(UUID id,long revision){return new AggregateHeader(1,id,new Revision(revision),new Sequence(0),new Tick(revision));}
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static <T>T await(CompletionStage<T> pending)throws Exception{return pending.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static ConfigView profile(Path path)throws Exception{
        var raw=((JsonValue.ObjectValue)((JsonValue.ObjectValue)CanonicalJsonV1.decode(Files.readAllBytes(path))).values().get("values")).values();
        var values=new HashMap<String,JsonValue.Number>();raw.forEach((k,v)->values.put(k,(JsonValue.Number)v));
        return new ConfigView(new Revision(1),BlobContent.hash(Files.readAllBytes(path)),Calibration.DEFAULT_FOR_TESTING,values);
    }
    private static void append(JournalStore store,List<AggregateState> rows,List<BlobContent> blobs)throws Exception{
        var records=rows.stream().map(row->(DurableRecord)new DurableAggregate(row)).toList();
        check(await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),records,blobs))).ackStatus()==AckStatus.DURABLE,"fixture append actually durable");
    }
    private static RegionAggregate region(int n,long revision,int x){var id=new RegionId(id(91,n));
        return new RegionAggregate(new RegionRecord(header(id.value(),revision),id,DIM,RegionStatus.ACTIVE,
            new SupportShape(List.of(new Aabb(DIM,x,0,0,x+16,128,16)),List.of(),List.of()),Set.of(),new UnitValue(0),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of(new MemoryId(id.value()))));
    }
    private static RegionMemoryAggregate memory(int n){var id=new RegionId(id(91,n));
        return new RegionMemoryAggregate(new RegionMemory(header(id.value(),1),id,Map.of(),new Tick(0),new Tick(0),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of())));
    }
    private static PlayerOperationalAggregate player(PlayerId id){return new PlayerOperationalAggregate(new PlayerOperationalMemory(header(id.value(),1),id,new DurationTicks(0),Optional.empty(),Optional.empty(),List.of(),List.of(),Set.of(),new UnitValue(0),Map.of(),Optional.empty(),new Epoch(0)));}
    private static PlayerSignificantAggregate significant(PlayerId player,int index,BlobRef outcome){var id=id(94,index);
        return new PlayerSignificantAggregate(new PlayerSignificantMemory(header(id,1),new MemoryId(id),player,new EventInstanceId(id(95,index)),new DefinitionId("test:p008_event"),"witness",PerceptionStatus.RECEIVED,outcome,Optional.empty(),Optional.empty(),Set.of(),Set.of(),new Tick(index)));
    }
    public static void main(String[] args)throws Exception{
        if(args.length<2||args.length>3)throw new IllegalArgumentException("profile NEW-dir [region|significant] required");
        var root=Path.of(args[1]);if(Files.exists(root))throw new IllegalArgumentException("fresh synthetic QA directory required");Files.createDirectories(root);
        var settings=new MemorySettings(profile(Path.of(args[0])),false);
        if(args.length==2||args[2].equals("region"))geometry(root.resolve("geometry"),settings);
        if(args.length==2||args[2].equals("significant"))significance(root.resolve("significant"),settings);
        System.out.println("PASS SelectionCensusTests assertions="+checks+" scope=synthetic_actual_J04_no_Minecraft_performance_claim");
    }
    private static void geometry(Path path,MemorySettings settings)throws Exception{
        var store=await(JournalStore.open(path,WORLD,OPTIONS));try{
            append(store,List.of(region(0,1,0),memory(0),region(1,1,10000),memory(1)),List.of());
            var demand=new BoundedMetadataProjection.Demand(Set.of(),Set.of(BoundedMetadataProjection.Demand.cell(new BlockPosition(DIM,1,64,1),16)),16,Set.of());
            var selected=await(BoundedMetadataProjection.load(store,store.metadataSnapshot().orElseThrow(),demand,settings));
            check(selected.memory().regionRecords().size()==1,"initial spatial selection excludes real distant region");
            append(store,List.of(region(1,2,0)),List.of());
            check(!selected.currentHeads(store,store.metadataSnapshot().orElseThrow()),"RED_REPRO: changed cold region geometry must invalidate selected census without anchor/source-generation update");
            var refreshed=await(BoundedMetadataProjection.load(store,store.metadataSnapshot().orElseThrow(),demand,settings));
            check(refreshed.memory().regionRecords().size()==2,"fresh actual geometry includes newly intersecting region");
        }finally{await(store.closeAsync());}
    }
    private static void significance(Path path,MemorySettings settings)throws Exception{
        var store=await(JournalStore.open(path,WORLD,OPTIONS));var owner=new PlayerId(id(92,1));
        BoundedMetadataProjection.View previous;
        try{
            var outcome=BlobContent.capture(new ResourceId("test:p008_outcome"),1,new byte[]{1});
            append(store,List.of(player(owner)),List.of());
            var demand=new BoundedMetadataProjection.Demand(Set.of(owner),Set.of(),16,Set.of());
            var selected=await(BoundedMetadataProjection.load(store,store.metadataSnapshot().orElseThrow(),demand,settings));
            append(store,List.of(significant(owner,0,outcome.reference())),List.of(outcome));
            check(!selected.currentHeads(store,store.metadataSnapshot().orElseThrow()),"RED_REPRO: new significant head for selected player must invalidate absent top64 census");
            var refreshed=await(BoundedMetadataProjection.load(store,store.metadataSnapshot().orElseThrow(),demand,settings));
            check(refreshed.memory().significantPlayers().size()==1,"fresh selected player significant row is actually visible");
            long pages=store.metadataHydrationPageLoads();
            for(int round=0;round<10;round++){
                var foreign=new ArrayList<AggregateState>();
                for(int n=0;n<25;n++)foreign.add(significant(new PlayerId(id(93,n)),1+round*25+n,outcome.reference()));
                append(store,foreign,List.of());
                check(refreshed.currentHeads(store,store.metadataSnapshot().orElseThrow()),"25 unrelated historical-player updates do not starve selected census, round"+round);
            }
            check(store.metadataHydrationPageLoads()==pages,"current census validation performs no metadata hydration during250 independent writes");
            check(refreshed.memory().significantPlayers().size()==1,"unrelated histories never leak into selected player's memory");
            previous=refreshed;await(store.checkpoint());
            System.out.println("INDEPENDENT_CENSUS_WRITES players=25 rounds=10 durable_significant_rows=250 selection_reloads=0 additional_metadata_page_loads="+(store.metadataHydrationPageLoads()-pages));
        }finally{await(store.closeAsync());}
        var cold=await(JournalStore.open(path,WORLD,OPTIONS));try{
            var snapshot=cold.metadataSnapshot().orElseThrow();
            check(!previous.currentHeads(cold,snapshot),"previous session census cannot authorize reopened J04");
            var demand=new BoundedMetadataProjection.Demand(Set.of(owner),Set.of(),16,Set.of());
            var fresh=await(BoundedMetadataProjection.load(cold,snapshot,demand,settings));
            check(fresh.currentHeads(cold,snapshot)&&fresh.memory().significantPlayers().size()==1,"checkpoint/reopen reconstructs exact selected-player census tokens");
        }finally{await(cold.closeAsync());}
    }
}
