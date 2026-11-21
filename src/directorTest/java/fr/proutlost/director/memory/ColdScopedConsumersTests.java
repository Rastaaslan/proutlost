package fr.proutlost.director.memory;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.admin.server.*;
import fr.proutlost.director.adapter.neoforge.server.observation.ObservationBuffer;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Actual J04 + pure native-consumer components; declared synthetic QA, no Minecraft claim. */
public final class ColdScopedConsumersTests {
    private static final WorldId WORLD=new WorldId(new UUID(0x50303038434f4c44L,1));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final StoreOptions OPTIONS=new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128);
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static <T>T await(CompletionStage<T> value)throws Exception{return value.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static UUID id(int domain,int n){return new UUID(domain,n);}
    private static AggregateHeader header(UUID id,long revision){return new AggregateHeader(1,id,new Revision(revision),new Sequence(0),new Tick(0));}
    private static MemorySettings configuration(Path file)throws Exception{
        var raw=((JsonValue.ObjectValue)((JsonValue.ObjectValue)CanonicalJsonV1.decode(Files.readAllBytes(file))).values().get("values")).values();
        Map<String,JsonValue.Number> values=new HashMap<>();raw.forEach((k,v)->values.put(k,(JsonValue.Number)v));
        return new MemorySettings(new ConfigView(new Revision(1),BlobContent.hash(Files.readAllBytes(file)),Calibration.DEFAULT_FOR_TESTING,values),false);
    }
    private static DurableBatch batch(JournalStore store,TransactionId id,List<AggregateState> values,Map<ScopeRef,Revision> cas){
        return ResultTransaction.batch(id,store.session().storeGeneration(),cas,values.stream().map(s->(DurableRecord)new DurableAggregate(s)).toList(),List.of());
    }
    private static void append(JournalStore store,List<AggregateState> values)throws Exception{
        check(await(store.append(batch(store,new TransactionId(UUID.randomUUID()),values,Map.of()))).ackStatus()==AckStatus.DURABLE,"fixture actual J04 append");
    }
    private static List<AggregateState> region(int n){
        int x=n*64;var region=new RegionId(id(1,n));var anchor=new AnchorId(id(2,n));var location=new LocationId(id(3,n));
        var bounds=new Aabb(DIM,x,0,0,x+16,256,16);
        return List.of(new RegionAggregate(new RegionRecord(header(region.value(),1),region,DIM,RegionStatus.ACTIVE,new SupportShape(List.of(bounds),List.of(),List.of()),
                Set.of(anchor),new UnitValue(0),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of(new MemoryId(region.value())))),
            new RegionMemoryAggregate(new RegionMemory(header(region.value(),1),region,Map.of(),new Tick(0),new Tick(0),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of()))),
            new AnchorAggregate(new AnchorRecord(header(anchor.value(),1),anchor,Optional.of(location),Set.of(region),DIM,new BlockPosition(DIM,x+1,64,1),"region.camp_radius_blocks",
                Set.of(),AnchorOrigin.IMPORTED,EvidenceStatus.CONFIRMED,true)),
            new LocationAggregate(new LocationRecord(header(location.value(),1),location,new ResourceId("test:synthetic_cold_scope"),"qa_"+n,DIM,bounds,Set.of(),Set.of(anchor),new Revision(1),true)));
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("profile and NEW QA directory");
        Path root=Path.of(args[1]);if(Files.exists(root))throw new IllegalArgumentException("fresh QA directory required");Files.createDirectories(root);
        var settings=configuration(Path.of(args[0]));admin(root.resolve("admin"),settings);rejection(root.resolve("rebase"),settings);
        System.out.println("PASS ColdScopedConsumersTests assertions="+checks+" scope=actual_J04_synthetic_no_Minecraft_no_Contra_no_RUN7");
    }
    private static AdminQuery query(SessionId session,AdminFilter filter){return new AdminQuery(session,new RequestId(UUID.randomUUID()),AdminPanel.MEMORY,filter,Optional.empty(),Optional.empty(),10);}
    private static void admin(Path root,MemorySettings settings)throws Exception{
        var store=await(JournalStore.open(root,WORLD,OPTIONS));
        try{
            append(store,region(1));append(store,region(2));var snapshot=store.metadataSnapshot().orElseThrow();
            var demand=new BoundedMetadataProjection.Demand(Set.of(),Set.of(BoundedMetadataProjection.Demand.cell(new BlockPosition(DIM,65,64,1),16)),16,Set.of());
            var selected=await(BoundedMetadataProjection.load(store,snapshot,demand,settings));var memory=selected.memory();
            var hot=new RegionId(id(1,1));var cold=new RegionId(id(1,2));
            check(store.metadataRevision(snapshot,new AggregateKey(AggregateKind.REGION,cold.value())).value()==1&&!memory.regionRecords().containsKey(cold),"cold target really exists in J04 outside selected cache");
            var r=memory.regionRecords().get(hot);var m=memory.regions().getFirst();
            var row=new AdminRow(new ScopeRef(ScopeKind.REGION,hot.value()),r.header().revision(),r.header().updatedTick(),new RegionAdminData(r,m,Set.of(),List.copyOf(memory.anchors().values()),List.copyOf(memory.locations().values())));
            var session=new SessionId(id(5,1));var actor=new ActorIdentity(session,ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true);
            var legacy=new AdminProjectionPages(WORLD,50,10,22000);legacy.publish(WORLD,new Revision(1),new Tick(1),snapshot.cut(),List.of(row),Set.of());
            var coldQuery=query(session,new RegionFilter(Optional.of(cold),Set.of()));
            var falseEmpty=legacy.query(coldQuery,actor,store.session().bootEpoch());
            check(falseEmpty.status()==AdminStatus.SUCCEEDED&&falseEmpty.data().isEmpty(),"counterexample: unqualified row-only publication falsely reports cold target absent");
            var pages=new AdminProjectionPages(WORLD,50,10,22000);pages.publish(WORLD,new Revision(1),new Tick(1),snapshot.cut(),List.of(row),Set.of(),memory);
            var rejected=pages.query(coldQuery,actor,store.session().bootEpoch());
            check(rejected.status()==AdminStatus.DEFERRED&&rejected.snapshot().isEmpty(),"scoped publication defers cold target without an AVAILABLE empty stamp");
            check(pages.query(query(session,new RegionFilter(Optional.empty(),Set.of())),actor,store.session().bootEpoch()).status()==AdminStatus.DEFERRED,"scoped list never claims complete world coverage");
            check(pages.query(query(session,new RegionFilter(Optional.of(hot),Set.of())),actor,store.session().bootEpoch()).data().equals(List.of(row)),"complete requested resident scope remains inspectable");
            var incomplete=new MemoryView(memory.scopes(),memory.revisions(),memory.regions(),memory.operationalPlayers(),memory.significantPlayers(),memory.regionRecords(),Map.of(),memory.locations());
            check(!AdminMemoryCoverage.available(new RegionFilter(Optional.of(hot),Set.of()),incomplete),"missing required anchor remains incomplete");
            var port=new DurableMemoryPort(store,settings);port.replaceProjection(selected.states().values());var queries=new AdminMemoryQueries(port,WORLD);
            try{queries.region(cold);throw new AssertionError("cold exact region treated as absent");}catch(IllegalStateException expected){checks++;}
            check(queries.region(hot).isPresent(),"exact resident memory query retains real result");
            var global=port.view(new ScopeSet(WORLD,Set.of(new ScopeRef(ScopeKind.WORLD,WORLD.value()))));
            check(!global.scopes().scopes().contains(new ScopeRef(ScopeKind.WORLD,WORLD.value()))&&global.regionRecords().size()==1,
                "generic WORLD request cannot relabel a scoped cache as complete world memory");
            check(port.view(new ScopeSet(WORLD,Set.of(new ScopeRef(ScopeKind.REGION,cold.value())))).scopes().scopes().isEmpty(),
                "cold exact request preserves unknown coverage for generic memory consumers");
        }finally{await(store.closeAsync());}
    }
    private static PlayerOperationalAggregate player(PlayerId id,long revision,long eligible){return new PlayerOperationalAggregate(new PlayerOperationalMemory(header(id.value(),revision),id,new DurationTicks(eligible),
        Optional.empty(),Optional.empty(),List.of(),List.of(),Set.of(),new UnitValue(0),Map.of(),Optional.empty(),new Epoch(0)));}
    private static SnapshotBatch snapshot(MemoryView memory){return new SnapshotBatch(new RequestId(id(8,1)),memory.scopes(),new Tick(200),memory.revisions(),
        new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),Set.of(),Set.of(),HealthMode.NORMAL);}
    private static DurableBatch reduced(JournalStore store,TransactionId tx,Reduction reduction){
        var cas=new HashMap<ScopeRef,Revision>();var states=new ArrayList<AggregateState>();
        for(var change:reduction.aggregateChanges()){states.add(change.next());if(change.next() instanceof PlayerOperationalAggregate p)
            cas.put(new ScopeRef(ScopeKind.PLAYER,p.value().playerId().value()),change.expectedRevision().orElse(new Revision(0)));}
        return batch(store,tx,states,cas);
    }
    private static void rejection(Path root,MemorySettings settings)throws Exception{
        var store=await(JournalStore.open(root,WORLD,OPTIONS));
        try{
            var session=store.session();var id=new PlayerId(id(7,1));var scopes=new ScopeSet(WORLD,Set.of(new ScopeRef(ScopeKind.PLAYER,id.value())));
            var first=player(id,1,100);append(store,List.of(first));var before=MemoryProjection.of(scopes,List.of(first));
            var buffer=new ObservationBuffer(WORLD,200,16,session.bootEpoch());buffer.add(new ScopeRef(ScopeKind.PLAYER,id.value()),new ResourceId("proutlost_director:presence"),new Tick(40),"eligible_ticks",40);
            var frozen=buffer.seal(snapshot(before),before.revisions());var reducer=new MemoryReducer(settings);
            var tx=new TransactionId(UUID.nameUUIDFromBytes((WORLD.value()+"/synthetic-j01/"+frozen.inputIds()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var failed=reduced(store,tx,reducer.reduce(before,frozen,new DirectorClock(new Tick(200),0,Instant.EPOCH)));
            var concurrent=player(id,2,200);append(store,List.of(concurrent));
            var rejection=await(store.append(failed));check(rejection.ackStatus()==AckStatus.REJECTED&&rejection.durableSequence().value()==0,"real concurrent J04 head causes definitive pre-IO CAS rejection");
            check(store.transactionReadback(tx).orElseThrow().receipt().isEmpty(),"rejected transaction is provably absent from non-evicting durable receipt index");
            check(ObservationAppendRecovery.verify(store,session,failed,rejection)==ObservationAppendRecovery.Status.REBASE_ALLOWED,"only proven uncommitted stale batch may rebase");
            check(buffer.seal(snapshot(before))==frozen&&!buffer.pendingMemoryScopes().isEmpty(),"rejection consumes no captured observation");
            var unavailable=new CommitAck(tx,session.storeGeneration(),new Sequence(0),failed.transactionHash(),AckStatus.UNAVAILABLE,Optional.empty(),Set.of());
            check(ObservationAppendRecovery.verify(store,session,failed,unavailable)==ObservationAppendRecovery.Status.UNSAFE,"unavailable/uncertain acknowledgement never authorizes retry");
            var current=MemoryProjection.of(scopes,List.of(concurrent));var rebased=buffer.rebaseAfterRejection(rejection,snapshot(current),current.revisions());
            check(rebased.inputIds().equals(frozen.inputIds())&&rebased.observations().equals(frozen.observations()),"rebase preserves exact original IDs and captured statistics");
            var corrected=reduced(store,tx,reducer.reduce(current,rebased,new DirectorClock(new Tick(200),0,Instant.EPOCH)));
            check(corrected.transactionId().equals(failed.transactionId())&&!corrected.transactionHash().equals(failed.transactionHash()),"definitively uncommitted same transaction identity receives recomputed CAS/body");
            var ack=await(store.append(corrected));check(ack.ackStatus()==AckStatus.DURABLE,"fresh-head recomputation commits to actual J04");
            check(ObservationAppendRecovery.verify(store,session,failed,rejection)==ObservationAppendRecovery.Status.UNSAFE,"existing durable same-ID other-hash receipt forbids rebasing old refusal");
            buffer.acknowledge(rebased.inputIds());check(buffer.pendingMemoryScopes().isEmpty(),"only exact durable ACK consumes the frozen input");
            var absent=new PlayerId(id(7,99));var coverage=new ScopeSet(WORLD,Set.of(new ScopeRef(ScopeKind.PLAYER,absent.value())));
            var empty=new ScopedAggregateCapture.View(store.metadataSnapshot().orElseThrow(),Map.of());
            check(empty.sameMemoryHeads(store,store.metadataSnapshot().orElseThrow(),coverage,Set.of()),"actual absent scope is revision0 at captured current head index");
            append(store,List.of(player(absent,1,0)));
            check(!empty.sameMemoryHeads(store,store.metadataSnapshot().orElseThrow(),coverage,Set.of()),"a newly created previously absent scope invalidates selected memory before write");
        }finally{await(store.closeAsync());}
    }
}
