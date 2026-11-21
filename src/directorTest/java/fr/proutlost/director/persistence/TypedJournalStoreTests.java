package fr.proutlost.director.persistence;

import fr.proutlost.director.api.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real disposable filesystem tests for the experimental typed J04 projection and WAL. */
public final class TypedJournalStoreTests {
    static final WorldId WORLD = new WorldId(new UUID(0, 420));
    static final StoreOptions OPTIONS = new StoreOptions(
        1_048_620, 2_097_152, 4_194_304, 16_777_216, 32, 0, 128);
    static int assertions;
    static void check(boolean value, String message) {
        assertions++;
        if(!value) throw new AssertionError(message);
    }
    static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(15, TimeUnit.SECONDS);
    }
    static DirectorFact fact(int i) {
        return new DirectorFact(new FactId(new UUID(1,i)),new FactType("fixture:fact"),
            List.of(new ScopeRef(ScopeKind.WORLD,WORLD.value())),Optional.empty(),
            EvidenceStatus.CONFIRMED,Map.of("n",new JsonValue.Text(Integer.toString(i))),true,Optional.empty());
    }
    static DurableBatch batch(JournalStore store,int transaction,int... facts) {
        List<DurableRecord> records=new ArrayList<>();
        for(int id:facts)records.add(new DurableFact(fact(id)));
        return new DurableBatch(new TransactionId(new UUID(2,transaction)),
            store.session().storeGeneration(),Map.of(),records,List.of(),Optional.empty(),
            BlobContent.hash(new byte[]{(byte)transaction}));
    }
    static StoreQuery query(int limit,Optional<String> cursor) {
        return new StoreQuery(WORLD,Set.of(),new Sequence(0),cursor,limit);
    }
    static DirectorEnvelope envelope(int i) {
        return new DirectorEnvelope(1,new MessageId(new UUID(9,i)),WORLD,
            new ResourceId("fixture:producer"),"message-"+i,
            new ScopeRef(ScopeKind.WORLD,WORLD.value()),new CorrelationId(new UUID(10,i)),
            Optional.empty(),new Sequence(i),Optional.empty(),new Tick(10),
            PayloadType.DirectorFact,1,new FactPayload(fact(i)));
    }
    static void subscriptionRoundTrip(JournalStore store) throws Exception {
        int value=17;
        var batch=new DurableBatch(new TransactionId(new UUID(15,value)),
            store.session().storeGeneration(),Map.of(),
            List.of(new DurableOutbox(envelope(value))),List.of(),Optional.empty(),
            BlobContent.hash(new byte[]{(byte)value}));
        check(await(store.append(batch)).ackStatus()==AckStatus.DURABLE,
            "outbox write durable");
        var consumerId=new ConsumerId(new UUID(0,900));
        var spec=new ConsumerSpec(consumerId,Set.of(),new Sequence(0),4);
        var page=store.subscribe(spec);
        check(page.status()==ReadStatus.AVAILABLE,"typed subscription available");
        ReadContracts.validate(page,spec,store.session());
        var subscription=page.page().orElseThrow();
        check(subscription.batch().size()==1,"one durable message");
        check(subscription.nextSequence().equals(new Sequence(2)),"message cursor advanced only in page");
        var async=await(store.readSubscription(spec));
        check(async.status()==ReadStatus.AVAILABLE,"async subscription available");
        check(async.page().orElseThrow().batch().size()==1,"async delivery same event");
        var ack=await(store.acknowledge(new ConsumerAck(consumerId,
            Set.of(subscription.batch().getFirst().messageId()),new Sequence(1),new Sequence(0))));
        check(ack.status()==AckStatus.DURABLE,"explicit ack forced");
        var nextSpec=new ConsumerSpec(consumerId,Set.of(),new Sequence(2),4);
        var next=store.subscribe(nextSpec);
        check(next.status()==ReadStatus.AVAILABLE,"ack state valid");
        check(next.page().orElseThrow().batch().isEmpty(),"durable ack prevents redelivery");
    }
    static void scenario(Path base) throws Exception {
        var store=await(JournalStore.open(base, WORLD, OPTIONS));
        try {
            var empty=store.view(query(1,Optional.empty()));
            check(empty.status()==ReadStatus.AVAILABLE, "empty resident page available");
            check(empty.page().orElseThrow().records().isEmpty(),"empty resident verified");
            ReadContracts.validate(empty,query(1,Optional.empty()),store.session());
            check(await(store.append(batch(store,1,1,2))).ackStatus()==AckStatus.DURABLE,
                "two durable records committed");
            check(store.retainedFact(fact(1).factId()).orElseThrow().equals(fact(1)),"canonical private-collection dedup uses actual durable fact index");
            check(store.retainedFact(new FactId(new UUID(90,90))).isEmpty(),"verified running fact index can attest an absent identity");
            var first=store.view(query(1,Optional.empty()));
            check(first.status()==ReadStatus.AVAILABLE,"first typed page");
            check(first.page().orElseThrow().records().size()==1,"bounded page size");
            check(first.page().orElseThrow().hasMore(),"continuation required");
            String cursor=first.page().orElseThrow().nextCursor().orElseThrow();
            var secondQuery=query(1,Optional.of(cursor));
            var second=await(store.read(secondQuery));
            check(second.status()==ReadStatus.AVAILABLE,"async second page");
            check(second.page().orElseThrow().records().size()==1,"second record");
            check(!second.page().orElseThrow().hasMore(),"end of two records");
            check(store.view(query(1,Optional.of("forged"))).status()==ReadStatus.STALE,
                "forged cursor stale");
            check(await(store.append(batch(store,2,3))).ackStatus()==AckStatus.DURABLE,
                "next durable transaction");
            check(store.view(secondQuery).status()==ReadStatus.STALE,
                "old snapshot cursor invalid after new commit");
            var after=store.view(query(8,Optional.empty()));
            check(after.page().orElseThrow().records().size()==3,"all durable records visible");
        } finally {
            await(store.closeAsync());
        }
        store=await(JournalStore.open(base, WORLD, OPTIONS));
        try {
            var again=await(store.read(query(8,Optional.empty())));
            check(again.status()==ReadStatus.AVAILABLE,"reopen typed read");
            check(again.page().orElseThrow().records().size()==3,"WAL replay retains contents");
            check(!store.readOnlySafe(),"healthy reopen remains writable");
            check(store.retainedFact(fact(1).factId()).orElseThrow().equals(fact(1)),"durable collected identity survives real WAL reopen");
            subscriptionRoundTrip(store);
        } finally {
            await(store.closeAsync());
        }
    }
    static void corruptionScenario(Path folder) throws Exception {
        var store=await(JournalStore.open(folder,WORLD,OPTIONS));
        try {
            check(await(store.append(batch(store,71,71))).ackStatus()==AckStatus.DURABLE,
                "corruption fixture committed");
        } finally { await(store.closeAsync()); }
        Path wal;
        try(var entries=Files.list(folder.resolve("wal"))) {
            wal=entries.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        var bytes=Files.readAllBytes(wal);
        check(bytes.length>24,"WAL fixture contains framed transaction");
        bytes[bytes.length/2]^=1;
        Files.write(wal,bytes);
        store=await(JournalStore.open(folder,WORLD,OPTIONS));
        try {
            check(store.readOnlySafe(),"WAL corruption triggers safe read-only state");
            boolean unavailable=false;
            try{store.retainedFact(fact(71).factId());}catch(IllegalArgumentException expected){unavailable=true;}
            check(unavailable,"unavailable fact index never turns writer fault into absence");
            check(await(store.readRetainedFacts(new FactType("fixture:fact"),new ScopeRef(ScopeKind.WORLD,WORLD.value()))).isEmpty(),"corrupt store cannot publish retained personal/index pages");
            check(store.view(query(3,Optional.empty())).status()==ReadStatus.UNAVAILABLE,
                "corrupt WAL cannot report false empty resident view");
            check(await(store.read(query(3,Optional.empty()))).status()==ReadStatus.UNAVAILABLE,
                "corrupt WAL async read unavailable");
            var consumer=new ConsumerSpec(new ConsumerId(new UUID(0,505)),Set.of(),new Sequence(0),3);
            check(store.subscribe(consumer).status()==ReadStatus.UNAVAILABLE,
                "corrupt WAL subscription unavailable");
        } finally { await(store.closeAsync()); }
    }
    static EventInstance indexedEvent(UUID id,long revision,EventState state,BlobRef context) {
        return new EventInstance(new AggregateHeader(1,id,new Revision(revision),new Sequence(0),new Tick(revision)),
            new EventInstanceId(id),new DefinitionRef(new DefinitionId("fixture:index"),new Revision(1),context.hash()),
            new Epoch(1),state,"INDEX_FIXTURE",new RoleAssignments(Map.of()),Set.of(),context,Map.of(),Set.of(),Set.of(),Set.of(),
            Optional.empty(),Set.of(),Optional.of(new Tick(1)),new DurationTicks(0),new DurationTicks(100),RecoveryMode.RECOVER,
            state==EventState.SUCCEEDED?Optional.of(Outcome.SUCCEEDED):Optional.empty());
    }
    static void filteredDiscoveryScenario(Path folder) throws Exception {
        var dimension=new DimensionId("minecraft:overworld");var locationId=new LocationId(new UUID(50,1));
        var location=new LocationRecord(new AggregateHeader(1,locationId.value(),new Revision(1),new Sequence(0),new Tick(1)),
            locationId,new ResourceId("fixture:source"),"one",dimension,new Aabb(dimension,0,0,0,1,1,1),Set.of(),Set.of(),new Revision(1),true);
        var anchorId=new AnchorId(new UUID(50,2));
        var anchor=new AnchorRecord(new AggregateHeader(1,anchorId.value(),new Revision(1),new Sequence(0),new Tick(1)),anchorId,
            Optional.of(locationId),Set.of(),dimension,new BlockPosition(dimension,0,0,0),"fixture",Set.of(),AnchorOrigin.IMPORTED,EvidenceStatus.CONFIRMED,true);
        var resource=new ResourceKey(OwnershipDomain.BLOCK,dimension,"fixture:one");var ownerId=new UUID(50,3);
        var owner=new OwnershipRecord(new AggregateHeader(1,ownerId,new Revision(1),new Sequence(0),new Tick(1)),resource,
            Set.of(new OwnerToken(new UUID(50,4))),new Epoch(1),OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(new UUID(50,5)),
            BlobContent.hash(new byte[]{1}),new Epoch(0),Optional.empty());
        var context=BlobContent.capture(new ResourceId("fixture:index_context"),1,new byte[]{1,2,3});
        var firstId=new UUID(50,6);var secondId=new UUID(50,7);
        var active=indexedEvent(firstId,1,EventState.RUNNING,context.reference());
        var ended=indexedEvent(secondId,1,EventState.SUCCEEDED,context.reference());
        var store=await(JournalStore.open(folder,WORLD,OPTIONS));
        try {
            var records=List.<DurableRecord>of(new DurableAggregate(new LocationAggregate(location)),new DurableAggregate(new AnchorAggregate(anchor)),
                new DurableOwnership(owner),new DurableAggregate(new EventAggregate(active)),new DurableAggregate(new EventAggregate(ended)));
            var batch=fr.proutlost.director.runtime.ResultTransaction.batch(new TransactionId(new UUID(51,1)),store.session().storeGeneration(),Map.of(),records,List.of(context));
            check(await(store.append(batch)).ackStatus()==AckStatus.DURABLE,"discovery fixture is genuine committed state");
            var locations=store.residentAggregateKeys(Set.of(AggregateKind.LOCATION),1).orElseThrow();
            check(locations.keys().equals(Set.of(new AggregateKey(AggregateKind.LOCATION,locationId.value()))),"unrelated aggregate history cannot exhaust a filtered bound");
            check(store.residentAggregateKeys(Set.of(AggregateKind.EVENT),1).isEmpty(),"requested-kind overflow is unknown, never truncated");
            var live=store.residentPlanningAggregateKeys(Set.of(AggregateKind.EVENT),1).orElseThrow();
            check(live.keys().equals(Set.of(new AggregateKey(AggregateKind.EVENT,firstId))),"planning excludes terminal event only");
            check(live.session().equals(store.session())&&live.cut().equals(locations.cut()),"discovery retains exact session and published cut");
            check(store.residentOwnershipKeys(Set.of(resource),1).orElseThrow().keys().equals(Set.of(new AggregateKey(AggregateKind.OWNERSHIP,ownerId))),"resource lookup independent of unrelated head count");
            check(store.residentBudgetAggregateKeys(1).orElseThrow().keys().equals(Set.of(new AggregateKey(AggregateKind.OWNERSHIP,ownerId))),"budget discovery includes only actually charged state");
            var released=new OwnershipRecord(new AggregateHeader(1,ownerId,new Revision(2),new Sequence(0),new Tick(2)),resource,
                owner.ownerTokens(),owner.epoch(),OwnershipDisposition.RELEASED,owner.creationMutationId(),owner.expectedPostHash(),owner.playerOverrideEpoch(),Optional.empty());
            var finish=fr.proutlost.director.runtime.ResultTransaction.batch(new TransactionId(new UUID(51,2)),store.session().storeGeneration(),Map.of(),
                List.of(new DurableAggregate(new EventAggregate(indexedEvent(firstId,2,EventState.SUCCEEDED,context.reference()))),new DurableOwnership(released)),List.of());
            check(await(store.append(finish)).ackStatus()==AckStatus.DURABLE,"terminal revision committed");
            check(store.residentPlanningAggregateKeys(Set.of(AggregateKind.EVENT),1).orElseThrow().keys().isEmpty(),"latest terminal head retires from planning");
            check(live.keys().size()==1,"previous returned cut remains immutable");
            check(store.residentBudgetAggregateKeys(1).orElseThrow().keys().isEmpty(),"released owner no longer charged despite retained history");
            check(store.residentOwnershipKeys(Set.of(resource),1).orElseThrow().keys().size()==1,"released owner remains available for exact epoch/override checks");
            check(await(store.checkpoint())>0,"discovery checkpoint durable");
        } finally {await(store.closeAsync());}
        store=await(JournalStore.open(folder,WORLD,OPTIONS));
        try {
            check(store.residentPlanningAggregateKeys(Set.of(AggregateKind.EVENT),1).orElseThrow().keys().isEmpty(),"planning predicate survives real restart");
            check(store.residentAggregateKeys(Set.of(AggregateKind.EVENT),2).orElseThrow().keys().size()==2,"terminal history is retained after checkpoint/reopen");
            check(store.residentOwnershipKeys(Set.of(resource),1).orElseThrow().keys().size()==1,"ownership index survives real restart");
            check(store.residentBudgetAggregateKeys(1).orElseThrow().keys().isEmpty(),"active budget filtering survives checkpoint/reopen");
        } finally {await(store.closeAsync());}
    }
    static DirectorFact personalFact(int n,UUID player,boolean mixed){
        var refs=mixed?List.of(new ScopeRef(ScopeKind.PLAYER,player),new ScopeRef(ScopeKind.WORLD,WORLD.value()))
            :List.of(new ScopeRef(ScopeKind.PLAYER,player));
        return new DirectorFact(new FactId(new UUID(700,n)),new FactType("fixture:personal"),refs,Optional.empty(),
            EvidenceStatus.CONFIRMED,Map.of("n",new JsonValue.Text(Integer.toString(n))),true,Optional.empty());
    }
    static List<DirectorFact> retainedSelection(JournalStore store,JournalStore.RetainedFactPage first) throws Exception {
        List<DirectorFact> result=new ArrayList<>();var page=first;int pages=0;
        while(true){
            check(++pages<100,"retained fact continuation progresses through sparse index");
            check(page.snapshot()==first.snapshot()&&page.snapshot().cut().equals(first.snapshot().cut()),"personal pages retain exact captured cut");
            check(page.facts().size()<=16,"personal page stays bounded");result.addAll(page.facts());
            if(page.next().isEmpty())return result;
            var after=page.next().orElseThrow();
            var next=await(store.readRetainedFacts(first.snapshot(),after)).orElseThrow();
            var replay=await(store.readRetainedFacts(first.snapshot(),after)).orElseThrow();
            check(next.equals(replay),"same retained cursor returns identical immutable page");page=next;
        }
    }
    static void retainedPersonalFactsScenario(Path folder) throws Exception {
        var type=new FactType("fixture:personal");UUID a=new UUID(800,1),b=new UUID(800,2);
        var ownerA=new ScopeRef(ScopeKind.PLAYER,a);var ownerB=new ScopeRef(ScopeKind.PLAYER,b);
        JournalStore.RetainedFactSnapshot priorSnapshot;FactId priorAfter;
        var store=await(JournalStore.open(folder,WORLD,OPTIONS));
        try{
            List<DurableRecord> records=new ArrayList<>();
            for(int i=1000;i<1600;i++)records.add(new DurableFact(fact(i)));
            Set<FactId> expected=new HashSet<>();
            for(int i=1;i<=33;i++){var value=personalFact(i,a,false);records.add(new DurableFact(value));expected.add(value.factId());}
            records.add(new DurableFact(personalFact(101,b,false)));records.add(new DurableFact(personalFact(102,b,false)));
            records.add(new DurableFact(personalFact(103,a,true)));
            var initial=new DurableBatch(new TransactionId(new UUID(900,1)),store.session().storeGeneration(),Map.of(),records,List.of(),Optional.empty(),BlobContent.hash(new byte[]{1}));
            check(await(store.append(initial)).ackStatus()==AckStatus.DURABLE,"personal facts and raw WAL noise committed");
            var captured=await(store.readRetainedFacts(type,ownerA)).orElseThrow();
            priorSnapshot=captured.snapshot();priorAfter=captured.next().orElseThrow();
            var later=personalFact(34,a,false);
            var append=new DurableBatch(new TransactionId(new UUID(900,2)),store.session().storeGeneration(),Map.of(),List.of(new DurableFact(later)),List.of(),Optional.empty(),BlobContent.hash(new byte[]{2}));
            check(await(store.append(append)).ackStatus()==AckStatus.DURABLE,"background append after personal first page");
            var selected=retainedSelection(store,captured);
            check(selected.size()==33&&selected.stream().map(DirectorFact::factId).collect(java.util.stream.Collectors.toSet()).equals(expected),"stable cut yields every own record exactly once despite later append, never raw WAL/world/other-player facts");
            var fresh=retainedSelection(store,await(store.readRetainedFacts(type,ownerA)).orElseThrow());
            check(fresh.size()==34&&fresh.contains(later),"fresh personal journal captures later committed record");
            var other=retainedSelection(store,await(store.readRetainedFacts(type,ownerB)).orElseThrow());
            check(other.size()==2&&other.stream().allMatch(f->f.subjectRefs().equals(List.of(ownerB))),"B selection cannot expose A or mixed-scope records");
            var sparse=await(store.readRetainedFacts(new FactType("fixture:absent"),ownerA)).orElseThrow();
            check(sparse.facts().isEmpty()&&sparse.next().isPresent(),"bounded sparse scan returns continuation rather than false journal absence");
            check(retainedSelection(store,sparse).isEmpty(),"sparse continuation reaches verified final index cut");
        }finally{await(store.closeAsync());}
        check(await(store.readRetainedFacts(type,ownerA)).isEmpty(),"closed store cannot publish a retained journal snapshot");
        store=await(JournalStore.open(folder,WORLD,OPTIONS));
        try{
            check(await(store.readRetainedFacts(priorSnapshot,priorAfter)).isEmpty(),"previous StoreSession snapshot is refused after real WAL reopen");
            var restored=retainedSelection(store,await(store.readRetainedFacts(type,ownerA)).orElseThrow());
            check(restored.size()==34,"personal index pages survive actual WAL reopen");
        }finally{await(store.closeAsync());}
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("one disposable base path required");
        Path folder=Path.of(args[0]).toAbsolutePath().normalize();
        check(!Files.exists(folder),"fresh disposable store only");
        scenario(folder);
        corruptionScenario(Path.of(folder.toString()+"-corrupt"));
        retainedPersonalFactsScenario(Path.of(folder.toString()+"-personal"));
        filteredDiscoveryScenario(Path.of(folder.toString()+"-filtered-discovery"));
        System.out.println("PASS TypedJournalStoreTests assertions="+assertions+
            "; real WAL/restart, no cold archive eviction qualification");
    }
}
