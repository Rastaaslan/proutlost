package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import fr.proutlost.director.bootstrap.DirectorNativeDecisionScheduler;
import fr.proutlost.director.bootstrap.DirectorNativeRegionalPlanningBridge;
import fr.proutlost.director.bootstrap.DirectorLabObservationProfile;
import fr.proutlost.director.api.model.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

/** Server-side real actor J03 calculation and durable J04 audit.
 * No qualified events exist in this TEST_ONLY lab: zero physical effects.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeScheduledDecisionGameTests {
    private DirectorNativeScheduledDecisionGameTests() {}

    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
    public static void realNativeJ03CycleProducesDurableJ04Audit(GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null && server.isSameThread(),"server actor");
        helper.succeedWhen(()->{
            var life=DirectorStandaloneMod.directorLifecycle();
            var scheduler=life.decisionSchedulerStatus(server);
            helper.assertTrue(scheduler.isPresent(),"native J03 scheduler not yet bound");
            var status=scheduler.orElseThrow();
            helper.assertTrue(status.phase()!=DirectorNativeDecisionScheduler.Phase.FAILED_SAFE,
                "real engine J03 to J04 journal failed");
            helper.assertTrue(status.committed()>=1,
                "real actor evaluation not yet durably acknowledged");
            helper.assertTrue(status.worldRevision()>=2 &&
                status.simTick()>0 && status.committed()<=status.evaluated(),
                "J03 WORLD CAS/clock did not advance");
            helper.assertTrue(!status.gameplayActive() &&
                !life.snapshot(server).admitsEvents() &&
                life.snapshot(server).missingPortCount()==9,
                "test-only J03 scheduler granted physical admission");
            System.out.println("DIRECTOR_NATIVE_J03_AUTONOMOUS_ACTOR_WAL_AUDIT_PASS");
        });
    }

    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
    public static void unqualifiedJ02CannotBecomeAutomaticPhysicalEvent(GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        helper.succeedWhen(()->{
            var life=DirectorStandaloneMod.directorLifecycle();
            var schedule=life.decisionSchedulerStatus(server);
            helper.assertTrue(schedule.isPresent(),"J03 server scheduler unavailable");
            helper.assertTrue(schedule.orElseThrow().phase()!=
                DirectorNativeDecisionScheduler.Phase.FAILED_SAFE,
                "J03 scheduler error");
            helper.assertTrue(life.candidateCatalogView(server).isPresent() &&
                !life.candidateCatalogView(server).orElseThrow().validatedForProduction(),
                "J02 candidate inadvertently qualified");
            helper.assertTrue(life.recoveredWorld(server).orElseThrow()
                .runControl()==fr.proutlost.director.api.model.RunControl.PAUSED &&
                !life.snapshot(server).admitsEvents(),
                "unqualified auto-scheduler bypassed PAUSED");
            System.out.println("DIRECTOR_NATIVE_J03_NO_UNQUALIFIED_EVENT_PASS");
        });
    }

    /** Prove J03 never turns an oversized or cold J04 projection into
     * "there are no regions". This deliberately creates DTOs in the
     * isolated GameTest, never writes them to J04 or the Minecraft world. */
    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",
              timeoutTicks=12000)
    public static void regionalPlanningBridgeDefersOversizeAndRejectsForeignWorld(
            GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null&&server.isSameThread(),"server actor");
        helper.succeedWhen(()->{
            var life=DirectorStandaloneMod.directorLifecycle();
            var live=life.observationSnapshot(server);
            var durable=life.labMemoryView(server);
            helper.assertTrue(live.isPresent()&&durable.isPresent(),
                "J01/J04 not ready");
            var configuration=DirectorLabObservationProfile.load();
            var bridge=new DirectorNativeRegionalPlanningBridge(configuration);
            // Pure fixture exercises the explicit mode boundary; never
            // registered as an operator-approved production provider.
            var approvedFixture=new ConfigView(configuration.revision(),configuration.provenanceHash(),
                Calibration.APPROVED,configuration.values());
            boolean mismatchRejected=false;
            try{new DirectorNativeRegionalPlanningBridge(configuration,ProviderMode.PRODUCTION);}
            catch(IllegalArgumentException expected){mismatchRejected=true;}
            helper.assertTrue(mismatchRejected,"production regional projection accepted TEST calibration");
            mismatchRejected=false;
            try{new DirectorNativeRegionalPlanningBridge(approvedFixture);}
            catch(IllegalArgumentException expected){mismatchRejected=true;}
            helper.assertTrue(mismatchRejected,"legacy regional projection accepted APPROVED calibration");
            var productionBridge=new DirectorNativeRegionalPlanningBridge(approvedFixture,ProviderMode.PRODUCTION);
            // Pure mode-boundary check only: never install this fixture as a
            // production provider or use its relief proxy to admit a danger.
            var emptyCatalog=new CatalogSnapshot(new Epoch(0),BlobContent.hash(new byte[0]),Map.of(),Map.of(),Map.of());
            var zeroBudget=new BudgetView(BudgetMath.zero(),BudgetMath.zero(),Map.of());
            var state=new DecisionState(life.recoveredWorld(server).orElseThrow(),zeroBudget,new OwnershipView(Map.of()),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
            var riskInput=new DecisionInput(live.orElseThrow(),durable.orElseThrow(),emptyCatalog,approvedFixture,List.of(),List.of(),0,state,Optional.empty());
            boolean riskMismatch=false;
            try{fr.proutlost.director.bootstrap.NativeLandslideRiskObservation.enrich(server,riskInput);}
            catch(IllegalArgumentException expected){riskMismatch=true;}
            helper.assertTrue(riskMismatch,"legacy relief sensor accepted APPROVED fixture");
            var relief=fr.proutlost.director.bootstrap.NativeLandslideRiskObservation.enrich(server,riskInput,ProviderMode.PRODUCTION);
            for(var region:relief.context().context().regions().values())if(region.risks().containsKey(new ResourceId("proutlost:landslide")))
                helper.assertTrue(fr.proutlost.director.bootstrap.NativeLandslideRiskObservation.capturedFactors(relief,region.region().regionId()).isPresent(),"production risk lacked exact captured normalized factors");
            approvedRiskUsesActualNativeSurface(helper,riskInput);
            var testRiskInput=new DecisionInput(riskInput.context(),riskInput.memory(),riskInput.catalog(),configuration,List.of(),List.of(),0,state,Optional.empty());
            riskMismatch=false;
            try{fr.proutlost.director.bootstrap.NativeLandslideRiskObservation.enrich(server,testRiskInput,ProviderMode.PRODUCTION);}
            catch(IllegalArgumentException expected){riskMismatch=true;}
            helper.assertTrue(riskMismatch,"production relief sensor accepted TEST calibration");

            var actual=durable.orElseThrow();
            var all=new HashMap<RegionId,RegionRecord>();
            var dim=new DimensionId("minecraft:overworld");
            for(int n=0;n<65;n++){
                var id=new RegionId(UUID.nameUUIDFromBytes(
                    ("J03_REGIONAL_OVERFLOW_"+n).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                all.put(id,new RegionRecord(
                    new AggregateHeader(1,id.value(),new Revision(1),
                        new Sequence(0),new Tick(200)),
                    id,dim,RegionStatus.ACTIVE,
                    new SupportShape(List.of(),List.of(),List.of()),Set.of(),
                    new UnitValue(.3),Set.of(),Set.of(),new Revision(1),
                    new RegionPersonality(Map.of()),Set.of()));
            }
            var excessive=new MemoryView(actual.scopes(),actual.revisions(),
                actual.regions(),actual.operationalPlayers(),
                actual.significantPlayers(),all,actual.anchors(),actual.locations());
            var out=bridge.project(live.orElseThrow(),excessive,123);
            var productionOut=productionBridge.project(live.orElseThrow(),excessive,123);
            helper.assertTrue(productionOut.equals(out),
                "production regional projection bypassed the same bounded J04 gate");
            helper.assertTrue(out.deferred()&&out.regionCount()==0 &&
                out.campCount()==0 && out.j04Sequence()==123 &&
                out.snapshot().context().regions().isEmpty() &&
                out.snapshot().missingData().contains(SensorKind.REGION_TAGS) &&
                out.snapshot().missingData().contains(SensorKind.CAMP_ACTIVITY),
                "oversized region view silently became a known-empty census");
            var empty=new MemoryView(actual.scopes(),actual.revisions(),
                List.of(),actual.operationalPlayers(),
                actual.significantPlayers(),Map.of(),Map.of(),Map.of());
            var cold=bridge.project(live.orElseThrow(),empty,123);
            helper.assertTrue(productionBridge.project(live.orElseThrow(),empty,123).equals(cold),
                "production regional projection fabricated cold-region membership");
            helper.assertTrue(!cold.deferred()&&cold.regionCount()==0 &&
                cold.snapshot().context().regions().isEmpty(),
                "cold J04 without region records should not invent one");
            var first=all.entrySet().iterator().next();
            var one=Map.of(first.getKey(),first.getValue());
            var missingMemory=new MemoryView(actual.scopes(),actual.revisions(),
                List.of(),actual.operationalPlayers(),
                actual.significantPlayers(),one,Map.of(),Map.of());
            var unavailable=bridge.project(live.orElseThrow(),missingMemory,124);
            helper.assertTrue(productionBridge.project(live.orElseThrow(),missingMemory,124).equals(unavailable),
                "production regional projection weakened missing-memory gate");
            helper.assertTrue(unavailable.deferred() &&
                unavailable.snapshot().context().regions().isEmpty() &&
                unavailable.snapshot().missingData().contains(SensorKind.REGION_TRAITS),
                "incomplete J04 region projection falsely became empty evidence");
            var old=live.orElseThrow();
            var fakeWorld=new WorldId(UUID.randomUUID());
            var foreign=new SnapshotBatch(old.requestId(),
                new ScopeSet(fakeWorld,Set.of()),old.capturedTick(),
                old.scopeRevisions(),old.context(),old.missingData(),
                old.loadedChunks(),old.serverHealth());
            boolean rejected=false;
            try{bridge.project(foreign,actual,123);}
            catch(IllegalArgumentException expected){rejected=true;}
            mismatchRejected=false;
            try{productionBridge.project(foreign,actual,123);}
            catch(IllegalArgumentException expected){mismatchRejected=true;}
            helper.assertTrue(mismatchRejected,"production regional projection accepted a foreign world");
            helper.assertTrue(rejected && !life.snapshot(server).admitsEvents(),
                "foreign world or insufficient regional evidence granted rights");
            System.out.println("DIRECTOR_NATIVE_J03_REGION_CONTEXT_OVERFLOW_FAIL_CLOSED_PASS");
        });
    }
    /** Real native block reads plus explicit DTO fixture test the producer only.
     * No approval, J04 observation, event admission or human perception is claimed. */
    private static void approvedRiskUsesActualNativeSurface(GameTestHelper helper,DecisionInput base){
        var level=helper.getLevel();var server=level.getServer();var block=helper.absolutePos(new net.minecraft.core.BlockPos(1,2,1));
        var chunk=level.getChunkSource().getChunkNow(block.getX()>>4,block.getZ()>>4);
        helper.assertTrue(chunk!=null,"test terrain must already be loaded");
        int top=chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,block.getX()&15,block.getZ()&15);
        var wet=new net.minecraft.core.BlockPos(block.getX(),top+1,block.getZ());var before=level.getBlockState(wet);
        level.setBlock(wet,net.minecraft.world.level.block.Blocks.STONE_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED,true),2);
        try{
            var id=new RegionId(UUID.randomUUID());var dimension=new DimensionId(level.dimension().location().toString());var tick=base.context().capturedTick();
            var header=new AggregateHeader(1,id.value(),new Revision(1),new Sequence(0),tick);
            var region=new RegionRecord(header,id,dimension,RegionStatus.ACTIVE,new SupportShape(List.of(new Aabb(dimension,block.getX(),level.getMinBuildHeight(),block.getZ(),block.getX()+3,level.getMaxBuildHeight(),block.getZ()+3)),List.of(),List.of()),Set.of(),new UnitValue(0),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of());
            var memory=new RegionMemory(header,id,Map.of(MemoryComponent.ACTIVITY,new DecayValue(1,tick,"memory.activity_half_life_ticks",1,new UnitValue(1))),tick,new Tick(0),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of()));
            var ref=new ScopeRef(ScopeKind.REGION,id.value());var scopeRefs=new HashSet<>(base.context().scopes().scopes());scopeRefs.add(ref);var scopeSet=new ScopeSet(base.context().scopes().worldId(),scopeRefs);
            var revisions=new HashMap<>(base.context().scopeRevisions());revisions.put(ref,new Revision(1));var scopes=new HashMap<>(base.context().context().scopes());scopes.put(ref,new ScopeSnapshot(ref,new Revision(1),Set.of(),Set.of(SensorKind.RISK,SensorKind.TERRAIN),Map.of()));
            var c=base.context().context();var snapshot=new SnapshotBatch(base.context().requestId(),scopeSet,tick,revisions,new ObservationContext(scopes,c.players(),c.groups(),Map.of(id,new RegionSnapshot(region,Set.of(),Set.of(),List.of(),Map.of())),Map.of(),Map.of(),List.of(),c.capabilities()),Set.of(SensorKind.RISK,SensorKind.TERRAIN),base.context().loadedChunks(),base.context().serverHealth());
            var memories=fr.proutlost.director.memory.MemoryProjection.of(scopeSet,List.of(new RegionAggregate(region),new RegionMemoryAggregate(memory)));
            var input=new DecisionInput(snapshot,memories,base.catalog(),base.config(),List.of(),List.of(),0,base.state(),Optional.empty());
            var result=fr.proutlost.director.bootstrap.NativeLandslideRiskObservation.enrich(server,input,ProviderMode.PRODUCTION);var scores=result.context().context().regions().get(id).risks();var measured=fr.proutlost.director.bootstrap.NativeLandslideRiskObservation.capturedFactors(result,id).orElseThrow();
            var risk=new ResourceId("proutlost:landslide");helper.assertTrue(scores.containsKey(risk),"known native surface and activity did not produce the weighted risk");
            helper.assertTrue(measured.get(new ResourceId("proutlost_director:landslide/saturation")).value()>0,"actual waterlogged native surface did not contribute saturation");
            helper.assertTrue(Math.abs(measured.get(new ResourceId("proutlost_director:landslide/recent_activity")).value()-.5)<1e-12,"observed J04 activity fixture did not use v/(1+v)");
            double weighted=0;String[] factors={"rain","saturation","slope","recent_activity"};String[] keys={"rain","saturation","slope","activity"};
            for(int n=0;n<4;n++)weighted+=measured.get(new ResourceId("proutlost_director:landslide/"+factors[n])).value()*base.config().values().get("risk.landslide."+keys[n]+"_weight").value().doubleValue();
            helper.assertTrue(Math.abs(scores.get(risk).value()-Math.clamp(weighted,0,1))<1e-12,"approved risk weights were not consumed");
            helper.assertTrue(scores.keySet().equals(Set.of(risk)),"factor values leaked into aggregate pacing risk scores");
            helper.assertTrue(fr.proutlost.director.engine.ConditionEvaluator.knownRegionalRisk(id,risk,result),"exact regional risk availability was not published");
            var noActivity=new RegionMemory(header,id,Map.of(),tick,new Tick(0),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of()));
            var unknownMemory=fr.proutlost.director.memory.MemoryProjection.of(scopeSet,List.of(new RegionAggregate(region),new RegionMemoryAggregate(noActivity)));
            var unknown=fr.proutlost.director.bootstrap.NativeLandslideRiskObservation.enrich(server,new DecisionInput(snapshot,unknownMemory,base.catalog(),base.config(),List.of(),List.of(),0,base.state(),Optional.empty()),ProviderMode.PRODUCTION);
            helper.assertTrue(!unknown.context().context().regions().get(id).risks().containsKey(risk)&&unknown.context().missingData().contains(SensorKind.RISK),"absent activity became a fake known zero");
            var cave=new RegionRecord(header,id,dimension,RegionStatus.ACTIVE,new SupportShape(List.of(new Aabb(dimension,block.getX(),level.getMinBuildHeight(),block.getZ(),block.getX()+3,Math.max(level.getMinBuildHeight()+1,wet.getY()),block.getZ()+3)),List.of(),List.of()),Set.of(),new UnitValue(0),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of());
            var caveFrame=new SnapshotBatch(snapshot.requestId(),scopeSet,tick,revisions,new ObservationContext(scopes,c.players(),c.groups(),Map.of(id,new RegionSnapshot(cave,Set.of(),Set.of(),List.of(),Map.of())),Map.of(),Map.of(),List.of(),c.capabilities()),snapshot.missingData(),snapshot.loadedChunks(),snapshot.serverHealth());
            var caveMemory=fr.proutlost.director.memory.MemoryProjection.of(scopeSet,List.of(new RegionAggregate(cave),new RegionMemoryAggregate(memory)));
            var caveResult=fr.proutlost.director.bootstrap.NativeLandslideRiskObservation.enrich(server,new DecisionInput(caveFrame,caveMemory,base.catalog(),base.config(),List.of(),List.of(),0,base.state(),Optional.empty()),ProviderMode.PRODUCTION);
            helper.assertTrue(!caveResult.context().context().regions().get(id).risks().containsKey(risk)&&caveResult.context().context().scopes().get(ref).availableSensors().contains(SensorKind.TERRAIN),"surface outside actual cave bounds supplied regional risk or erased observed terrain");
            level.setBlock(wet,net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(),2);
            var opaque=fr.proutlost.director.bootstrap.NativeLandslideRiskObservation.enrich(server,input,ProviderMode.PRODUCTION);
            helper.assertTrue(!opaque.context().context().regions().get(id).risks().containsKey(risk),"opaque BlockEntity supplied invented saturation");
            helper.assertTrue(opaque.context().context().scopes().get(ref).availableSensors().contains(SensorKind.TERRAIN)&&!opaque.context().context().scopes().get(ref).availableSensors().contains(SensorKind.RISK),"unknown wetness erased observable relief or granted risk");
            System.out.println("DIRECTOR_NATIVE_APPROVED_RISK_SURFACE_AND_UNKNOWN_ACTIVITY_PASS");
        }finally{level.setBlock(wet,before,2);}
    }

}
