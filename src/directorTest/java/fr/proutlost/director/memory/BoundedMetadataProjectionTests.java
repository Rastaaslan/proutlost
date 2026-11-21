package fr.proutlost.director.memory;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import fr.proutlost.director.adapter.neoforge.server.observation.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit synthetic, worldless QA data through actual J04; no Contra/RUN7 claim. */
public final class BoundedMetadataProjectionTests {
    private static final WorldId WORLD=new WorldId(new UUID(0x5030303852454144L,1));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final ResourceId SOURCE=new ResourceId("test:synthetic_p008_reader");
    private static final StoreOptions OPTIONS=new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128);
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(60,TimeUnit.SECONDS);}
    private static UUID id(int domain,int number){return new UUID(domain,number);}
    private static AggregateHeader header(UUID id,long revision){return new AggregateHeader(1,id,new Revision(revision),new Sequence(0),new Tick(0));}
    private static List<AggregateState> rows(int n,boolean dense){
        int x=dense?0:n*64;
        var region=new RegionId(id(1,n));var location=new LocationId(id(2,n));var anchor=new AnchorId(id(3,n));
        var bounds=new Aabb(DIM,x,0,0,x+16,256,16);
        return List.of(new RegionAggregate(new RegionRecord(header(region.value(),1),region,DIM,RegionStatus.ACTIVE,
            new SupportShape(List.of(bounds),List.of(),List.of()),Set.of(anchor),new UnitValue(0),Set.of(),Set.of(),
            new Revision(1),new RegionPersonality(Map.of()),Set.of(new MemoryId(region.value())))),
            new RegionMemoryAggregate(new RegionMemory(header(region.value(),1),region,Map.of(),new Tick(0),new Tick(0),
                Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of()))),
            new AnchorAggregate(new AnchorRecord(header(anchor.value(),1),anchor,Optional.of(location),Set.of(region),DIM,
                new BlockPosition(DIM,x+1,64,1),"region.camp_radius_blocks",Set.of(new TagId("proutlost:anchor_center")),
                AnchorOrigin.IMPORTED,EvidenceStatus.CONFIRMED,true)),
            new LocationAggregate(new LocationRecord(header(location.value(),1),location,SOURCE,"qa_location_"+n,DIM,bounds,
                Set.of(new TagId("proutlost:structure")),Set.of(anchor),new Revision(1),true)));
    }
    private static void append(JournalStore store,List<AggregateState> states)throws Exception {append(store,states,List.of());}
    private static void append(JournalStore store,List<AggregateState> states,List<BlobContent> blobs)throws Exception {
        List<DurableRecord> records=new ArrayList<>();states.forEach(s->records.add(new DurableAggregate(s)));
        var batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),records,blobs);
        if(await(store.append(batch)).ackStatus()!=AckStatus.DURABLE)throw new AssertionError("fixture actual J04 append refused");
    }
    private static ConfigView configuration(Path file)throws Exception {
        var raw=((JsonValue.ObjectValue)((JsonValue.ObjectValue)CanonicalJsonV1.decode(Files.readAllBytes(file))).values().get("values")).values();
        Map<String,JsonValue.Number> values=new HashMap<>();raw.forEach((k,v)->values.put(k,(JsonValue.Number)v));
        return new ConfigView(new Revision(1),BlobContent.hash(Files.readAllBytes(file)),Calibration.DEFAULT_FOR_TESTING,values);
    }
    private static BoundedMetadataProjection.Demand demand(int index,Set<ScopeRef> pinned){
        return new BoundedMetadataProjection.Demand(Set.of(),Set.of(BoundedMetadataProjection.Demand.cell(
            new BlockPosition(DIM,index*64+1,64,1),16)),16,pinned);
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("profile and NEW isolated result directory required");
        Path root=Path.of(args[1]);if(Files.exists(root))throw new IllegalArgumentException("fresh declared QA directory required");Files.createDirectories(root);
        var settings=new MemorySettings(configuration(Path.of(args[0])),false);
        exactRegionReadCost(root.resolve("exact-scope-cost"),settings);
        largeDemand(root.resolve("sparse"),settings);
        denseRefusal(root.resolve("dense"),settings);
        disconnectedPlayer(root.resolve("disconnected-player"),settings);
        twentyFivePlayers(root.resolve("25x64-selected-memory"),settings);
        unacknowledgedDependencies();
        pendingDiscoveryAnchor(root.resolve("pending-discovery"),settings);
        System.out.println("PASS BoundedMetadataProjectionTests assertions="+checks+" scope=synthetic_actual_J04_no_Minecraft_no_Contra_no_RUN7");
    }
    private static void largeDemand(Path root,MemorySettings settings)throws Exception {
        var store=await(JournalStore.open(root,WORLD,OPTIONS));
        try {
            for(int from=0;from<600;from+=24){List<AggregateState> batch=new ArrayList<>();
                for(int n=from;n<Math.min(600,from+24);n++)batch.addAll(rows(n,false));append(store,batch);}
            var snapshot=store.metadataSnapshot().orElseThrow();
            var far=await(BoundedMetadataProjection.load(store,snapshot,demand(599,Set.of()),settings));
            check(far.examinedRows()==3000,"complete catalogue scanned without first512 truncation");
            check(far.peakPageRows()<=96&&far.states().size()==4,"page and selected working set independently bounded");
            check(far.memory().regionRecords().keySet().equals(Set.of(new RegionId(id(1,599)))),"distant native head selected");
            check(far.memory().locations().keySet().equals(Set.of(new LocationId(id(2,599)))),"referenced location follows actual anchor");
            check(!far.scopes().scopes().contains(new ScopeRef(ScopeKind.WORLD,WORLD.value())),"selected cache never claims complete world");
            var pinned=await(BoundedMetadataProjection.load(store,snapshot,demand(599,Set.of(new ScopeRef(ScopeKind.REGION,id(1,80)))),settings));
            check(pinned.states().size()==6&&pinned.memory().regionRecords().containsKey(new RegionId(id(1,80))),"remote region and memory are pinned without requiring every distant anchor body");
            var original=(RegionAggregate)rows(599,false).getFirst();var before=original.value();
            var replacement=new RegionAggregate(new RegionRecord(header(before.regionId().value(),2),before.regionId(),DIM,RegionStatus.ACTIVE,
                before.supportShape(),before.anchorIds(),new UnitValue(.9),before.parentIds(),before.aliases(),new Revision(2),before.personality(),before.memoryRefs()));
            append(store,List.of(replacement));
            var old=await(BoundedMetadataProjection.load(store,snapshot,demand(599,Set.of()),settings));
            var current=await(BoundedMetadataProjection.load(store,store.metadataSnapshot().orElseThrow(),demand(599,Set.of()),settings));
            check(old.snapshot().cut().equals(snapshot.cut())&&old.memory().regionRecords().get(before.regionId()).importance().value()==0,
                "one pinned cut survives unrelated/newer appends without mixed revisions");
            check(current.memory().regionRecords().get(before.regionId()).importance().value()==.9,
                "fresh demand reads actual newer head");
            var memory=new DurableMemoryPort(store,settings);memory.replaceProjection(current.states().values());
            check(memory.view(current.scopes()).equals(current.memory()),"native memory port publishes selected candidate atomically");
            List<AggregateState> excess=new ArrayList<>();for(int n=0;n<129;n++)excess.addAll(rows(n,true));
            try {memory.replaceProjection(excess);throw new AssertionError("oversize selected candidate accepted");}
            catch(IllegalArgumentException expected){checks++;}
            check(memory.view(current.scopes()).equals(current.memory()),"capacity refusal preserves complete prior projection");
        }finally{await(store.closeAsync());}
        var cold=await(JournalStore.open(root,WORLD,OPTIONS));
        try {
            var view=await(BoundedMetadataProjection.load(cold,cold.metadataSnapshot().orElseThrow(),demand(599,Set.of()),settings));
            check(view.memory().regionRecords().get(new RegionId(id(1,599))).importance().value()==.9,"same-directory fresh J04 session restores distant selected head");
            check(view.states().size()==4,"cold selection remains bounded");
            var retained=cold.metadataSnapshot().orElseThrow();
            List<AggregateState> retirement=new ArrayList<>();
            for(var state:view.states().values()) {
                var h=header(state.header().id(),state.header().revision().value()+1);
                if(state instanceof RegionAggregate r) {var v=r.value();retirement.add(new RegionAggregate(new RegionRecord(h,v.regionId(),v.dimension(),RegionStatus.ARCHIVED,
                    v.supportShape(),v.anchorIds(),v.importance(),v.parentIds(),v.aliases(),v.membershipRevision().plus(1),v.personality(),v.memoryRefs())));}
                else if(state instanceof AnchorAggregate a) {var v=a.value();retirement.add(new AnchorAggregate(new AnchorRecord(h,v.anchorId(),v.locationId(),v.regionIds(),v.dimension(),
                    v.position(),v.radiusProfile(),v.tags(),v.origin(),v.evidence(),false)));}
                else if(state instanceof LocationAggregate l) {var v=l.value();retirement.add(new LocationAggregate(new LocationRecord(h,v.locationId(),v.sourceNamespace(),v.externalId(),
                    v.dimension(),v.bounds(),v.tags(),v.anchorIds(),v.importRevision().plus(1),false)));}
            }
            append(cold,retirement);
            var after=await(BoundedMetadataProjection.load(cold,cold.metadataSnapshot().orElseThrow(),demand(599,Set.of()),settings));
            check(after.states().isEmpty(),"retained inactive tombstones do not occupy active spatial working set");
            var historical=await(BoundedMetadataProjection.load(cold,retained,demand(599,Set.of()),settings));
            check(historical.states().size()==4&&historical.memory().regionRecords().get(new RegionId(id(1,599))).status()==RegionStatus.ACTIVE,
                "retirement keeps the exact pinned previous view readable");
        }finally{await(cold.closeAsync());}
    }
    private static void twentyFivePlayers(Path root,MemorySettings settings)throws Exception {
        var store=await(JournalStore.open(root,WORLD,OPTIONS));
        try {
            var outcome=BlobContent.capture(new ResourceId("test:synthetic_memory_outcome"),1,new byte[]{1});
            var players=new HashSet<PlayerId>();var keys=new HashSet<AggregateKey>();
            for(int n=0;n<25;n++) {
                var player=new PlayerId(id(10,n));players.add(player);var batch=new ArrayList<AggregateState>();
                batch.add(new PlayerOperationalAggregate(new PlayerOperationalMemory(header(player.value(),1),player,new DurationTicks(400),
                    Optional.empty(),Optional.empty(),List.of(),List.of(),Set.of(),new UnitValue(.2),Map.of(),Optional.empty(),new Epoch(0))));
                for(int e=0;e<64;e++) {
                    var identity=id(11,n*64+e);
                    batch.add(new PlayerSignificantAggregate(new PlayerSignificantMemory(header(identity,1),new MemoryId(identity),player,
                        new EventInstanceId(id(12,n*64+e)),new DefinitionId("test:synthetic_memory_event"),"witness",PerceptionStatus.RECEIVED,
                        outcome.reference(),Optional.empty(),Optional.empty(),Set.of(),Set.of(),new Tick(e))));
                }
                batch.forEach(state->keys.add(state.key()));append(store,batch,List.of(outcome));
            }
            var snapshot=store.metadataSnapshot().orElseThrow();
            var demand=new BoundedMetadataProjection.Demand(players,Set.of(),16,Set.of());
            var selected=await(BoundedMetadataProjection.load(store,snapshot,demand,settings));
            check(selected.states().size()==1625&&selected.memory().significantPlayers().size()==1600,
                "25players x64 significant memories preserved without1024 total truncation");
            int maximum=Math.addExact(512,Math.multiplyExact(settings.count("memory.resident_player_limit"),settings.count("memory.player_recent_event_entries")+1));
            var capture=await(ScopedAggregateCapture.load(store,snapshot,keys,maximum));
            check(capture.states().size()==1625&&capture.sameHeads(store,store.metadataSnapshot().orElseThrow()),
                "paged current-head capture exceeds one StoreQuery while retaining all1625 exact heads");
            check(MemoryProjection.of(selected.scopes(),capture.states().values()).equals(selected.memory()),
                "selected decision memory remains byte-contract equivalent after paged capture");
            var fact=new DurableFact(new DirectorFact(new FactId(id(15,1)),new FactType("test:unrelated_append"),List.of(new ScopeRef(ScopeKind.WORLD,WORLD.value())),
                Optional.empty(),EvidenceStatus.CONFIRMED,Map.of(),true,Optional.empty()));
            check(await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(fact),List.of()))).ackStatus()==AckStatus.DURABLE,
                "unrelated writer really advances J04 cut");
            check(capture.sameHeads(store,store.metadataSnapshot().orElseThrow()),"unchanged current heads usable after unrelated WAL append");
            var player=new PlayerId(id(10,24));
            append(store,List.of(new PlayerOperationalAggregate(new PlayerOperationalMemory(header(player.value(),2),player,new DurationTicks(600),
                Optional.empty(),Optional.empty(),List.of(),List.of(),Set.of(),new UnitValue(.3),Map.of(),Optional.empty(),new Epoch(0)))));
            check(!capture.sameHeads(store,store.metadataSnapshot().orElseThrow()),"any changed selected head invalidates old bodies before current CAS");
        }finally{await(store.closeAsync());}
    }
    private static void unacknowledgedDependencies(){
        var region=new RegionId(id(20,1));var target=new RegionId(id(20,2));
        var player=new ScopeRef(ScopeKind.PLAYER,id(21,1));
        var buffer=new ObservationBuffer(WORLD,200,16);
        buffer.add(player,new ResourceId("proutlost_director:presence"),new Tick(40),"eligible_ticks",40);
        buffer.add(new ScopeRef(ScopeKind.REGION,region.value()),new ResourceId("proutlost_director:activity"),new Tick(40),"visit",1);
        buffer.transition(new RouteKey(region,target),Sector.E,new Tick(40));
        var expected=Set.of(player,new ScopeRef(ScopeKind.REGION,region.value()),new ScopeRef(ScopeKind.REGION,target.value()));
        check(buffer.pendingMemoryScopes().equals(expected),"both route ends and pending coarse scopes retained");
        var snapshot=new SnapshotBatch(new RequestId(id(22,1)),new ScopeSet(WORLD,Set.of(new ScopeRef(ScopeKind.WORLD,WORLD.value()))),new Tick(200),
            Map.of(),new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),Set.of(),Set.of(),HealthMode.NORMAL);
        var batch=buffer.seal(snapshot);
        check(buffer.pendingMemoryScopes().equals(expected),"sealing input does not release metadata dependencies");
        buffer.acknowledge(batch.inputIds());check(buffer.pendingMemoryScopes().isEmpty(),"only matching ACK releases dependencies");
        var anchor=new AnchorRecord(header(id(23,1),1),new AnchorId(id(23,1)),Optional.empty(),Set.of(region),DIM,new BlockPosition(DIM,1,64,1),
            "region.camp_radius_blocks",Set.of(new TagId("proutlost:camp")),AnchorOrigin.CAMP,EvidenceStatus.CONFIRMED,true);
        var sampler=new CampSampler(16,12,200,1200);
        sampler.observe(anchor,region,List.of(new PositionSnapshot(anchor.position(),new Tick(200))),new Tick(200),0,200,32);
        check(sampler.pendingRegions().equals(Set.of(region))&&sampler.pendingAnchors().equals(Set.of(anchor.anchorId())),"unsealed camp dependency remains available");
        var windows=sampler.sealed(new Tick(200));
        check(sampler.pendingRegions().equals(Set.of(region))&&sampler.pendingAnchors().equals(Set.of(anchor.anchorId())),"frozen camp waits for durable ACK");
        sampler.acknowledge(windows.stream().map(w->w.window().id()).toList(),new Tick(200));
        check(sampler.pendingRegions().isEmpty()&&sampler.pendingAnchors().isEmpty(),"camp ACK releases only committed dependencies");
    }
    private static void pendingDiscoveryAnchor(Path root,MemorySettings settings)throws Exception {
        var store=await(JournalStore.open(root,WORLD,OPTIONS));
        try {
            var id=new AnchorId(id(24,1));
            var anchor=new AnchorAggregate(new AnchorRecord(header(id.value(),1),id,Optional.empty(),Set.of(),DIM,new BlockPosition(DIM,1,64,1),
                "region.camp_radius_blocks",Set.of(new TagId("proutlost:camp")),AnchorOrigin.CAMP,EvidenceStatus.POSSIBLE,true));
            append(store,List.of(anchor));
            var demand=new BoundedMetadataProjection.Demand(Set.of(),Set.of(),16,Set.of(),Set.of(id));
            var read=await(BoundedMetadataProjection.load(store,store.metadataSnapshot().orElseThrow(),demand,settings));
            check(read.memory().anchors().containsKey(id),"durable candidate camp anchor can hydrate before region discovery");
            check(read.memory().regionRecords().isEmpty(),"candidate demand never fabricates a physical active region");
        }finally{await(store.closeAsync());}
    }
    private static void disconnectedPlayer(Path root,MemorySettings settings)throws Exception {
        var store=await(JournalStore.open(root,WORLD,OPTIONS));
        var id=new PlayerId(id(8,1));
        try {
            var player=new PlayerOperationalAggregate(new PlayerOperationalMemory(header(id.value(),1),id,new DurationTicks(400),
                Optional.empty(),Optional.empty(),List.of(),List.of(),Set.of(),new UnitValue(.2),Map.of(),Optional.empty(),new Epoch(0)));
            append(store,List.of(player));
            var demand=new BoundedMetadataProjection.Demand(Set.of(),Set.of(),16,Set.of());
            var projected=await(BoundedMetadataProjection.load(store,store.metadataSnapshot().orElseThrow(),demand,settings));
            check(projected.memory().operationalPlayers().equals(List.of(player.value())),
                "disconnect does not erase actual player revision needed by sealed J01 input");
            check(projected.memory().revisions().get(new ScopeRef(ScopeKind.PLAYER,id.value())).equals(new Revision(1)),
                "disconnected player cannot appear as absent revision0");
        }finally{await(store.closeAsync());}
    }
    private static void denseRefusal(Path root,MemorySettings settings)throws Exception {
        var store=await(JournalStore.open(root,WORLD,OPTIONS));
        try {
            for(int from=0;from<129;from+=24){List<AggregateState> batch=new ArrayList<>();
                for(int n=from;n<Math.min(129,from+24);n++)batch.addAll(rows(n,true));append(store,batch);}
            try{await(BoundedMetadataProjection.load(store,store.metadataSnapshot().orElseThrow(),demand(0,Set.of()),settings));
                throw new AssertionError("dense demand silently truncated");}
            catch(ExecutionException expected){check(expected.getCause() instanceof BoundedMetadataProjection.DemandCapacityException,"genuine local capacity overload is explicit and recoverable before partial publication");}
        }finally{await(store.closeAsync());}
    }

    /** Same600-region activity: irrelevant bodies must not dominate exact admin IO.
     * Reverse-only anchor membership is retained; no source/index/filter relaxation. */
    private static void exactRegionReadCost(Path root,MemorySettings settings)throws Exception {
        var store=await(JournalStore.open(root,WORLD,OPTIONS));
        try {
            for(int from=0;from<600;from+=24){List<AggregateState> batch=new ArrayList<>();
                for(int n=from;n<Math.min(600,from+24);n++)batch.addAll(rows(n,false));append(store,batch);}
            var target=new RegionId(id(1,599));var primaryAnchor=new AnchorId(id(3,599));
            var reverse=new AnchorId(id(4,700));var location=new LocationId(id(2,599));
            var reverseBody=new AnchorRecord(header(reverse.value(),1),reverse,Optional.of(location),Set.of(target),DIM,
                new BlockPosition(DIM,599*64+2,64,2),"region.camp_radius_blocks",Set.of(new TagId("proutlost:reverse_qa")),
                AnchorOrigin.CAMP,EvidenceStatus.CONFIRMED,true);
            append(store,List.of(new AnchorAggregate(reverseBody)));
            var demand=new BoundedMetadataProjection.Demand(Set.of(),Set.of(),16,
                Set.of(new ScopeRef(ScopeKind.REGION,target.value())),Set.of(primaryAnchor));
            var pinned=store.metadataSnapshot().orElseThrow();
            var view=await(BoundedMetadataProjection.loadExact(store,pinned,demand,settings));
            System.out.println("P008_EXACT_SCOPE_COST600 examined="+view.examinedRows()+" selected="+view.states().size()+" peak="+view.peakPageRows());
            check(view.memory().regionRecords().keySet().equals(Set.of(target)),"exact selected region unchanged in600 corpus");
            check(view.memory().anchors().keySet().equals(Set.of(primaryAnchor,reverse)),"reverse-only anchor must not be lost");
            check(view.memory().anchors().get(reverse).equals(reverseBody),"actual reverse anchor full body retained");
            check(view.memory().locations().keySet().equals(Set.of(location)),"exact associated location retained");
            check(view.states().size()==5&&view.peakPageRows()<=96,"exact working set and page bounds unchanged");
            check(view.currentHeads(store,pinned),"exact fresh head proof remains usable");
            check(view.examinedRows()<=605,"exact admin scope hydrated unrelated REGION, LOCATION or REGION_MEMORY bodies: examined="+view.examinedRows());
            var moved=new AnchorRecord(header(reverse.value(),2),reverse,Optional.of(location),Set.of(new RegionId(id(1,598))),DIM,
                reverseBody.position(),reverseBody.radiusProfile(),reverseBody.tags(),reverseBody.origin(),reverseBody.evidence(),true);
            append(store,List.of(new AnchorAggregate(moved)));
            var now=store.metadataSnapshot().orElseThrow();
            check(!view.currentHeads(store,now),"changed reverse membership must invalidate old exact data");
            var fresh=await(BoundedMetadataProjection.loadExact(store,now,demand,settings));
            check(fresh.memory().anchors().keySet().equals(Set.of(primaryAnchor)),"fresh scope excludes moved reverse member");
            var old=await(BoundedMetadataProjection.loadExact(store,pinned,demand,settings));
            check(old.memory().anchors().get(reverse).equals(reverseBody),"pinned old cut must never mix newer reverse membership");
            var absent=new BoundedMetadataProjection.Demand(Set.of(),Set.of(),16,
                Set.of(new ScopeRef(ScopeKind.REGION,id(1,999))),Set.of());
            var missing=await(BoundedMetadataProjection.loadExact(store,now,absent,settings));
            check(missing.states().isEmpty(),"absent exact scope must not fabricate a body");
            check(!missing.scopes().scopes().contains(new ScopeRef(ScopeKind.WORLD,WORLD.value())),"exact absence never claims complete world");
        }finally{await(store.closeAsync());}
    }
}
