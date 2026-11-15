package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.memory.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;

/**
 * Actual J04 WAL -> durable J01 sparse region index; no invented membership.
 * Synthetic region GEOMETRY is a test fixture persisted in an actual WAL.
 * Native observation on the running server consumes only the J04 projection.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeDurableRegionIndexGameTests {
    private DirectorNativeDurableRegionIndexGameTests(){}
    private static final StoreOptions OPTIONS=new StoreOptions(
        1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);

    private static final class Probe {
        final MinecraftServer server;
        final WorldId world;
        final RegionId regionId;
        final RegionRecord region;
        final RegionMemory memory;
        final EventInstance eventFixture;
        final ChainInstance chainFixture;
        final BlobContent eventContext;
        final BlockPosition inside;
        final BlockPosition outside;
        final java.nio.file.Path directory;
        final CompletableFuture<JournalStore> opening;
        JournalStore store;
        CompletableFuture<CommitAck> committed;
        CompletableFuture<Void> closed;
        CompletableFuture<JournalStore> reopened;
        int phase;
        Probe(GameTestHelper helper){
            server=Objects.requireNonNull(helper.getLevel().getServer());
            actor();
            world=server.overworld().getDataStorage().computeIfAbsent(
                DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY).worldId();
            var pos=helper.absolutePos(new BlockPos(1,2,1));
            var dimension=new DimensionId(helper.getLevel().dimension().location().toString());
            inside=new BlockPosition(dimension,pos.getX(),pos.getY(),pos.getZ());
            outside=new BlockPosition(dimension,pos.getX()+128,pos.getY(),pos.getZ());
            regionId=new RegionId(UUID.randomUUID());
            var hdr=new AggregateHeader(1,regionId.value(),new Revision(1),
                new Sequence(0),new Tick(10));
            region=new RegionRecord(hdr,regionId,dimension,RegionStatus.ACTIVE,
                new SupportShape(List.of(),
                    List.of(new SupportDisc(inside,6.0)),List.of()),
                Set.of(),new UnitValue(.5),Set.of(),Set.of(),
                new Revision(1),new RegionPersonality(Map.of()),Set.of());
            memory=new RegionMemory(hdr,regionId,Map.of(),new Tick(10),
                new Tick(0),Set.of(),Set.of(),
                new RegionActivityState(Map.of(),Map.of()));
            var id=new EventInstanceId(UUID.randomUUID());
            eventContext=BlobContent.capture(
                new ResourceId("proutlost_director:lab_context"),1,
                new byte[]{1,2,3});
            var hash=eventContext.reference().hash();
            eventFixture=new EventInstance(
                new AggregateHeader(1,id.value(),new Revision(1),
                    new Sequence(0),new Tick(10)),
                id,new DefinitionRef(new DefinitionId(
                    "proutlost_director:lab_event"),new Revision(1),hash),
                new Epoch(1),EventState.PROPOSED,"proposed",
                new RoleAssignments(Map.of()),Set.of(regionId),
                eventContext.reference(),
                Map.of(),Set.of(),Set.of(),Set.of(),Optional.empty(),Set.of(),
                Optional.empty(),new DurationTicks(0),new DurationTicks(100),
                RecoveryMode.RECOVER,Optional.empty());
            var chainId=new ChainId(UUID.randomUUID());
            chainFixture=new ChainInstance(
                new AggregateHeader(1,chainId.value(),new Revision(1),
                    new Sequence(0),new Tick(10)),chainId,
                new DefinitionRef(new DefinitionId("proutlost_director:lab_chain"),
                    new Revision(1),hash),
                ChainMode.SHORT_ARC,ChainState.WAITING,
                Set.of("START"),Set.of(),Set.of(regionId),
                List.of(),List.of(),Optional.empty(),new UnitValue(.5),
                new Tick(100));
            directory=server.getWorldPath(LevelResource.ROOT)
                .resolve("director-durable-region-index-test-only")
                .resolve(UUID.randomUUID().toString());
            opening=JournalStore.open(directory,world,OPTIONS).toCompletableFuture();
        }
        void actor(){
            if(!server.isSameThread())
                throw new IllegalStateException("J01 region index Minecraft actor");
        }
        void verifyRecovered(GameTestHelper helper,JournalStore store){
            var config=DirectorLabObservationProfile.load();
            var projection=new DurableMemoryPort(store,new MemorySettings(config,false));
            var storeQuery=new StoreQuery(world,Set.of(),new Sequence(0),
                Optional.empty(),64);
            var page=projection.refresh(storeQuery);
            helper.assertTrue(!page.hasMore(),"one bounded WAL region fixture page");
            var actual=projection.view(new ScopeSet(world,
                Set.of(new ScopeRef(ScopeKind.WORLD,world.value()))));
            helper.assertTrue(actual.regionRecords().size()==1 &&
                actual.regions().size()==1 &&
                actual.regionRecords().get(regionId).equals(region) &&
                actual.regions().getFirst().equals(memory),
                "J04 WAL did not restore exact RegionAggregate and memory");
            var adminRows=DirectorNativeAdminReadBridge.memoryRows(
                new RegionFilter(Optional.of(regionId),Set.of()),actual);
            helper.assertTrue(adminRows.size()==1 &&
                adminRows.getFirst().data() instanceof RegionAdminData data &&
                data.region().equals(region) && data.memory().equals(memory),
                "J07 server MEMORY did not return exactly J04 recovered region");
            helper.assertTrue(DirectorNativeAdminReadBridge.memoryRows(
                new RegionFilter(Optional.of(new RegionId(UUID.randomUUID())),
                    Set.of()),actual).isEmpty(),
                "J07 invented data for an unknown regional identity");
            var worldState=new DirectorWorldState(
                new AggregateHeader(1,world.value(),new Revision(1),
                    new Sequence(0),new Tick(10)),
                world,store.session().storeGeneration(),new Tick(10),
                RunControl.PAUSED,HealthMode.READ_ONLY_SAFE,
                PacingState.REST,GlobalTension.CALM,
                new ResourceId("proutlost_director:lab"),new Fingerprint("b".repeat(64)),
                config.provenanceHash(),store.session().bootEpoch(),new Sequence(0),
                new EngineControlState(1,new Revision(0),new Tick(10),new Tick(10),
                    new UnitValue(0),new Tick(10),Optional.empty(),new UnitValue(0)));
            var activeQuery=new AdminQuery(new SessionId(UUID.randomUUID()),
                new RequestId(UUID.randomUUID()),AdminPanel.ACTIVE,
                new EventFilter(Optional.of(eventFixture.instanceId()),Set.of()),
                Optional.empty(),Optional.empty(),5);
            var active=DirectorNativeAdminReadBridge.activePage(
                store,activeQuery,worldState);
            helper.assertTrue(active.isPresent() &&
                active.orElseThrow().rows().size()==1 &&
                active.orElseThrow().rows().getFirst().data()
                    instanceof EventAdminData item &&
                item.event().equals(eventFixture),
                "J07 real J04 EventAggregate not visible in ACTIVE");
            var other=new AdminQuery(activeQuery.actorSession(),
                new RequestId(UUID.randomUUID()),AdminPanel.ACTIVE,
                new EventFilter(Optional.of(new EventInstanceId(UUID.randomUUID())),Set.of()),
                Optional.empty(),Optional.empty(),5);
            var absent=DirectorNativeAdminReadBridge.activePage(store,other,worldState);
            helper.assertTrue(absent.isPresent() && absent.orElseThrow().rows().isEmpty(),
                "J07 invented a missing event instance");
            System.out.println("DIRECTOR_NATIVE_J07_ACTUAL_EVENT_AGGREGATE_ACTIVE_WAL_PASS");
            var exactCut=active.orElseThrow().lastDurableSequence();
            helper.assertTrue(DirectorNativeAdminReadBridge.sameWalCut(
                    active,exactCut).equals(active),
                "J07 rejected a matching actual J04 durable read cut");
            helper.assertTrue(DirectorNativeAdminReadBridge.sameWalCut(
                    active,new Sequence(Math.addExact(exactCut.value(),1))).isEmpty(),
                "J07 mixed a stale WORLD with a newer ACTIVE WAL read");
            if(exactCut.value()>0)
                helper.assertTrue(DirectorNativeAdminReadBridge.sameWalCut(
                        active,new Sequence(exactCut.value()-1)).isEmpty(),
                    "J07 mixed a newer WORLD with an older ACTIVE WAL read");
            helper.assertTrue(DirectorNativeAdminReadBridge.sameWalCut(
                    Optional.empty(),exactCut).isEmpty(),
                "J07 fabricated an unavailable ACTIVE WAL page");
            System.out.println("DIRECTOR_NATIVE_J07_EXACT_WORLD_WAL_CUT_PASS");
            var chainQuery=new AdminQuery(activeQuery.actorSession(),
                new RequestId(UUID.randomUUID()),AdminPanel.ACTIVE,
                new ChainFilter(Optional.of(chainFixture.chainId())),
                Optional.empty(),Optional.empty(),5);
            var chainPage=DirectorNativeAdminReadBridge.activePage(
                store,chainQuery,worldState);
            helper.assertTrue(chainPage.isPresent() &&
                chainPage.orElseThrow().rows().size()==1 &&
                chainPage.orElseThrow().rows().getFirst().data()
                    instanceof ChainAdminData info &&
                info.chain().equals(chainFixture),
                "J07 real J04 ChainAggregate not visible in ACTIVE");
            System.out.println("DIRECTOR_NATIVE_J07_ACTUAL_CHAIN_AGGREGATE_ACTIVE_WAL_PASS");
            // Discover the real latest aggregate by paginating immutable WAL
            // records one at a time. Empty intermediate pages are NOT absence;
            // every returned event/chain must be rehydrated from J04.
            for(var listedFilter:List.<AdminFilter>of(
                    new EventFilter(Optional.empty(),Set.of(EventState.PROPOSED)),
                    new ChainFilter(Optional.empty()))) {
                Optional<String> cursor=Optional.empty();
                var cursors=new HashSet<String>();
                boolean found=false;
                for(int turn=0;turn<16;turn++) {
                    var list=new AdminQuery(activeQuery.actorSession(),
                        new RequestId(UUID.randomUUID()),AdminPanel.ACTIVE,
                        listedFilter,cursor,Optional.empty(),1);
                    var read=DirectorNativeAdminReadBridge.activePage(
                        store,list,worldState);
                    helper.assertTrue(read.isPresent() &&
                        read.orElseThrow().rows().size()<=1 &&
                        read.orElseThrow().capabilities().equals(fr.proutlost.director.admin.server.NativeReadCommandFence.supported()),
                        "J07 ACTIVE list exceeded bound or invented authority");
                    var row=read.orElseThrow();
                    for(var item:row.rows()) {
                        if(listedFilter instanceof EventFilter &&
                           item.data() instanceof EventAdminData data &&
                           data.event().equals(eventFixture))found=true;
                        if(listedFilter instanceof ChainFilter &&
                           item.data() instanceof ChainAdminData data &&
                           data.chain().equals(chainFixture))found=true;
                    }
                    if(row.nextCursor().isEmpty())break;
                    helper.assertTrue(cursors.add(row.nextCursor().orElseThrow()),
                        "J07 ACTIVE continuation stalled");
                    cursor=row.nextCursor();
                }
                helper.assertTrue(found,
                    "J07 ACTIVE paginated WAL omitted persisted event/chain");
                var forged=new AdminQuery(activeQuery.actorSession(),
                    new RequestId(UUID.randomUUID()),AdminPanel.ACTIVE,
                    listedFilter,Optional.of("invalid-native-j04-cursor"),
                    Optional.empty(),1);
                helper.assertTrue(DirectorNativeAdminReadBridge.activePage(
                    store,forged,worldState).isEmpty(),
                    "J07 ACTIVE untrusted WAL cursor did not fail closed");
            }
            var foreign=new DirectorWorldState(
                new AggregateHeader(1,UUID.randomUUID(),new Revision(1),
                    new Sequence(0),new Tick(10)),
                new WorldId(UUID.randomUUID()),worldState.storeGeneration(),
                worldState.simTick(),worldState.runControl(),
                worldState.healthMode(),worldState.pacingState(),
                worldState.tension(),worldState.intensityProfileId(),
                worldState.catalogHash(),worldState.configHash(),
                worldState.bootEpoch(),worldState.lastCheckpointSeq(),
                worldState.engineControl());
            helper.assertTrue(DirectorNativeAdminReadBridge.activePage(
                store,activeQuery,foreign).isEmpty(),
                "J07 ACTIVE accepted foreign J04 world");
            System.out.println("DIRECTOR_NATIVE_J07_ACTIVE_WAL_PAGED_CURRENT_AGGREGATE_PASS");
            var index=new DirectorLabRegionIndex(server,world,config,
                actual,page.lastDurableSequence().value());
            helper.assertTrue(index.status().records()==1 &&
                index.status().occupiedCells()>0 &&
                index.primary(inside).equals(Optional.of(regionId)),
                "J01 failed to index J04 persisted support geometry");
            helper.assertTrue(index.primary(outside).isEmpty() &&
                index.primary(new BlockPosition(
                    new DimensionId("minecraft:the_nether"),
                    inside.x(),inside.y(),inside.z())).isEmpty(),
                "J01 must never infer region from a nearby chunk/foreign dimension");
            helper.assertTrue(index.status().testOnly(),
                "legacy J01 region constructor must remain TEST_ONLY");
            boolean rejected=false;
            try {
                new DirectorLabRegionIndex(server,world,config,actual,
                    page.lastDurableSequence().value(),ProviderMode.PRODUCTION);
            } catch(IllegalArgumentException expected) { rejected=true; }
            helper.assertTrue(rejected,"production region index accepted TEST calibration");
            rejected=false;
            try {
                new DirectorNativeObservationQualification(server,world,
                    store.session().bootEpoch(),actual,config,ProviderMode.PRODUCTION);
            } catch(IllegalArgumentException expected) { rejected=true; }
            helper.assertTrue(rejected,"production observer accepted TEST calibration");
            // Explicit fixture tests the mode boundary only; it is never
            // registered or admitted as a real production provider.
            var approvedFixture=new ConfigView(config.revision(),config.provenanceHash(),
                Calibration.APPROVED,config.values());
            var productionIndex=new DirectorLabRegionIndex(server,world,
                approvedFixture,actual,page.lastDurableSequence().value(),ProviderMode.PRODUCTION);
            helper.assertTrue(!productionIndex.status().testOnly() &&
                !productionIndex.status().permitsGameplay() &&
                productionIndex.primary(inside).equals(Optional.of(regionId)),
                "explicit J01 production index lost exact J04 membership");
            var productionObserver=new DirectorNativeObservationQualification(server,world,
                store.session().bootEpoch(),actual,approvedFixture,ProviderMode.PRODUCTION);
            try {
                helper.assertTrue(productionObserver.mode()==ProviderMode.PRODUCTION &&
                    !productionObserver.view().testProfile() &&
                    !productionObserver.view().admitsEvents(),
                    "J01 mode boundary invented observation admission authority");
                rejected=false;
                try { productionObserver.testConfiguration(); }
                catch(IllegalStateException expected) { rejected=true; }
                helper.assertTrue(rejected,"production observer exposed legacy TEST configuration");
                rejected=false;
                try { productionObserver.labTestStageAnchor(null); }
                catch(IllegalStateException expected) { rejected=true; }
                helper.assertTrue(rejected,"production observer accepted synthetic anchor hook");
                rejected=false;
                try { productionObserver.labTestCampWindow(null); }
                catch(IllegalStateException expected) { rejected=true; }
                helper.assertTrue(rejected,"production observer accepted synthetic camp hook");
                rejected=false;
                try {
                    ((fr.proutlost.director.adapter.neoforge.server.observation.NeoForgeObservation)
                        productionObserver.observationPort()).labTestCampWindow(null);
                } catch(IllegalStateException expected) { rejected=true; }
                helper.assertTrue(rejected,"production observation port exposed synthetic camp hook");
                // This standalone mode-boundary fixture has no production
                // lifecycle clock. A revoked observer must never ask for one.
                var suspendedView=productionObserver.view();
                productionObserver.suspendSampling();productionObserver.suspendSampling();
                productionObserver.tick();
                productionObserver.refreshRegionIndex(actual,page.lastDurableSequence().value());
                helper.assertTrue(productionObserver.view().samples()==suspendedView.samples()
                    &&productionObserver.snapshot().isEmpty(),
                    "revoked production observer consulted clock or produced new evidence");
            } finally { productionObserver.close(); }
            // An identical WAL sequence is an idempotent actor-only refresh.
            index.refresh(actual,page.lastDurableSequence().value());
            helper.assertTrue(index.primary(inside).equals(Optional.of(regionId)),
                "repeated J01 index refresh lost exact persisted membership");
        }
        void advance(GameTestHelper helper){
            actor();
            switch(phase){
                case 0 -> {
                    helper.assertTrue(opening.isDone()&&!opening.isCompletedExceptionally(),
                        "J04 region test WAL startup pending");
                    store=opening.getNow(null);
                    var batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),
                        store.session().storeGeneration(),Map.of(),
                        List.of(new DurableAggregate(new RegionAggregate(region)),
                                new DurableAggregate(new RegionMemoryAggregate(memory)),
                                new DurableAggregate(new EventAggregate(eventFixture)),
                                new DurableAggregate(new ChainAggregate(chainFixture))),
                        List.of(eventContext));
                    committed=store.append(batch).toCompletableFuture();
                    phase=1;
                }
                case 1 -> {
                    helper.assertTrue(committed.isDone()&&!committed.isCompletedExceptionally(),
                        "J04 region WAL fsync pending");
                    helper.assertTrue(committed.getNow(null).ackStatus()==AckStatus.DURABLE,
                        "native region/event fixture not durably committed; ack="+
                        committed.getNow(null).ackStatus());
                    verifyRecovered(helper,store);
                    closed=store.closeAsync().toCompletableFuture();
                    phase=2;
                }
                case 2 -> {
                    helper.assertTrue(closed.isDone()&&!closed.isCompletedExceptionally(),
                        "J04 region WAL close pending");
                    reopened=JournalStore.open(directory,world,OPTIONS).toCompletableFuture();
                    phase=3;
                }
                case 3 -> {
                    helper.assertTrue(reopened.isDone()&&!reopened.isCompletedExceptionally(),
                        "J04 durable region WAL recovery pending");
                    store=reopened.getNow(null);
                    verifyRecovered(helper,store);
                    closed=store.closeAsync().toCompletableFuture();
                    phase=4;
                }
                case 4 -> {
                    helper.assertTrue(closed.isDone()&&!closed.isCompletedExceptionally(),
                        "region index recovered writer close pending");
                    helper.assertTrue(!DirectorStandaloneMod.directorLifecycle()
                        .snapshot(server).admitsEvents(),
                        "unapproved J01 region fixture promoted to gameplay");
                    System.out.println(
                        "DIRECTOR_NATIVE_J01_J04_DURABLE_REGION_INDEX_RECOVERY_PASS");
                    phase=5;
                }
                case 5 -> {}
                default -> throw new IllegalStateException("unexpected native J01 region stage");
            }
        }
    }

    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
    public static void actualWalRegionSupportIndexedAndRecovered(GameTestHelper helper) {
        var probe=new Probe(helper);
        helper.succeedWhen(()->{
            probe.advance(helper);
            helper.assertTrue(probe.phase==5,
                "J01 actual durable J04 region index after reboot still pending");
        });
    }

    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
    public static void serverJ01ObserverNeverInventsRegionWhenWalHasNone(
            GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null && server.isSameThread(),"server actor");
        helper.succeedWhen(()->{
            var life=DirectorStandaloneMod.directorLifecycle();
            var result=life.observationSnapshot(server);
            helper.assertTrue(result.isPresent(),"await real J01 observer snapshot");
            var observation=life.regionIndexView(server);
            helper.assertTrue(observation.isPresent(),
                "native J01 recovered index absent");
            helper.assertTrue(observation.orElseThrow().testOnly() &&
                !observation.orElseThrow().permitsGameplay(),
                "J01 readonly index illegally promoted");
            // The J04 lab now genuinely creates camp-born regions. An
            // absence assertion is no longer valid across repeat runs:
            // J01 may index only EXACTLY what J04 recovered, no more.
            var persisted=life.labMemoryView(server);
            helper.assertTrue(persisted.isPresent(),
                "await complete J04 memory for region-index equality");
            helper.assertTrue(observation.orElseThrow().records()==
                persisted.orElseThrow().regionRecords().size(),
                "J01 indexed a region unsupported by J04 WAL");
            var missing=new BlockPosition(new DimensionId(
                "proutlost:never_loaded_test_dimension"),0,64,0);
            helper.assertTrue(life.labPrimaryRegion(server,missing).isEmpty(),
                "J01 guessed membership outside J04 support geometry");
            System.out.println("DIRECTOR_NATIVE_J01_J04_REGION_INDEX_ONLY_PASS");
        });
    }

    /** J07 active listing must NOT redisplay old WAL revisions of one event
     * or chain as distinct live entities. A real J04 private lab store is
     * written at rev1, updated at rev2, then reopened after fsync. */
    private static final class MultiRevisionActiveProbe {
        final Probe original;
        final CompletableFuture<JournalStore> opening;
        JournalStore store;
        CompletableFuture<CommitAck> committed;
        CompletableFuture<Void> closed;
        CompletableFuture<JournalStore> reopened;
        EventInstance latestEvent;
        ChainInstance latestChain;
        int phase;

        MultiRevisionActiveProbe(GameTestHelper helper) {
            original=new Probe(helper);
            opening=original.opening;
        }
        void verify(GameTestHelper helper){
            var world=original.world;
            var config=DirectorLabObservationProfile.load();
            var tick=new Tick(40);
            var state=new DirectorWorldState(new AggregateHeader(1,
                world.value(),new Revision(1),new Sequence(0),tick),
                world,store.session().storeGeneration(),tick,
                RunControl.PAUSED,HealthMode.READ_ONLY_SAFE,
                PacingState.REST,GlobalTension.CALM,
                new ResourceId("proutlost_director:lab"),
                new Fingerprint("b".repeat(64)),
                config.provenanceHash(),store.session().bootEpoch(),
                new Sequence(0),new EngineControlState(1,new Revision(0),
                    tick,tick,new UnitValue(0),tick,Optional.empty(),
                    new UnitValue(0)));
            for(var filter:List.<AdminFilter>of(
                new EventFilter(Optional.empty(),Set.of(EventState.PROPOSED)),
                new ChainFilter(Optional.empty()))) {
                var cursor=Optional.<String>empty();
                var cursors=new HashSet<String>();
                var visits=new HashMap<UUID,Integer>();
                for(int pageNum=0;pageNum<16;pageNum++) {
                    var req=new AdminQuery(new SessionId(UUID.randomUUID()),
                        new RequestId(UUID.randomUUID()),AdminPanel.ACTIVE,
                        filter,cursor,Optional.empty(),1);
                    var result=DirectorNativeAdminReadBridge.activePage(
                        store,req,state);
                    helper.assertTrue(result.isPresent() &&
                        result.orElseThrow().rows().size()<=1 &&
                        result.orElseThrow().capabilities().equals(fr.proutlost.director.admin.server.NativeReadCommandFence.supported()),
                        "J07 multiversion active page failed/overproduced");
                    var snapshot=result.orElseThrow();
                    for(var row:snapshot.rows()) {
                        if(row.data() instanceof EventAdminData item) {
                            helper.assertTrue(item.event().equals(latestEvent) &&
                                item.event().header().revision().equals(
                                    new Revision(2)),
                                "stale J07 event revision reappeared on a later page");
                            visits.merge(item.event().instanceId().value(),1,Integer::sum);
                        } else if(row.data() instanceof ChainAdminData item) {
                            helper.assertTrue(item.chain().equals(latestChain) &&
                                item.chain().header().revision().equals(
                                    new Revision(2)),
                                "stale J07 chain revision reappeared on a later page");
                            visits.merge(item.chain().chainId().value(),1,Integer::sum);
                        } else helper.assertTrue(false,
                            "J07 active listing invented unrelated data");
                    }
                    if(snapshot.nextCursor().isEmpty())break;
                    helper.assertTrue(cursors.add(
                        snapshot.nextCursor().orElseThrow()),
                        "J07 multiversion WAL cursor did not advance");
                    cursor=snapshot.nextCursor();
                }
                var required=filter instanceof EventFilter
                    ?latestEvent.instanceId().value()
                    :latestChain.chainId().value();
                helper.assertTrue(visits.equals(Map.of(required,1)),
                    "J07 WAL rev1/rev2 duplicated one live instance");
            }
            helper.assertTrue(!DirectorStandaloneMod.directorLifecycle()
                .snapshot(original.server).admitsEvents(),
                "J07 read-only multiversion test activated gameplay");
            System.out.println(
                "DIRECTOR_NATIVE_J07_ACTIVE_LATEST_REVISION_NO_DUPLICATES_PASS");
        }
        void advance(GameTestHelper helper) {
            original.actor();
            switch(phase){
                case 0 -> {
                    helper.assertTrue(opening.isDone() &&
                        !opening.isCompletedExceptionally(),
                        "J07 revision store starting");
                    store=opening.getNow(null);
                    committed=store.append(ResultTransaction.batch(
                        new TransactionId(UUID.randomUUID()),
                        store.session().storeGeneration(),Map.of(),
                        List.of(
                            new DurableAggregate(new EventAggregate(
                                original.eventFixture)),
                            new DurableAggregate(new ChainAggregate(
                                original.chainFixture))),
                        List.of(original.eventContext))).toCompletableFuture();
                    phase=1;
                }
                case 1 -> {
                    helper.assertTrue(committed.isDone() &&
                        !committed.isCompletedExceptionally() &&
                        committed.getNow(null).ackStatus()==AckStatus.DURABLE,
                        "J07 rev1 Event/Chain not fsynced");
                    var event=original.eventFixture;
                    var eh=event.header();
                    latestEvent=new EventInstance(
                        new AggregateHeader(1,eh.id(),new Revision(2),
                            eh.lastSequence(),new Tick(20)),
                        event.instanceId(),event.definition(),
                        event.catalogGeneration(),event.state(),
                        "proposed-v2",event.roleAssignments(),
                        event.regionIds(),event.contextRef(),event.stepStates(),
                        event.reservationRefs(),event.effectRefs(),
                        event.mutationRefs(),event.chainId(),
                        event.requirementIds(),event.startedTick(),
                        event.elapsedActiveTicks(),event.maxDurationTicks(),
                        event.recoveryPolicy(),event.outcome());
                    var chain=original.chainFixture;
                    var ch=chain.header();
                    latestChain=new ChainInstance(new AggregateHeader(
                        1,ch.id(),new Revision(2),ch.lastSequence(),
                        new Tick(20)),chain.chainId(),chain.recipe(),
                        chain.mode(),chain.state(),Set.of("PHASE_TWO"),
                        chain.consumedFactIds(),chain.lineageRefs(),
                        chain.plannedHorizon(),chain.branchHistory(),
                        chain.regionalStateRef(),chain.relevance(),
                        new Tick(150));
                    committed=store.append(ResultTransaction.batch(
                        new TransactionId(UUID.randomUUID()),
                        store.session().storeGeneration(),
                        Map.of(new ScopeRef(ScopeKind.EVENT,
                            event.instanceId().value()),new Revision(1)),
                        List.of(
                            new DurableAggregate(new EventAggregate(latestEvent)),
                            new DurableAggregate(new ChainAggregate(latestChain))),
                        List.of())).toCompletableFuture();
                    phase=2;
                }
                case 2 -> {
                    helper.assertTrue(committed.isDone() &&
                        !committed.isCompletedExceptionally() &&
                        committed.getNow(null).ackStatus()==AckStatus.DURABLE,
                        "J07 rev2 Event/Chain CAS not fsynced");
                    verify(helper);
                    closed=store.closeAsync().toCompletableFuture();
                    phase=3;
                }
                case 3 -> {
                    helper.assertTrue(closed.isDone() &&
                        !closed.isCompletedExceptionally(),
                        "J07 rev2 WAL close pending");
                    reopened=JournalStore.open(original.directory,
                        original.world,OPTIONS).toCompletableFuture();
                    phase=4;
                }
                case 4 -> {
                    helper.assertTrue(reopened.isDone() &&
                        !reopened.isCompletedExceptionally(),
                        "J07 rev2 WAL restart pending");
                    store=reopened.getNow(null);
                    verify(helper);
                    closed=store.closeAsync().toCompletableFuture();
                    phase=5;
                }
                case 5 -> {
                    helper.assertTrue(closed.isDone() &&
                        !closed.isCompletedExceptionally(),
                        "J07 multiversion store final close pending");
                    phase=6;
                }
                case 6 -> {}
                default -> throw new IllegalStateException(
                    "J07 multiversion test state");
            }
        }
    }

    @GameTest(templateNamespace="proutlost_director",
              template="smoke_empty",timeoutTicks=12000)
    public static void activePagesDoNotRepeatSupersededWalVersions(
            GameTestHelper helper) {
        var probe=new MultiRevisionActiveProbe(helper);
        helper.succeedWhen(()->{
            probe.advance(helper);
            helper.assertTrue(probe.phase==6,
                "J07 rev2 WAL close/reboot pending");
        });
    }

    /** Read-only F8 regression over an actual fsynced/reopened native J04 WAL.
     * Disaster/event identities intentionally differ; no client/OP4 session is faked.
     */
    private static final class DisasterActiveProbe {
        final Probe original;
        final EventInstanceId eventId=new EventInstanceId(UUID.randomUUID());
        final DisasterId disasterId=new DisasterId(UUID.randomUUID());
        final MutationId mutationId=new MutationId(UUID.randomUUID());
        final SessionId actorSession=new SessionId(UUID.randomUUID());
        final DisasterInstance first,latest;
        final MutationRecord mutation;
        JournalStore store;
        CompletableFuture<CommitAck> committed;
        CompletableFuture<Void> closed;
        CompletableFuture<JournalStore> reopened;
        int phase;
        DisasterActiveProbe(GameTestHelper helper){
            original=new Probe(helper);
            var blob=original.eventContext.reference();
            mutation=new MutationRecord(new AggregateHeader(1,mutationId.value(),new Revision(1),new Sequence(0),new Tick(20)),
                mutationId,eventId,new SliceId(UUID.randomUUID()),MutationState.VERIFIED,
                new PrimitiveId("proutlost:trace"),1,
                new Footprint(original.inside.dimension(),Set.of(),Set.of(),List.of()),
                blob,blob,new OwnerToken(UUID.randomUUID()),new Epoch(1),
                new ReadSet(Map.of(),Map.of(),Map.of()),Set.of(),List.of(),Optional.empty(),new Sequence(1));
            first=disaster(new Revision(1),DisasterStage.WATCH,20);
            latest=disaster(new Revision(2),DisasterStage.RESOLVED,30);
        }
        DisasterInstance disaster(Revision revision,DisasterStage stage,long tick){
            var blob=original.eventContext.reference();
            return new DisasterInstance(new AggregateHeader(1,disasterId.value(),revision,new Sequence(0),new Tick(tick)),
                disasterId,eventId,new DefinitionId("proutlost_director:native_f8_disaster"),stage,Map.of(),new UnitValue(.4),
                new Footprint(original.inside.dimension(),Set.of(),Set.of(),List.of()),
                new WarningEvidence(Set.of(),new Tick(10),new DurationTicks(20),blob.hash(),new Epoch(1),false,false),
                Set.of(),List.of(),blob,Set.of(mutationId),"warning_pending",Set.of());
        }
        DirectorWorldState state(){
            var tick=new Tick(40);var hash=original.eventContext.reference().hash();
            return new DirectorWorldState(new AggregateHeader(1,original.world.value(),new Revision(1),new Sequence(0),tick),
                original.world,store.session().storeGeneration(),tick,RunControl.PAUSED,HealthMode.READ_ONLY_SAFE,
                PacingState.REST,GlobalTension.CALM,new ResourceId("proutlost_director:lab"),hash,
                DirectorLabObservationProfile.load().provenanceHash(),store.session().bootEpoch(),new Sequence(0),
                new EngineControlState(1,new Revision(0),tick,tick,new UnitValue(0),tick,Optional.empty(),new UnitValue(.5)));
        }
        DirectorWorldState sessionState(DirectorWorldState w,Epoch boot,Epoch generation){
            return new DirectorWorldState(w.header(),w.worldId(),generation,w.simTick(),w.runControl(),w.healthMode(),
                w.pacingState(),w.tension(),w.intensityProfileId(),w.catalogHash(),w.configHash(),boot,w.lastCheckpointSeq(),w.engineControl());
        }
        AdminQuery query(AdminFilter filter,Optional<String> cursor){
            return new AdminQuery(actorSession,new RequestId(UUID.randomUUID()),AdminPanel.ACTIVE,filter,cursor,Optional.empty(),1);
        }
        AdminSnapshot page(GameTestHelper helper,AdminQuery query,DirectorWorldState world){
            var value=DirectorNativeAdminReadBridge.activePage(store,query,world);
            helper.assertTrue(value.isPresent(),"native F8 disaster page unavailable");
            var reply=value.orElseThrow();
            helper.assertTrue(reply.rows().size()<=1&&new fr.proutlost.director.api.codec.ModelCodec<>(AdminSnapshot.class).encode(reply).length<=22000,
                "native F8 discovery exceeded row/wire bounds");
            helper.assertTrue(reply.capabilities().equals(fr.proutlost.director.admin.server.NativeReadCommandFence.supported()),
                "native F8 disaster read invented runtime rights");
            return reply;
        }
        void verify(GameTestHelper helper){
            var world=state();var target=new EventFilter(Optional.of(eventId),Set.of());
            helper.assertTrue(!eventId.value().equals(disasterId.value()),"fixture identities must be different domains");
            var noEvent=store.view(new StoreQuery(original.world,Set.of(new AggregateKey(AggregateKind.EVENT,eventId.value())),new Sequence(0),Optional.empty(),1));
            helper.assertTrue(noEvent.status()==ReadStatus.AVAILABLE&&noEvent.page().orElseThrow().aggregates().isEmpty(),
                "fixture must not manufacture an EventAggregate for the disaster");
            var initialRequest=query(target,Optional.empty());var initial=page(helper,initialRequest,world);
            helper.assertTrue(initial.rows().size()==1&&initial.nextCursor().isEmpty(),
                "one current disaster must be found immediately behind 2048 unrelated old WAL records");
            var firstRow=initial.rows().getFirst();
            helper.assertTrue(firstRow.scope().equals(new ScopeRef(ScopeKind.EVENT,eventId.value()))
                &&firstRow.data() instanceof DisasterAdminData data&&data.disaster().equals(latest),
                "fast lookup must return the actual latest disaster under its different linked event ID");
            var inspect=new AdminCommand(actorSession,initialRequest.requestId(),world.header().revision(),AdminOperation.EVENT_INSPECT,new EventInspectionPayload(eventId));
            helper.assertTrue(fr.proutlost.director.admin.server.NativeReadCommandFence.answer(inspect,Optional.of(initial)).status()==AdminStatus.SUCCEEDED,
                "resident disaster fallback must be inspectable under its genuine event ID");
            var blank=query(new EventFilter(Optional.empty(),Set.of()),Optional.empty());
            var firstBlank=page(helper,blank,world);
            helper.assertTrue(firstBlank.rows().size()==1&&firstBlank.rows().getFirst().data() instanceof DisasterAdminData,
                "blank ACTIFS must find the real latest RESOLVED disaster before unrelated events/history");
            var readonly=DirectorNativeAdminReadBridge.activePage(new DirectorLabReadOnlyStore(store),blank,world);
            helper.assertTrue(readonly.isPresent()&&readonly.get().rows().equals(firstBlank.rows())
                &&readonly.get().lastDurableSequence().equals(firstBlank.lastDurableSequence()),
                "native read-only wrapper must expose the same current keys/cut without write authority");
            // This is a genuine J04 cursor from a different key set at the SAME cut/session.
            var otherKeys=Set.of(new AggregateKey(AggregateKind.DISASTER,disasterId.value()),
                new AggregateKey(AggregateKind.EVENT,original.eventFixture.instanceId().value()),
                new AggregateKey(AggregateKind.CHAIN,original.chainFixture.chainId().value()));
            var otherRead=store.view(new StoreQuery(original.world,otherKeys,new Sequence(0),Optional.empty(),1));
            var otherCursor=otherRead.page().orElseThrow().nextCursor().orElseThrow();
            helper.assertTrue(DirectorNativeAdminReadBridge.activePage(store,
                query(new EventFilter(Optional.empty(),Set.of()),Optional.of(otherCursor)),world).isEmpty(),
                "a cursor from changed aggregate keys must be STALE, never a silent chronological fallback");
            var keys=store.residentAggregateKeys(Set.of(AggregateKind.DISASTER),4096).orElseThrow();
            helper.assertTrue(keys.keys().equals(Set.of(new AggregateKey(AggregateKind.DISASTER,disasterId.value())))
                &&keys.cut().equals(initial.lastDurableSequence())&&keys.session().equals(store.session()),
                "discovery must contain only actual latest resident disaster keys at the exact session/cut");
            var oneDisaster=store.residentAggregateKeys(Set.of(AggregateKind.DISASTER),1);
            helper.assertTrue(oneDisaster.isPresent()&&oneDisaster.orElseThrow().equals(keys),
                "filtered discovery must fit its one genuine disaster despite unrelated aggregate heads");
            helper.assertTrue(store.residentAggregateKeys(EnumSet.allOf(AggregateKind.class),1).isEmpty(),
                "discovery exceeding its actual matching-key capacity must refuse explicitly, never truncate");
            Optional<String> cursor=initial.nextCursor();int emitted=1,pages=0;AdminSnapshot found=initial;
            while(cursor.isPresent()&&pages++<32){
                var request=query(target,cursor);var reply=page(helper,request,world);
                helper.assertTrue(reply.lastDurableSequence().equals(initial.lastDurableSequence()),"disaster continuation mixed WAL cuts");
                for(var row:reply.rows()){
                    helper.assertTrue(row.scope().equals(new ScopeRef(ScopeKind.EVENT,eventId.value()))&&row.data() instanceof DisasterAdminData data&&data.disaster().equals(latest)&&data.disaster().mutationRefs().equals(Set.of(mutationId)),
                        "fallback did not emit the actual latest disaster with its real mutation references");
                    var command=new AdminCommand(actorSession,request.requestId(),world.header().revision(),AdminOperation.EVENT_INSPECT,new EventInspectionPayload(eventId));
                    helper.assertTrue(fr.proutlost.director.admin.server.NativeReadCommandFence.answer(command,Optional.of(reply)).status()==AdminStatus.SUCCEEDED,
                        "real disaster fallback row not accepted under its linked event ID");emitted++;found=reply;
                }
                cursor=reply.nextCursor();
            }
            helper.assertTrue(cursor.isEmpty()&&emitted==1&&found!=null,
                "disaster discovery repeated an old revision, skipped the latest, or lost continuation");
            helper.assertTrue(DirectorNativeAdminReadBridge.sameWalCut(Optional.of(found),initial.lastDurableSequence()).isPresent()
                &&DirectorNativeAdminReadBridge.sameWalCut(Optional.of(found),new Sequence(initial.lastDurableSequence().value()+1)).isEmpty(),
                "disaster projection must preserve the outer exact WORLD/WAL cut fence");
            var wrongTarget=new EventInstanceId(disasterId.value());cursor=Optional.empty();AdminSnapshot exhausted=null;AdminQuery lastRequest=null;int wrongRows=0;
            for(int n=0;n<32;n++){
                lastRequest=query(new EventFilter(Optional.of(wrongTarget),Set.of()),cursor);exhausted=page(helper,lastRequest,world);wrongRows+=exhausted.rows().size();cursor=exhausted.nextCursor();if(cursor.isEmpty())break;
            }
            helper.assertTrue(wrongRows==0&&cursor.isEmpty(),"DisasterId was incorrectly treated as EventInstanceId");
            var absent=new AdminCommand(actorSession,lastRequest.requestId(),world.header().revision(),AdminOperation.EVENT_INSPECT,new EventInspectionPayload(wrongTarget));
            helper.assertTrue(fr.proutlost.director.admin.server.NativeReadCommandFence.answer(absent,Optional.of(exhausted)).reasonCodes().equals(List.of(fr.proutlost.director.api.error.ReasonCode.NO_TARGET)),
                "exhausted missing event should be NO_TARGET");
            cursor=Optional.empty();int events=0;boolean finished=false;
            for(int n=0;n<32;n++){
                var request=query(new EventFilter(Optional.empty(),EnumSet.allOf(EventState.class)),cursor);var reply=page(helper,request,world);
                for(var row:reply.rows()){
                    helper.assertTrue(row.data() instanceof EventAdminData,"nonempty EventState filters must exclude every DisasterAdminData");events++;
                }
                cursor=reply.nextCursor();if(cursor.isEmpty()){finished=true;break;}
            }
            helper.assertTrue(finished&&events==1,"EventState-filtered listing lost the genuine unrelated event");
            var filtered=page(helper,query(new EventFilter(Optional.of(eventId),Set.of(EventState.RUNNING)),Optional.empty()),world);
            helper.assertTrue(filtered.rows().isEmpty()&&filtered.nextCursor().isEmpty(),"typed event-state inspection must not fall back to a disaster stage");
            helper.assertTrue(DirectorNativeAdminReadBridge.activePage(store,initialRequest,sessionState(world,world.bootEpoch().plus(1),world.storeGeneration())).isEmpty(),
                "disaster lookup accepted a stale/future boot epoch");
            helper.assertTrue(DirectorNativeAdminReadBridge.activePage(store,initialRequest,sessionState(world,world.bootEpoch(),world.storeGeneration().plus(1))).isEmpty(),
                "disaster lookup accepted a different store generation");
            System.out.println("DIRECTOR_NATIVE_F8_DISASTER_ACTUAL_WAL_EVENT_ID_CURSOR_MUTATION_JOIN_PASS boot="+store.session().bootEpoch().value());
            System.out.println("DIRECTOR_NATIVE_F8_CURRENT_KEYS_2048_OLD_RECORDS_IMMEDIATE_RESOLVED_DISASTER_PASS");
        }
        List<DurableRecord> crowdedFixture(){
            var records=new ArrayList<DurableRecord>();
            for(int i=0;i<2048;i++)records.add(new DurableFact(new DirectorFact(new FactId(UUID.randomUUID()),
                new FactType("proutlost_director:native_f8_old_noise"),List.of(new ScopeRef(ScopeKind.WORLD,original.world.value())),
                Optional.empty(),EvidenceStatus.POSSIBLE,Map.of("native_fixture",new JsonValue.Bool(true)),true,Optional.empty())));
            records.addAll(List.of(new DurableAggregate(new RegionAggregate(original.region)),new DurableAggregate(new RegionMemoryAggregate(original.memory)),
                new DurableAggregate(new EventAggregate(original.eventFixture)),new DurableAggregate(new ChainAggregate(original.chainFixture)),
                new DurableAggregate(new MutationAggregate(mutation)),new DurableAggregate(new DisasterAggregate(first))));
            return records;
        }
        void advance(GameTestHelper helper){
            original.actor();
            switch(phase){
                case 0->{
                    if(!original.opening.isDone())return;helper.assertTrue(!original.opening.isCompletedExceptionally(),"native F8 WAL open failed");store=original.opening.getNow(null);
                    committed=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),
                        crowdedFixture(),List.of(original.eventContext))).toCompletableFuture();phase=1;
                }
                case 1->{
                    if(!committed.isDone())return;helper.assertTrue(!committed.isCompletedExceptionally()&&committed.getNow(null).ackStatus()==AckStatus.DURABLE,"native F8 disaster/mutation fixture not durable");
                    committed=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(new DurableAggregate(new DisasterAggregate(latest))),List.of())).toCompletableFuture();phase=2;
                }
                case 2->{
                    if(!committed.isDone())return;helper.assertTrue(!committed.isCompletedExceptionally()&&committed.getNow(null).ackStatus()==AckStatus.DURABLE,"native F8 disaster latest revision not durable");verify(helper);closed=store.closeAsync().toCompletableFuture();phase=3;
                }
                case 3->{if(!closed.isDone())return;helper.assertTrue(!closed.isCompletedExceptionally(),"native F8 WAL close failed");reopened=JournalStore.open(original.directory,original.world,OPTIONS).toCompletableFuture();phase=4;}
                case 4->{if(!reopened.isDone())return;helper.assertTrue(!reopened.isCompletedExceptionally(),"native F8 same-WAL reopen failed");store=reopened.getNow(null);verify(helper);closed=store.closeAsync().toCompletableFuture();phase=5;}
                case 5->{if(!closed.isDone())return;helper.assertTrue(!closed.isCompletedExceptionally(),"native F8 final WAL close failed");phase=6;}
                default->{}
            }
        }
    }
    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
    public static void disasterActiveFallbackUsesRealEventIdAndExactMutationWalCut(GameTestHelper helper){
        var probe=new DisasterActiveProbe(helper);helper.succeedWhen(()->{probe.advance(helper);helper.assertTrue(probe.phase==6,"native F8 disaster read/reopen pending");});
    }
}
