package fr.proutlost.director.neoforge;

import fr.proutlost.director.bootstrap.DirectorServerLifecycle;
import fr.proutlost.director.bootstrap.DirectorNativeStoreBootstrap;
import fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Standalone-only GameTests: no WorldPrep constructor or test fixture.
 * Test class is excluded from the deployable JAR and only discovered
 * when the dedicated nativeGameTests option is set in the test run.
 */
@GameTestHolder(DirectorStandaloneMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DirectorStandaloneNativeGameTests {
    private DirectorStandaloneNativeGameTests(){}

    @GameTest(templateNamespace="proutlost_director",
              template="smoke_empty",timeoutTicks=12000)
    public static void standaloneModLifecycleIsIndependent(GameTestHelper helper){
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null && server.isSameThread(),
            "Director native server actor absent");
        helper.assertTrue(ModList.get().isLoaded("proutlost_director"),
            "Director standalone mod is not loaded");
        helper.assertTrue(!ModList.get().isLoaded("proutlost"),
            "WorldPrep main mod unexpectedly required by Director");
        helper.succeedWhen(()->{
            var life=DirectorStandaloneMod.directorLifecycle();
            var state=life.snapshot(server);
            helper.assertTrue(state.phase()==
                    DirectorServerLifecycle.Phase.OBSERVING_DISABLED,
                "independent Director native bootstrap absent");
            helper.assertTrue(state.observedTicks()>1 &&
                state.missingPortCount()>=9 && !state.admitsEvents(),
                "native bootstrap accidentally enabled uncalibrated gameplay");
            helper.assertTrue(life.activeServerCount()==1,
                "must have one Director actor per dedicated world");
            System.out.println("DIRECTOR_STANDALONE_NATIVE_GAMETEST_LIFECYCLE_PASS");
        });
    }

    @GameTest(templateNamespace="proutlost_director",
              template="smoke_empty",timeoutTicks=12000)
    public static void realJ01AnchorQueueJ04DurableRevisionAndNearby(GameTestHelper helper){
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null&&server.isSameThread(),"server actor");
        var id=new fr.proutlost.director.api.model.AnchorId(java.util.UUID.randomUUID());
        var pos=helper.absolutePos(new net.minecraft.core.BlockPos(1,2,1));
        var dim=new fr.proutlost.director.api.model.DimensionId(
            helper.getLevel().dimension().location().toString());
        var location=new fr.proutlost.director.api.model.BlockPosition(
            dim,pos.getX(),pos.getY(),pos.getZ());
        var original=helper.getLevel().getBlockState(pos);
        var caseState=new java.util.concurrent.atomic.AtomicInteger();
        var staged=new java.util.concurrent.atomic.AtomicReference<
            fr.proutlost.director.api.model.AnchorRecord>();
        helper.succeedWhen(()->{
            var life=DirectorStandaloneMod.directorLifecycle();
            if(caseState.get()==0){
                helper.assertTrue(life.labAnchorStatus(server).isPresent() &&
                    life.labMemoryView(server).isPresent(),
                    "J01 anchor actor/J04 memory startup pending");
                var now=new fr.proutlost.director.api.model.Tick(server.getTickCount());
                var first=new fr.proutlost.director.api.model.AnchorRecord(
                    new fr.proutlost.director.api.model.AggregateHeader(1,id.value(),
                        new fr.proutlost.director.api.model.Revision(1),
                        new fr.proutlost.director.api.model.Sequence(0),now),
                    id,java.util.Optional.empty(),java.util.Set.of(),dim,location,
                    "region.camp_radius_blocks",
                    java.util.Set.of(new fr.proutlost.director.api.model.TagId("proutlost:camp")),
                    fr.proutlost.director.api.model.AnchorOrigin.CAMP,
                    fr.proutlost.director.api.model.EvidenceStatus.POSSIBLE,true);
                var second=new fr.proutlost.director.api.model.AnchorRecord(
                    new fr.proutlost.director.api.model.AggregateHeader(1,id.value(),
                        new fr.proutlost.director.api.model.Revision(2),
                        new fr.proutlost.director.api.model.Sequence(0),now.plus(1)),
                    id,java.util.Optional.empty(),java.util.Set.of(),dim,location,
                    "region.camp_radius_blocks",
                    first.tags(),first.origin(),first.evidence(),false);
                helper.getLevel().setBlock(pos,
                    net.minecraft.world.level.block.Blocks.CAMPFIRE.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
                life.labTestStageAnchor(server,first);
                life.labTestStageAnchor(server,second);
                staged.set(second);
                caseState.set(1);
                helper.assertTrue(false,"await J04 fsync and two anchor revision projections");
            }
            var view=life.labAnchorStatus(server).orElseThrow();
            helper.assertTrue(view.phase()!=
                fr.proutlost.director.bootstrap.DirectorNativeAnchorWalIngestion.Phase.FAILED_SAFE,
                "real J01 anchor writer entered FAILED_SAFE");
            helper.assertTrue(view.durableAnchors()>=2,
                "await 2 consecutive J01/J04 anchor fsync/projection ACKs");
            var projected=life.labMemoryView(server);
            helper.assertTrue(projected.isPresent(),
                "await atomic complete J04 anchor memory projection");
            var memory=projected.orElseThrow();
            helper.assertTrue(staged.get().equals(memory.anchors().get(id)),
                "J04 memory did not retain exact active/inactive anchor revision");
            helper.assertTrue(life.labDurableAnchorsNear(server,location).stream()
                    .noneMatch(a->a.anchorId().equals(id)),
                "inactive J04 anchor was still counted as usable camp");
            helper.assertTrue(!life.snapshot(server).admitsEvents(),
                "J01 place/break became a gameplay permission");
            helper.getLevel().setBlock(pos,original,
                net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            System.out.println("DIRECTOR_STANDALONE_J01_ANCHOR_TWO_REVISIONS_J04_PASS");
        });
    }

    @GameTest(templateNamespace="proutlost_director",
              template="smoke_empty",timeoutTicks=12000)
    public static void nativeJ01CampVisitFromDurableAnchorReachesRegionMemory(
            GameTestHelper helper){
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null&&server.isSameThread(),"server actor");
        var identity=new fr.proutlost.director.api.model.AnchorId(java.util.UUID.randomUUID());
        var block=helper.absolutePos(new net.minecraft.core.BlockPos(1,2,1));
        var location=new fr.proutlost.director.api.model.BlockPosition(
            new fr.proutlost.director.api.model.DimensionId(
                helper.getLevel().dimension().location().toString()),
            block.getX(),block.getY(),block.getZ());
        var origin=helper.getLevel().getBlockState(block);
        var state=new java.util.concurrent.atomic.AtomicInteger();
        helper.succeedWhen(()->{
            var life=DirectorStandaloneMod.directorLifecycle();
            if(state.get()==0){
                helper.assertTrue(life.labAnchorStatus(server).isPresent() &&
                    life.labMemoryView(server).isPresent(),
                    "J04 J01 memory and anchor WAL not ready");
                var tick=new fr.proutlost.director.api.model.Tick(server.getTickCount());
                var anchor=new fr.proutlost.director.api.model.AnchorRecord(
                    new fr.proutlost.director.api.model.AggregateHeader(1,
                        identity.value(),new fr.proutlost.director.api.model.Revision(1),
                        new fr.proutlost.director.api.model.Sequence(0),tick),
                    identity,java.util.Optional.empty(),java.util.Set.of(),
                    location.dimension(),location,"region.camp_radius_blocks",
                    java.util.Set.of(new fr.proutlost.director.api.model.TagId("proutlost:camp")),
                    fr.proutlost.director.api.model.AnchorOrigin.CAMP,
                    fr.proutlost.director.api.model.EvidenceStatus.POSSIBLE,true);
                helper.getLevel().setBlock(block,
                    net.minecraft.world.level.block.Blocks.CAMPFIRE.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
                life.labTestStageAnchor(server,anchor);
                state.set(1);
                helper.assertTrue(false,"await camp J04 confirmation");
            }
            if(state.get()==1){
                var data=life.labMemoryView(server);
                helper.assertTrue(data.isPresent() &&
                    data.orElseThrow().anchors().containsKey(identity) &&
                    !life.labDurableAnchorsNear(server,location).isEmpty(),
                    "J01 must only observe J04-published active camp");
                life.labTestCampWindow(server,identity);
                state.set(2);
                helper.assertTrue(false,"await actual CampSampler sealed J04 memory");
            }
            var view=life.labMemoryView(server);
            helper.assertTrue(view.isPresent(),"region memory pending");
            var candidate=new fr.proutlost.director.api.model.RegionId(identity.value());
            var regional=view.orElseThrow().regions().stream()
                .filter(m->m.regionId().equals(candidate)).findFirst();
            helper.assertTrue(regional.isPresent(),
                "J01 camp episode not persisted by MemoryReducer into J04");
            var camp=regional.orElseThrow().activity().camps().get(identity);
            helper.assertTrue(camp!=null && camp.independentVisits()==1,
                "native camp window did not preserve independent visit");
            helper.assertTrue(!view.orElseThrow().regionRecords().containsKey(candidate),
                "single camp visit erroneously created an active region");
            helper.assertTrue(!life.snapshot(server).admitsEvents(),
                "camp memory granted TEST_ONLY gameplay");
            helper.getLevel().setBlock(block,origin,
                net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            System.out.println("DIRECTOR_STANDALONE_J01_CAMP_VISIT_J04_MEMORY_PASS");
        });
    }

    @GameTest(templateNamespace="proutlost_director",
              template="smoke_empty",timeoutTicks=12000)
    public static void nativeJ07WorldQueryUsesJ04WithOperatorGate(GameTestHelper helper){
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null&&server.isSameThread(),"actor");
        helper.succeedWhen(()->{
            var life=DirectorStandaloneMod.directorLifecycle();
            var world=life.recoveredWorld(server);
            helper.assertTrue(world.isPresent(),"waiting for verified J04 WORLD");
            var state=world.orElseThrow();
            var identity=new fr.proutlost.director.api.model.SessionIdentity(
                state.worldId(),state.bootEpoch(),
                new fr.proutlost.director.api.model.Epoch(1),
                new fr.proutlost.director.api.model.DimensionId("minecraft:overworld"),
                new fr.proutlost.director.api.model.PlayerId(java.util.UUID.randomUUID()));
            var id=fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
                .actorSession(identity);
            var query=new fr.proutlost.director.api.model.AdminQuery(
                id,new fr.proutlost.director.api.model.RequestId(java.util.UUID.randomUUID()),
                fr.proutlost.director.api.model.AdminPanel.WORLD,
                new fr.proutlost.director.api.model.WorldFilter(),
                java.util.Optional.empty(),java.util.Optional.empty(),10);
            var bridge=fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge.class;
            var accepted=fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
                .query(server,identity,true,query);
            helper.assertTrue(accepted.isPresent(),"J07 real J04 WORLD read missing");
            var response=accepted.orElseThrow();
            helper.assertTrue(response.rows().size()==1 &&
                response.capabilities().equals(fr.proutlost.director.admin.server.NativeReadCommandFence.supported()) &&
                response.rows().getFirst().revision().compareTo(
                    state.header().revision())>=0 &&
                response.rows().getFirst().data() instanceof
                    fr.proutlost.director.api.model.PublicStatusData
                    && response.lastDurableSequence().value()>=0,
                "J07 status must derive exactly from J04 and grant zero actions");
            helper.assertTrue(fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
                .query(server,identity,false,query).isEmpty(),
                "J07 nonoperator got private WAL status");
            var wrong=new fr.proutlost.director.api.model.AdminQuery(
                new fr.proutlost.director.api.model.SessionId(java.util.UUID.randomUUID()),
                query.requestId(),query.panel(),query.filter(),
                java.util.Optional.empty(),java.util.Optional.empty(),10);
            helper.assertTrue(fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
                .query(server,identity,true,wrong).isEmpty(),
                "J07 accepted substituted actor session");
            helper.assertTrue(!life.snapshot(server).admitsEvents(),
                "J07 reader illegally admitted physical effects");
            System.out.println("DIRECTOR_STANDALONE_J07_WORLD_WAL_PERMISSION4_PASS");
        });
    }

    /** The J03 scheduler advances J04 WORLD after startup. A live operator
     * must not receive the boot-only revision or a stale pagination stamp. */
    @GameTest(templateNamespace="proutlost_director",
              template="smoke_empty",timeoutTicks=12000)
    public static void nativeJ07WorldReadbackTracksDurableJ03Revision(
            GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null&&server.isSameThread(),"server actor");
        helper.succeedWhen(()->{
            var life=DirectorStandaloneMod.directorLifecycle();
            var scheduler=life.decisionSchedulerStatus(server);
            helper.assertTrue(scheduler.isPresent() &&
                scheduler.orElseThrow().committed()>=1,
                "await real J03 WAL-fsynced evaluation");
            var boot=life.recoveredWorld(server);
            var store=life.labReadOnlyStoreSnapshot(server);
            helper.assertTrue(boot.isPresent()&&store.isPresent(),
                "J04 WORLD boot/read-only store unavailable");
            var before=boot.orElseThrow();
            var readQuery=new StoreQuery(before.worldId(),
                java.util.Set.of(new AggregateKey(
                    AggregateKind.WORLD,before.worldId().value())),
                new Sequence(0),java.util.Optional.empty(),1);
            var page=store.orElseThrow().view(readQuery);
            ReadContracts.validate(page,readQuery,store.orElseThrow().session());
            helper.assertTrue(page.status()==ReadStatus.AVAILABLE &&
                page.page().orElseThrow().aggregates().size()==1,
                "actual latest J04 WORLD missing");
            var latest=(WorldAggregate)page.page().orElseThrow()
                .aggregates().getFirst().state();
            var live=latest.value();
            helper.assertTrue(live.header().revision().compareTo(
                before.header().revision())>0,
                "J03 must actually advance past the bootstrap cache");
            var identity=new SessionIdentity(live.worldId(),live.bootEpoch(),
                new Epoch(17),new DimensionId("minecraft:overworld"),
                new PlayerId(java.util.UUID.randomUUID()));
            var actor=DirectorNativeAdminReadBridge.actorSession(identity);
            var request=new AdminQuery(actor,new RequestId(java.util.UUID.randomUUID()),
                AdminPanel.WORLD,new WorldFilter(),java.util.Optional.empty(),
                java.util.Optional.empty(),1);
            var result=DirectorNativeAdminReadBridge
                .query(server,identity,true,request);
            helper.assertTrue(result.isPresent() &&
                result.orElseThrow().rows().size()==1 &&
                result.orElseThrow().rows().getFirst().revision()
                    .equals(live.header().revision()) &&
                result.orElseThrow().snapshotRevision()
                    .equals(live.header().revision()) &&
                result.orElseThrow().capturedTick().equals(live.simTick()) &&
                result.orElseThrow().lastDurableSequence()
                    .equals(page.page().orElseThrow().lastDurableSequence()) &&
                result.orElseThrow().capabilities().equals(fr.proutlost.director.admin.server.NativeReadCommandFence.supported()),
                "J07 served stale bootstrap WORLD or invented permissions");
            var stale=new AdminQuery(actor,new RequestId(java.util.UUID.randomUUID()),
                AdminPanel.WORLD,new WorldFilter(),java.util.Optional.empty(),
                java.util.Optional.of(before.header().revision()),1);
            helper.assertTrue(DirectorNativeAdminReadBridge
                .query(server,identity,true,stale).isEmpty(),
                "stale boot revision accepted as a live J07 cursor");
            helper.assertTrue(!life.snapshot(server).admitsEvents(),
                "J07 readback granted event admission");
            System.out.println("DIRECTOR_NATIVE_J07_REAL_J03_WORLD_LIVE_READBACK_PASS");
        });
    }

    @GameTest(templateNamespace="proutlost_director",
              template="smoke_empty",timeoutTicks=12000)
    public static void nativeJ07FivePanelsNeverInventPendingData(GameTestHelper helper){
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null&&server.isSameThread(),"actor");
        helper.succeedWhen(()->{
            var life=DirectorStandaloneMod.directorLifecycle();
            var world=life.recoveredWorld(server);
            helper.assertTrue(world.isPresent(),"J04 boot pending");
            var state=world.orElseThrow();
            var identity=new fr.proutlost.director.api.model.SessionIdentity(
                state.worldId(),state.bootEpoch(),
                new fr.proutlost.director.api.model.Epoch(1),
                new fr.proutlost.director.api.model.DimensionId("minecraft:overworld"),
                new fr.proutlost.director.api.model.PlayerId(java.util.UUID.randomUUID()));
            var actor=fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
                .actorSession(identity);
            for(var panel:fr.proutlost.director.api.model.AdminPanel.values()){
                fr.proutlost.director.api.model.AdminFilter filter=switch(panel){
                  case WORLD -> new fr.proutlost.director.api.model.WorldFilter();
                  case ACTIVE -> new fr.proutlost.director.api.model.EventFilter(
                      java.util.Optional.empty(),java.util.Set.of());
                  // Explicit missing region tests absence, even if another
                  // GameTest has legitimately birthed durable camp regions.
                  case MEMORY -> new fr.proutlost.director.api.model.RegionFilter(
                      java.util.Optional.of(new fr.proutlost.director.api.model.RegionId(
                          java.util.UUID.randomUUID())),java.util.Set.of());
                  case DECISIONS -> new fr.proutlost.director.api.model.DecisionFilter(
                      java.util.Optional.empty());
                  case LABORATORY -> new fr.proutlost.director.api.model.LaboratoryFilter(
                      java.util.Optional.empty());
                };
                var request=new fr.proutlost.director.api.model.AdminQuery(
                    actor,new fr.proutlost.director.api.model.RequestId(java.util.UUID.randomUUID()),
                    panel,filter,java.util.Optional.empty(),java.util.Optional.empty(),25);
                var response=fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
                    .query(server,identity,true,request);
                helper.assertTrue(response.isPresent(),"J07 panel wire failed");
                helper.assertTrue(response.orElseThrow().capabilities().equals(fr.proutlost.director.admin.server.NativeReadCommandFence.supported()) &&
                    response.orElseThrow().rows().size()==
                    (panel==fr.proutlost.director.api.model.AdminPanel.WORLD ||
                     panel==fr.proutlost.director.api.model.AdminPanel.LABORATORY
                        ?1:panel==fr.proutlost.director.api.model.AdminPanel.DECISIONS
                            ?response.orElseThrow().rows().size():0) &&
                    (panel!=fr.proutlost.director.api.model.AdminPanel.DECISIONS ||
                     response.orElseThrow().rows().size()<=4),
                    "J07 panel fabricated a row or granted permission");
                if(panel==fr.proutlost.director.api.model.AdminPanel.DECISIONS)
                    helper.assertTrue(response.orElseThrow().rows().stream()
                        .allMatch(row->row.data() instanceof
                            fr.proutlost.director.api.model.DecisionAdminData),
                        "J07 decision listing included non-WAL rows");
                if(panel==fr.proutlost.director.api.model.AdminPanel.LABORATORY){
                    var row=response.orElseThrow().rows().getFirst();
                    helper.assertTrue(row.data() instanceof
                            fr.proutlost.director.api.model.PublicStatusData diagnostic &&
                        diagnostic.reasons().contains(
                            fr.proutlost.director.api.error.ReasonCode.CAPABILITY_MISSING) &&
                        response.orElseThrow().capabilities().equals(fr.proutlost.director.admin.server.NativeReadCommandFence.supported()),
                        "J07 laboratory falsely advertised an approved simulation");
                    var unknown=new fr.proutlost.director.api.model.AdminQuery(
                        actor,new fr.proutlost.director.api.model.RequestId(
                            java.util.UUID.randomUUID()),panel,
                        new fr.proutlost.director.api.model.LaboratoryFilter(
                            java.util.Optional.of(new fr.proutlost.director.api.model.ResourceId(
                                "proutlost_director:unregistered_fixture"))),
                        java.util.Optional.empty(),java.util.Optional.empty(),25);
                    helper.assertTrue(fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
                            .query(server,identity,true,unknown).orElseThrow().rows().isEmpty() &&
                        fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
                            .query(server,identity,false,request).isEmpty(),
                        "J07 fake fixture or unauthenticated laboratory leaked");
                }
            }
            System.out.println("DIRECTOR_STANDALONE_J07_FIVE_PANEL_READONLY_BRIDGE_PASS");
        });
    }

    @GameTest(templateNamespace="proutlost_director",
              template="smoke_empty",timeoutTicks=12000)
    public static void standaloneLabWalAndNetworkAreRealButGameplayDisabled(
            GameTestHelper helper){
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null && server.isSameThread(),
            "server main-thread actor required");
        helper.succeedWhen(()->{
            var life=DirectorStandaloneMod.directorLifecycle();
            var store=life.labStoreSnapshot(server);
            helper.assertTrue(store.isPresent(),
                "standalone native LAB J04 writer not yet requested");
            var state=store.orElseThrow();
            helper.assertTrue(state.phase()==
                    DirectorNativeStoreBootstrap.Phase.RECOVERED_DISABLED &&
                state.hasDurableStore() && !state.gameplayAdmitted(),
                "standalone native WAL not recovered / improperly activated");
            var network=DirectorNativeNetworkBootstrap.status(server);
            helper.assertTrue(network.payloadsRegistered() &&
                network.j04Bound() && !network.effectsAuthorized(),
                "native 13-channel network negotiation lacks recovered J04");
            helper.assertTrue(!life.snapshot(server).admitsEvents(),
                "test-only recovery enabled game effects");
            System.out.println("DIRECTOR_STANDALONE_NATIVE_GAMETEST_WAL_J06_PASS");
        });
    }

    /**
     * Authenticated J07 operator can inspect a whitelisted TEST_ONLY J02
     * preview using the actual recovered J01/J04 memory and WORLD. Neither
     * score-only calculation nor inspection may write a WAL decision or
     * initiate J05 execution. A synthetic player is NOT introduced here.
     */
    @GameTest(templateNamespace="proutlost_director",
              template="smoke_empty",timeoutTicks=16000)
    public static void nativeJ07ScoreOnlyLaboratoryUsesJ04WithoutWorldEffects(
            GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null&&server.isSameThread(),
            "J07 laboratory must run on server actor");
        // This bound is elapsed native game ticks, never the number of
        // successful J04 projection reads (which may be briefly available).
        long firstTestTick=server.getTickCount();
        helper.succeedWhen(()->{
            var life=DirectorStandaloneMod.directorLifecycle();
            var state=life.recoveredWorld(server);
            var memory=life.memoryProjectionView(server);
            var observation=life.observationSnapshot(server);
            helper.assertTrue(state.isPresent()&&memory.isPresent()&&
                memory.orElseThrow().complete()&&observation.isPresent(),
                "await J07 actual J04 recovery");
            var current=state.orElseThrow();
            var identity=new fr.proutlost.director.api.model.SessionIdentity(
                current.worldId(),current.bootEpoch(),
                new fr.proutlost.director.api.model.Epoch(1),
                new fr.proutlost.director.api.model.DimensionId(
                    "minecraft:overworld"),
                new fr.proutlost.director.api.model.PlayerId(
                    java.util.UUID.randomUUID()));
            var actor=fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
                .actorSession(identity);
            var store=life.labReadOnlyStoreSnapshot(server);
            helper.assertTrue(store.isPresent(),"J04 world not published");
            var key=new fr.proutlost.director.api.model.AggregateKey(
                fr.proutlost.director.api.model.AggregateKind.WORLD,
                current.worldId().value());
            var worldQuery=new fr.proutlost.director.api.model.StoreQuery(
                current.worldId(),java.util.Set.of(key),
                new fr.proutlost.director.api.model.Sequence(0),
                java.util.Optional.empty(),1);
            var before=store.orElseThrow().view(worldQuery);
            helper.assertTrue(before.status()==fr.proutlost.director.api.model.ReadStatus.AVAILABLE
                && before.page().orElseThrow().aggregates().size()==1,
                "await J07 exact WORLD readback");
            // A cold/restarted LAB may contain durable regions whose anchor
            // aggregates have not been hydrated. The real J03 region bridge
            // MUST defer such memory, and J07 must not manufacture a score.
            // Distinguish this *negative* safety check from a positive score
            // qualification; never label it a successful simulated preview.
            var projection=life.memoryProjectionView(server).orElseThrow();
            var recoveredMemory=life.labMemoryView(server);
            helper.assertTrue(recoveredMemory.isPresent(),
                "J07 cold-region test requires a hydrated J04 memory view");
            var regional=new fr.proutlost.director.bootstrap.DirectorNativeRegionalPlanningBridge(
                fr.proutlost.director.bootstrap.DirectorLabObservationProfile.load())
                .project(observation.orElseThrow(),recoveredMemory.orElseThrow(),
                    projection.lastDurableSequence());
            boolean staleProjection=projection.lastDurableSequence()<
                before.page().orElseThrow().lastDurableSequence().value();
            boolean coldRegionalContext=regional.deferred();
            // An immediately stale WORLD/MEMORY cut is common at startup
            // while J03 is committing. Give the real bounded projection
            // time to catch up before qualifying a persistent cold refusal.
            if((staleProjection || coldRegionalContext) &&
                server.getTickCount()-firstTestTick<400)
                helper.assertTrue(false,"await bounded J04 rehydration window");
            for(var fixture:java.util.List.of(
                    fr.proutlost.director.bootstrap.DirectorNativeLaboratoryPreview.SAFE_MICRO,
                    fr.proutlost.director.bootstrap.DirectorNativeLaboratoryPreview.CAMP_REGROWTH)){
                var query=new fr.proutlost.director.api.model.AdminQuery(actor,
                    new fr.proutlost.director.api.model.RequestId(
                        java.util.UUID.randomUUID()),
                    fr.proutlost.director.api.model.AdminPanel.LABORATORY,
                    new fr.proutlost.director.api.model.LaboratoryFilter(
                        java.util.Optional.of(fixture)),
                    java.util.Optional.empty(),java.util.Optional.empty(),2);
                var preview=fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
                    .query(server,identity,true,query);
                // Verify the permission gate also on the cold/negative path.
                helper.assertTrue(fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
                    .query(server,identity,false,query).isEmpty(),
                    "J07 unprivileged viewer obtained a laboratory result");
                if(staleProjection) {
                    helper.assertTrue(preview.isEmpty(),
                        "J07 leaked a score past an unhydrated J04 WAL sequence");
                    continue;
                }
                if(coldRegionalContext) {
                    // This is an explicit STALE_CONTEXT diagnostic, NOT a
                    // passed score-only preview or a fake candidate result.
                    helper.assertTrue(preview.isPresent() &&
                        preview.orElseThrow().rows().size()==1 &&
                        preview.orElseThrow().rows().getFirst().data()
                            instanceof fr.proutlost.director.api.model.PublicStatusData &&
                        preview.orElseThrow().capabilities().equals(fr.proutlost.director.admin.server.NativeReadCommandFence.supported()),
                        "J07 fabricated a score for incomplete native regions");
                    var diagnostic=(fr.proutlost.director.api.model.PublicStatusData)
                        preview.orElseThrow().rows().getFirst().data();
                    helper.assertTrue(diagnostic.localAnomaly() &&
                        diagnostic.reasons().contains(
                            fr.proutlost.director.api.error.ReasonCode.STALE_CONTEXT) &&
                        diagnostic.reasons().contains(
                            fr.proutlost.director.api.error.ReasonCode.CAPABILITY_MISSING),
                        "J07 cold-context diagnostic omitted its blocking reason");
                    continue;
                }
                helper.assertTrue(preview.isPresent()&&
                    preview.orElseThrow().rows().size()==1,
                    "J07 qualified score fixture absent despite complete regional J04");
                var result=preview.orElseThrow();
                helper.assertTrue(result.capabilities().equals(fr.proutlost.director.admin.server.NativeReadCommandFence.supported()) &&
                    result.rows().getFirst().data() instanceof
                        fr.proutlost.director.api.model.LaboratoryAdminData,
                    "J07 score-only result advertised gameplay permission");
                var payload=(fr.proutlost.director.api.model.LaboratoryAdminData)
                    result.rows().getFirst().data();
                helper.assertTrue(payload.fixtureId().equals(fixture) &&
                    payload.proposal().selectedPlan().isEmpty() &&
                    payload.proposal().stateChanges().stream()
                        .allMatch(c->c.next() instanceof
                            fr.proutlost.director.api.model.WorldAggregate) &&
                    payload.admissionReasons().contains(
                        fr.proutlost.director.api.error.ReasonCode.CAPABILITY_MISSING),
                    "J07 what-if preview accidentally produced an executable plan");
            }
            var unknown=new fr.proutlost.director.api.model.AdminQuery(actor,
                new fr.proutlost.director.api.model.RequestId(java.util.UUID.randomUUID()),
                fr.proutlost.director.api.model.AdminPanel.LABORATORY,
                new fr.proutlost.director.api.model.LaboratoryFilter(
                    java.util.Optional.of(new fr.proutlost.director.api.model.ResourceId(
                        "proutlost_director:unknown_fixture"))),
                java.util.Optional.empty(),java.util.Optional.empty(),2);
            var unknownPreview=fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
                .query(server,identity,true,unknown);
            helper.assertTrue(staleProjection?unknownPreview.isEmpty():
                unknownPreview.isPresent() && unknownPreview.orElseThrow().rows().isEmpty(),
                "unregistered J02 fixture was fabricated");
            var after=store.orElseThrow().view(worldQuery);
            helper.assertTrue(after.page().orElseThrow().aggregates().equals(
                before.page().orElseThrow().aggregates()) &&
                !life.snapshot(server).admitsEvents(),
                "J07 score-only preview modified J04 WORLD or admitted gameplay");
            if(staleProjection || coldRegionalContext)
                System.out.println("DIRECTOR_NATIVE_J07_SCORE_ONLY_DEFERRED_COLD_J04_PASS"
                    +" reason="+(staleProjection?"STALE_WAL_CUT":"INCOMPLETE_REGION_ANCHORS")
                    +" awaited_ticks="+(server.getTickCount()-firstTestTick));
            else
                System.out.println(
                    "DIRECTOR_NATIVE_J07_TWO_REAL_J02_FIXTURES_SCORE_ONLY_J04_PASS");
        });
    }
}
