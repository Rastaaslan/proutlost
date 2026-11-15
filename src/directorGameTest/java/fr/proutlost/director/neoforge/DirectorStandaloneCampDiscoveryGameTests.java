package fr.proutlost.director.neoforge;

import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

/**
 * End-to-end J01 camp discovery with TEST_ONLY virtual witnessed visits.
 * Deliberately waits real server simTicks for seven independent episodes,
 * not seven duplicates; the true J01 CampSampler/Reducer/WAL handles them.
 * No user gameplay admission or real player identity is fabricated.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorStandaloneCampDiscoveryGameTests {
    private DirectorStandaloneCampDiscoveryGameTests(){}

    /** Uses the actual registered NeoForge block-event callbacks with a
     * server-side FakePlayer. Unlike labTestStageAnchor this path does not
     * inject an AnchorRecord. This does NOT claim any real network client.
     */
    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",
              timeoutTicks=12000)
    public static void neoforgeCampfireHooksCreateAndDeactivateDurableAnchor(
            GameTestHelper helper){
        var level=helper.getLevel();
        var server=level.getServer();
        helper.assertTrue(server!=null&&server.isSameThread(),"server actor");
        var block=helper.absolutePos(new BlockPos(1,2,1));
        var pos=new BlockPosition(
            new DimensionId(level.dimension().location().toString()),
            block.getX(),block.getY(),block.getZ());
        var previous=level.getBlockState(block);
        var phase=new AtomicInteger();
        var seen=new AtomicReference<AnchorId>();
        helper.succeedWhen(()->{
            var director=DirectorStandaloneMod.directorLifecycle();
            if(phase.get()==0){
                helper.assertTrue(director.labAnchorStatus(server).isPresent() &&
                    director.labMemoryView(server).isPresent(),
                    "waiting for native J01 and recovered J04");
                var fake=net.neoforged.neoforge.common.util.FakePlayerFactory
                    .getMinecraft(level);
                var prior=net.neoforged.neoforge.common.util.BlockSnapshot.create(
                    level.dimension(),level,block);
                level.setBlock(block,Blocks.CAMPFIRE.defaultBlockState(),
                    Block.UPDATE_CLIENTS);
                var placed=new net.neoforged.neoforge.event.level.BlockEvent
                    .EntityPlaceEvent(prior,previous,fake);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(placed);
                helper.assertTrue(!placed.isCanceled(),
                    "native lab place event was cancelled");
                phase.set(1);
                helper.assertTrue(false,"await place hook J04 fsync");
            }
            if(phase.get()==1){
                var view=director.labMemoryView(server);
                helper.assertTrue(view.isPresent(),"await J04 hook publication");
                var matches=view.orElseThrow().anchors().values().stream()
                    .filter(a->a.position().equals(pos)&&a.active() &&
                        a.origin()==AnchorOrigin.CAMP).toList();
                helper.assertTrue(matches.size()==1,
                    "real NeoForge place listener did not create one J04 anchor");
                var id=matches.getFirst().anchorId();
                seen.set(id);
                helper.assertTrue(director.labDurableAnchorsNear(server,pos)
                    .stream().anyMatch(a->a.anchorId().equals(id)),
                    "placed camp anchor not published to native lookup");
                var fake=net.neoforged.neoforge.common.util.FakePlayerFactory
                    .getMinecraft(level);
                var broken=new net.neoforged.neoforge.event.level.BlockEvent
                    .BreakEvent(level,block,level.getBlockState(block),fake);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(broken);
                helper.assertTrue(!broken.isCanceled(),
                    "native lab break event was cancelled");
                level.setBlock(block,Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_CLIENTS);
                phase.set(2);
                helper.assertTrue(false,"await break hook J04 fsync");
            }
            var view=director.labMemoryView(server);
            helper.assertTrue(view.isPresent(),"await completed J04 projection");
            var anchor=view.orElseThrow().anchors().get(seen.get());
            helper.assertTrue(anchor!=null && !anchor.active() &&
                    anchor.header().revision().value()==2,
                "NeoForge break listener failed J04 revision-2 deactivation");
            helper.assertTrue(director.labDurableAnchorsNear(server,pos).stream()
                    .noneMatch(a->a.anchorId().equals(seen.get())),
                "broken J04 campfire reappeared in native nearby lookup");
            helper.assertTrue(!director.snapshot(server).admitsEvents(),
                "real NeoForge block hooks granted unauthorized event rights");
            level.setBlock(block,previous,Block.UPDATE_CLIENTS);
            System.out.println("DIRECTOR_NATIVE_J01_REAL_NEOFORGE_BLOCK_EVENT_HOOKS_J04_PASS");
        });
    }

    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",
              timeoutTicks=24000)
    public static void sevenIndependentCampVisitsBecomeOneDurableRegion(
            GameTestHelper helper){
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null&&server.isSameThread(),"Minecraft actor");
        var anchorId=new AnchorId(UUID.randomUUID());
        var candidateRegionId=new RegionId(anchorId.value());
        BlockPos block=helper.absolutePos(new BlockPos(1,2,1));
        var location=new BlockPosition(
            new DimensionId(helper.getLevel().dimension().location().toString()),
            block.getX(),block.getY(),block.getZ());
        var original=helper.getLevel().getBlockState(block);
        var phase=new AtomicInteger(0);
        var visits=new AtomicInteger(0);
        var nextVisitTick=new AtomicLong(0);
        var firstWorldAudit=new AtomicLong(-1);
        var lastReportedIndependent=new AtomicLong(-1);
        helper.succeedWhen(()->{
            var director=DirectorStandaloneMod.directorLifecycle();
            if(phase.get()==0) {
                helper.assertTrue(director.labAnchorStatus(server).isPresent() &&
                    director.labMemoryView(server).isPresent(),
                    "waiting for J04 native anchors");
                var tick=new Tick(server.getTickCount());
                var record=new AnchorRecord(
                    new AggregateHeader(1,anchorId.value(),new Revision(1),
                        new Sequence(0),tick),anchorId,Optional.empty(),Set.of(),
                    location.dimension(),location,"region.camp_radius_blocks",
                    Set.of(new TagId("proutlost:camp")),AnchorOrigin.CAMP,
                    EvidenceStatus.POSSIBLE,true);
                helper.getLevel().setBlock(block,Blocks.CAMPFIRE.defaultBlockState(),
                    Block.UPDATE_CLIENTS);
                director.labTestStageAnchor(server,record);
                phase.set(1);
                helper.assertTrue(false,"waiting for durable J04 anchor");
            }
            if(phase.get()==1) {
                var memory=director.labMemoryView(server);
                helper.assertTrue(memory.isPresent() &&
                    memory.orElseThrow().anchors().containsKey(anchorId) &&
                    director.labDurableAnchorsNear(server,location).stream()
                      .anyMatch(a->a.anchorId().equals(anchorId)),
                    "camp only counts after J04 anchor fsync and publication");
                nextVisitTick.set(server.getTickCount());
                director.decisionSchedulerStatus(server).ifPresent(
                    s->firstWorldAudit.compareAndSet(-1,s.committed()));
                phase.set(2);
            }
            if(phase.get()==2) {
                if(visits.get()<7 && server.getTickCount()>=nextVisitTick.get()){
                    director.labTestCampWindow(server,anchorId);
                    int completed=visits.incrementAndGet();
                    nextVisitTick.set(server.getTickCount()+1300L);
                    System.out.println("DIRECTOR_STANDALONE_CAMP_INDEPENDENT_VISIT "+
                        completed+" tick="+server.getTickCount());
                }
                if(visits.get()<7)
                    helper.assertTrue(false,"waiting for independent camp episodes");
                var memory=director.labMemoryView(server);
                helper.assertTrue(memory.isPresent(),"waiting for J04 candidate memories");
                var camp=memory.orElseThrow().regions().stream()
                    .filter(m->m.regionId().equals(candidateRegionId))
                    .findFirst();
                helper.assertTrue(camp.isPresent(),
                    "seven independent camp visits not reduced into J04 memory");
                var routine=camp.orElseThrow().activity().camps().get(anchorId);
                if(routine!=null &&
                   lastReportedIndependent.getAndSet(routine.independentVisits())
                       !=routine.independentVisits())
                    System.out.println("DIRECTOR_CAMP_CANDIDATE_DIAGNOSTIC"+
                        " independent="+routine.independentVisits()+
                        " confidence="+routine.confidence().value()+
                        " windows="+routine.windows().size()+
                        " samples="+visits.get());
                helper.assertTrue(routine!=null &&
                    routine.independentVisits()>=7 &&
                    routine.confidence().value()>=.65,
                    "camp evidence confidence threshold not reached");
                var born=memory.orElseThrow().regionRecords().get(candidateRegionId);
                helper.assertTrue(born!=null && born.status()==RegionStatus.ACTIVE &&
                    born.anchorIds().contains(anchorId),
                    "J01 failed to create recognized region after camp visits");
                var linked=memory.orElseThrow().anchors().get(anchorId);
                helper.assertTrue(linked!=null &&
                    linked.regionIds().contains(candidateRegionId) &&
                    linked.evidence()==EvidenceStatus.CONFIRMED,
                    "J04 did not assign recognized camp anchor to new region");
                helper.assertTrue(director.labPrimaryRegion(server,location)
                    .filter(candidateRegionId::equals).isPresent(),
                    "J04 confirmed region did not enter actor-local sparse index");
                var scheduler=director.decisionSchedulerStatus(server);
                helper.assertTrue(scheduler.isPresent() &&
                    scheduler.orElseThrow().phase()!=
                        fr.proutlost.director.bootstrap.DirectorNativeDecisionScheduler.Phase.FAILED_SAFE &&
                    scheduler.orElseThrow().committed()>firstWorldAudit.get(),
                    "J03 stopped durably auditing after regional camp discovery");
                helper.assertTrue(scheduler.orElseThrow().evaluatedRegions()>=1 &&
                    scheduler.orElseThrow().evaluatedIndependentCampVisits()>=7 &&
                    scheduler.orElseThrow().evaluatedJ04Sequence()>0,
                    "J03 audit not proven to consume the 7-visit J04 region memory");
                helper.assertTrue(scheduler.orElseThrow().evaluatedRegionContexts()>=1 &&
                    scheduler.orElseThrow().evaluatedCampSnapshots()>=1 &&
                    !scheduler.orElseThrow().regionalContextDeferred(),
                    "J03 decision audit did not include the J04-backed RegionSnapshot");
                System.out.println("DIRECTOR_NATIVE_J03_REAL_J04_REGIONAL_CONTEXT_WAL_PASS"+
                    " regions="+scheduler.orElseThrow().evaluatedRegionContexts()+
                    " camps="+scheduler.orElseThrow().evaluatedCampSnapshots());
                var world=director.recoveredWorld(server).orElseThrow();
                var identity=new SessionIdentity(world.worldId(),world.bootEpoch(),
                    new Epoch(1),location.dimension(),
                    new PlayerId(UUID.randomUUID()));
                var query=new AdminQuery(
                    fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
                        .actorSession(identity),
                    new RequestId(UUID.randomUUID()),AdminPanel.MEMORY,
                    new RegionFilter(Optional.of(candidateRegionId),Set.of()),
                    Optional.empty(),Optional.empty(),10);
                var admin=fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
                    .query(server,identity,true,query);
                helper.assertTrue(admin.isPresent() &&
                    admin.orElseThrow().rows().size()==1 &&
                    admin.orElseThrow().rows().getFirst().data() instanceof
                        RegionAdminData row &&
                    row.region().regionId().equals(candidateRegionId) &&
                    row.memory().activity().camps().containsKey(anchorId) &&
                    row.memory().activity().camps().get(anchorId)
                        .independentVisits()>=7 &&
                    admin.orElseThrow().capabilities().equals(fr.proutlost.director.admin.server.NativeReadCommandFence.supported()),
                    "J07 failed to render true recovered camp routine without rights");
                helper.assertTrue(!director.snapshot(server).admitsEvents(),
                    "recognized camp from TEST_ONLY illegally started gameplay");
                helper.getLevel().setBlock(block,original,Block.UPDATE_CLIENTS);
                System.out.println("DIRECTOR_STANDALONE_J01_CAMP_REGION_BIRTH_J04_PASS"+
                    " visits="+routine.independentVisits()+
                    " confidence="+routine.confidence().value());
                phase.set(3);
            }
            helper.assertTrue(phase.get()==3,"camp J04 discovery pending");
        });
    }

    /** A committed J04 anchor is not sufficient to certify a physical
     * camp after an out-of-band (non-player) block edit. Deliberately leaves
     * the J04 record active to exercise the false-visit prevention path. */
    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",
              timeoutTicks=12000)
    public static void staleJ04CampAnchorCannotFabricateVisits(
            GameTestHelper helper){
        var level=helper.getLevel();
        var server=level.getServer();
        helper.assertTrue(server!=null&&server.isSameThread(),"server actor");
        var block=helper.absolutePos(new BlockPos(2,2,2));
        var location=new BlockPosition(
            new DimensionId(level.dimension().location().toString()),
            block.getX(),block.getY(),block.getZ());
        var anchorId=new AnchorId(UUID.randomUUID());
        var original=level.getBlockState(block);
        var phase=new AtomicInteger(0);
        helper.succeedWhen(()->{
            var life=DirectorStandaloneMod.directorLifecycle();
            var memory=life.labMemoryView(server);
            helper.assertTrue(memory.isPresent(),"await durable J04 recovery");
            if(phase.get()==0){
                var record=new AnchorRecord(
                    new AggregateHeader(1,anchorId.value(),new Revision(1),
                        new Sequence(0),new Tick(server.getTickCount())),
                    anchorId,Optional.empty(),Set.of(),location.dimension(),
                    location,"region.camp_radius_blocks",
                    Set.of(new TagId("proutlost:camp")),AnchorOrigin.CAMP,
                    EvidenceStatus.POSSIBLE,true);
                level.setBlock(block,Blocks.CAMPFIRE.defaultBlockState(),
                    Block.UPDATE_CLIENTS);
                life.labTestStageAnchor(server,record);
                phase.set(1);
                helper.assertTrue(false,"await real J04 fsync before removal");
            }
            var persisted=memory.orElseThrow().anchors().get(anchorId);
            helper.assertTrue(persisted!=null && persisted.active() &&
                life.labDurableAnchorsNear(server,location).stream()
                    .anyMatch(a->a.anchorId().equals(anchorId)),
                "await committed active camp anchor");
            // No player event: simulate an external mod or environment edit.
            level.setBlock(block,Blocks.STONE.defaultBlockState(),
                Block.UPDATE_CLIENTS);
            boolean rejected=false;
            try{life.labTestCampWindow(server,anchorId);}
            catch(IllegalStateException safe){
                rejected="unloaded or missing physical campfire".equals(
                    safe.getMessage());
            }
            helper.assertTrue(rejected,
                "unverified J04 camp created a phantom visit");
            helper.assertTrue(memory.orElseThrow().regions().stream()
                    .noneMatch(region->region.regionId().value().equals(
                        anchorId.value())),
                "out-of-band camp destruction generated regional memory");
            helper.assertTrue(!life.snapshot(server).admitsEvents(),
                "missing physical camp granted gameplay admission");
            level.setBlock(block,original,Block.UPDATE_CLIENTS);
            phase.set(2);
            System.out.println("DIRECTOR_NATIVE_J01_STALE_CAMP_FAIL_CLOSED_PASS");
        });
    }
}
