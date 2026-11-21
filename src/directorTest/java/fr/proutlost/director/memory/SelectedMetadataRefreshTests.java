package fr.proutlost.director.memory;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Declared synthetic actual-J04 refresh and selection-change fences.
 * Does not assert Minecraft performance or canonical author decisions. */
public final class SelectedMetadataRefreshTests {
    private static final WorldId WORLD=new WorldId(new UUID(808,1600));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final PlayerId PLAYER=new PlayerId(new UUID(808,1700));
    private static int assertions;
    private static <T>T await(CompletionStage<T> value)throws Exception{return value.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    private static RegionId id(int n){return new RegionId(new UUID(808,1601+n));}
    private static AggregateHeader header(UUID id,long revision){return new AggregateHeader(1,id,new Revision(revision),new Sequence(0),new Tick(revision));}
    private static RegionAggregate region(int n,long revision,boolean near){
        int x=near?0:10000+n*1000;
        return new RegionAggregate(new RegionRecord(header(id(n).value(),revision),id(n),DIM,RegionStatus.ACTIVE,
            new SupportShape(List.of(new Aabb(DIM,x,0,0,x+16,128,16)),List.of(),List.of()),Set.of(),new UnitValue(0),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of(new MemoryId(id(n).value()))));
    }
    private static RegionMemoryAggregate memory(int n,long revision,Map<AnchorId,CampRoutineMemory> camps){return new RegionMemoryAggregate(new RegionMemory(header(id(n).value(),revision),id(n),Map.of(),new Tick(revision),new Tick(0),Set.of(),Set.of(),new RegionActivityState(camps,Map.of())));}
    private static PlayerOperationalAggregate player(PlayerId player,long revision,Optional<RegionId> last){return new PlayerOperationalAggregate(new PlayerOperationalMemory(header(player.value(),revision),player,new DurationTicks(revision),last,Optional.empty(),List.of(),List.of(),Set.of(),new UnitValue(0),Map.of(),Optional.empty(),new Epoch(0)));}
    private static ConfigView profile(Path path)throws Exception{
        var raw=((JsonValue.ObjectValue)((JsonValue.ObjectValue)CanonicalJsonV1.decode(Files.readAllBytes(path))).values().get("values")).values();
        var values=new HashMap<String,JsonValue.Number>();raw.forEach((k,v)->values.put(k,(JsonValue.Number)v));return new ConfigView(new Revision(1),BlobContent.hash(Files.readAllBytes(path)),Calibration.DEFAULT_FOR_TESTING,values);
    }
    private static void append(JournalStore store,List<AggregateState> rows,List<BlobContent> blobs)throws Exception{
        check(await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows.stream().map(row->(DurableRecord)new DurableAggregate(row)).toList(),blobs))).ackStatus()==AckStatus.DURABLE,"actual J04 append durable");
    }
    private static BoundedMetadataProjection.View refresh(JournalStore store,BoundedMetadataProjection.View prior,BoundedMetadataProjection.Demand demand,MemorySettings settings)throws Exception{
        var pinned=store.metadataSnapshot().orElseThrow();var next=await(BoundedMetadataProjection.refresh(store,prior,pinned,demand,settings));
        check(next.snapshot()==pinned,"refresh result retains requested identical opaque pinned handle");
        check(next.currentHeads(store,store.metadataSnapshot().orElseThrow()),"new selected result passes every current head and absence fence");return next;
    }
    private static void exact(JournalStore store,BoundedMetadataProjection.View selected)throws Exception{
        var actual=await(ScopedAggregateCapture.load(store,selected.snapshot(),selected.states().keySet(),selected.states().size()));
        check(actual.states().equals(selected.states()),"every selected body equals actual independent J04 read at reported cut");
    }
    private static DefinitionRef definition(){return new DefinitionRef(new DefinitionId("qa:selected_refresh"),new Revision(1),new Fingerprint("1".repeat(64)));}
    private static EventAggregate event(long revision,EventState state,BlobRef context){return new EventAggregate(new EventInstance(header(new UUID(808,1800),revision),new EventInstanceId(new UUID(808,1800)),definition(),new Epoch(0),state,"qa",new RoleAssignments(Map.of()),Set.of(id(3)),context,Map.of(),Set.of(),Set.of(),Set.of(),Optional.empty(),Set.of(),Optional.empty(),new DurationTicks(revision),new DurationTicks(1000),RecoveryMode.RECOVER,Optional.empty()));}
    private static ChainAggregate chain(long revision,ChainState state,Optional<ResourceId> regional){return new ChainAggregate(new ChainInstance(header(new UUID(808,1801),revision),new ChainId(new UUID(808,1801)),definition(),ChainMode.DYNAMIC,state,Set.of("qa"),Set.of(),Set.of(id(4)),List.of(),List.of(),regional,new UnitValue(0),new Tick(revision)));}
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("profile NEW isolated result directory required");var path=Path.of(args[1]);if(Files.exists(path))throw new IllegalArgumentException("fresh synthetic directory required");
        var settings=new MemorySettings(profile(Path.of(args[0])),false);var options=new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128);
        var store=await(JournalStore.open(path,WORLD,options));try{
            var initial=new ArrayList<AggregateState>();for(int n=0;n<6;n++){initial.add(region(n,1,n==0));if(n!=0)initial.add(memory(n,1,Map.of()));}initial.add(player(PLAYER,1,Optional.empty()));append(store,initial,List.of());
            var demand=new BoundedMetadataProjection.Demand(Set.of(PLAYER),Set.of(BoundedMetadataProjection.Demand.cell(new BlockPosition(DIM,1,64,1),16)),16,Set.of());
            var selected=await(BoundedMetadataProjection.load(store,store.metadataSnapshot().orElseThrow(),demand,settings));
            check(selected.states().size()==2&&selected.memory().regions().isEmpty(),"initial selected region has genuine absent memory");
            append(store,List.of(memory(0,1,Map.of())),List.of());
            check(selected.anchorCoverage().orElseThrow().sameSelection(store,store.metadataSnapshot().orElseThrow()),"empty new memory changes no global selection geometry/camp set");
            selected=refresh(store,selected,demand,settings);check(selected.states().size()==3&&selected.examinedRows()>3,"selected memory absence-to-presence forces complete scan instead of omitted refresh key");
            var before=selected;var r=selected.memory().regionRecords().get(id(0));
            var traits=new RegionAggregate(new RegionRecord(header(r.header().id(),2),r.regionId(),r.dimension(),r.status(),r.supportShape(),r.anchorIds(),new UnitValue(.4),r.parentIds(),r.aliases(),r.membershipRevision(),new RegionPersonality(Map.of(RegionTrait.values()[0],new UnitValue(.5))),r.memoryRefs()));
            append(store,List.of(traits,memory(0,2,Map.of()),player(PLAYER,2,Optional.empty())),List.of());
            check(!before.currentHeads(store,store.metadataSnapshot().orElseThrow()),"old selected bodies never become fresh merely through stable semantic census");
            selected=refresh(store,before,demand,settings);check(selected.examinedRows()==3&&selected.memory().regions().getFirst().header().revision().value()==2,"stable selection refresh reads only three current heads");exact(store,selected);
            check(before.memory().regions().getFirst().header().revision().value()==1,"original selected snapshot remains original after refresh");
            append(store,List.of(memory(0,3,Map.of())),List.of());check(!selected.currentHeads(store,store.metadataSnapshot().orElseThrow()),"post-refresh concurrent selected update still refuses publication");
            selected=refresh(store,selected,demand,settings);check(selected.examinedRows()==3&&selected.memory().regions().getFirst().header().revision().value()==3,"retry rereads current selected memory without full catalogue");
            append(store,List.of(region(1,2,true)),List.of());selected=refresh(store,selected,demand,settings);
            check(selected.memory().regionRecords().containsKey(id(1))&&selected.examinedRows()>selected.states().size(),"cold geometry newly enters cell through full rescan fallback");
            var anchorId=new AnchorId(new UUID(808,1900));var camp=new CampRoutineMemory(anchorId,List.of(),1,new DurationTicks(1),new ActivityHistogram(Map.of(),Map.of(),Map.of(),Map.of()),new UnitValue(.5));
            var anchor=new AnchorAggregate(new AnchorRecord(header(anchorId.value(),1),anchorId,Optional.empty(),Set.of(id(2)),DIM,new BlockPosition(DIM,12001,64,1),"region.camp_radius_blocks",Set.of(new TagId("qa:camp")),AnchorOrigin.CAMP,EvidenceStatus.CONFIRMED,true));
            append(store,List.of(anchor,memory(2,2,Map.of(anchorId,camp))),List.of());selected=refresh(store,selected,demand,settings);
            check(selected.memory().regionRecords().containsKey(id(2))&&selected.memory().anchors().containsKey(anchorId)&&selected.examinedRows()>selected.states().size(),"new distant timed camp forces scope rebuild with actual anchor");
            var context=BlobContent.capture(new ResourceId("qa:event_context"),1,new byte[]{1});append(store,List.of(event(1,EventState.RUNNING,context.reference())),List.of(context));selected=refresh(store,selected,demand,settings);
            check(selected.memory().regionRecords().containsKey(id(3))&&selected.examinedRows()>selected.states().size(),"new active EVENT pins distant real region through full fallback");
            append(store,List.of(event(2,EventState.RESOLVING,context.reference())),List.of());selected=refresh(store,selected,demand,settings);
            check(selected.examinedRows()==selected.states().size(),"EVENT header/phase progress with same pinned regions permits bounded refresh");
            append(store,List.of(event(3,EventState.SUCCEEDED,context.reference())),List.of());selected=refresh(store,selected,demand,settings);
            check(!selected.memory().regionRecords().containsKey(id(3))&&selected.examinedRows()>selected.states().size(),"terminal EVENT releases dependency only after full scope rebuild");
            append(store,List.of(chain(1,ChainState.ACTIVE,Optional.empty())),List.of());selected=refresh(store,selected,demand,settings);
            check(selected.memory().regionRecords().containsKey(id(4)),"new active CHAIN pins its lineage region");
            append(store,List.of(chain(2,ChainState.SETTLED_AS_WORLD_STATE,Optional.of(new ResourceId("qa:regional_state")))),List.of());selected=refresh(store,selected,demand,settings);
            check(selected.memory().regionRecords().containsKey(id(4))&&selected.examinedRows()==selected.states().size(),"settled regional state retains same pinned lineage and bounded refresh");
            append(store,List.of(chain(3,ChainState.SETTLED_AS_WORLD_STATE,Optional.empty())),List.of());selected=refresh(store,selected,demand,settings);
            check(!selected.memory().regionRecords().containsKey(id(4)),"settled CHAIN without regional state releases lineage only through full rebuild");
            append(store,List.of(player(PLAYER,3,Optional.of(id(5)))),List.of());selected=refresh(store,selected,demand,settings);
            check(selected.memory().regionRecords().containsKey(id(5))&&selected.examinedRows()>selected.states().size(),"selected PLAYER last-region change cannot reuse previous scope");
            var other=new PlayerId(new UUID(808,1701));append(store,List.of(player(other,1,Optional.empty())),List.of());selected=refresh(store,selected,demand,settings);
            check(selected.memory().operationalPlayers().size()==2&&selected.examinedRows()>selected.states().size(),"new operational player identity is included after complete scope rebuild");
            var moved=new BoundedMetadataProjection.Demand(Set.of(PLAYER),Set.of(BoundedMetadataProjection.Demand.cell(new BlockPosition(DIM,10001,64,1),16)),16,Set.of());selected=refresh(store,selected,moved,settings);
            check(selected.examinedRows()>selected.states().size()&&selected.demand().equals(moved),"changed actual demand falls back instead of retaining old spatial scope");exact(store,selected);
            var config=settings.configuration();var nextProfile=new ConfigView(config.revision().plus(1),config.provenanceHash(),config.calibration(),config.values());
            var recalibrated=new MemorySettings(nextProfile,false);
            var underNewProfile=await(BoundedMetadataProjection.refresh(store,selected,store.metadataSnapshot().orElseThrow(),moved,recalibrated));
            check(underNewProfile.examinedRows()>underNewProfile.states().size(),"profile revision change forces full scan even with same demand and census");
            check(store.metadataCachedEntries()<=512,"all refreshes and fallback scans preserve cache512");
            await(store.checkpoint());
        }finally{await(store.closeAsync());}
        System.out.println("PASS SelectedMetadataRefreshTests assertions="+assertions+" scope=synthetic_actual_J04_no_Minecraft_no_performance_claim");
    }
}
