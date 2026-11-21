package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.PlanningPort;
import fr.proutlost.director.engine.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

/**
 * Select the ACTUAL bundled J02 safe_micro event with the real J03
 * CandidateGenerator/eligibility/scoring/weighted selection and a bounded
 * TEST_ONLY planning input. No client sound packet or gameplay permission.
 * Only synthetic player eligibility belongs to the fixture, never production.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeJ02J03SelectedSafeMicroGameTests {
 private DirectorNativeJ02J03SelectedSafeMicroGameTests(){}
 public record TestScene(DecisionInput input,DecisionProposal proposal,
                         CatalogSnapshot catalog) {}
 static TestScene select(GameTestHelper helper){
  var server=helper.getLevel().getServer();
  helper.assertTrue(server!=null&&server.isSameThread(),"actor");
  var config=DirectorLabObservationProfile.load();
  var catalog=new DirectorLabSafeMicroCatalog(config).snapshot();
  var worldId=new WorldId(server.overworld().getDataStorage().computeIfAbsent(
    fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity.FACTORY,
    fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity.KEY).worldId().value());
  var playerId=new PlayerId(UUID.nameUUIDFromBytes("J02J03_SAFE_MICRO_TEST_PLAYER".getBytes()));
  var dimension=new DimensionId("minecraft:overworld");
  var now=new Tick(20000);
  var capId=new CapabilityId("perception.sound_v1");
  var worldScope=new ScopeRef(ScopeKind.WORLD,worldId.value());
  var playerScope=new ScopeRef(ScopeKind.PLAYER,playerId.value());
  var scopes=new ScopeSet(worldId,Set.of(worldScope,playerScope));
  var versions=Map.of(worldScope,new Revision(1),playerScope,new Revision(1));
  var position=new BlockPosition(dimension,0,65,0);
  var player=new PlayerSnapshot(playerId,new Revision(1),now,
    PlayerEligibility.ELIGIBLE,new PositionSnapshot(position,now),
    Set.of(),Set.of(),new DurationTicks(20000),Set.of(),
    new ClientCapabilitySnapshot(1,Set.of(new ResourceId("proutlost:sound")),
      Map.of(),true),Set.of());
  var scopeRows=new HashMap<ScopeRef,ScopeSnapshot>();
  for(var scope:scopes.scopes())
    scopeRows.put(scope,new ScopeSnapshot(scope,new Revision(1),
      EnumSet.allOf(SensorKind.class),Set.of(),Map.of()));
  var capability=new CapabilityDescriptor(capId,1,ProviderMode.TEST_ONLY,
    CapabilityStatus.AVAILABLE,new ResourceId("proutlost:lab_sound"),
    Set.of("test_only"));
  var context=new ObservationContext(scopeRows,Map.of(playerId,player),Map.of(),
    Map.of(),Map.of(),Map.of(),List.of(),Map.of(capId,capability));
  var loaded=new ChunkRef(dimension,0,0);
  var sample=new SnapshotBatch(new RequestId(UUID.randomUUID()),
    scopes,now,versions,context,Set.of(),Set.of(loaded),HealthMode.NORMAL);
  var hdr=new AggregateHeader(1,playerId.value(),new Revision(1),
    new Sequence(0),now);
  var memoryRow=new PlayerOperationalMemory(hdr,playerId,
    new DurationTicks(20000),Optional.empty(),Optional.empty(),List.of(),
    List.of(),Set.of(),new UnitValue(.5),Map.of(),Optional.empty(),new Epoch(0));
  var remembered=new MemoryView(scopes,versions,List.of(),
    List.of(memoryRow),List.of(),Map.of(),Map.of(),Map.of());
  var control=new EngineControlState(1,new Revision(0),now,now,
    new UnitValue(0),now,Optional.empty(),new UnitValue(.5));
  var world=new DirectorWorldState(new AggregateHeader(1,worldId.value(),
      new Revision(1),new Sequence(0),now),worldId,new Epoch(1),now,
      RunControl.RUNNING,HealthMode.NORMAL,PacingState.REST,GlobalTension.CALM,
      new ResourceId("proutlost:test"),catalog.hash(),config.provenanceHash(),
      new Epoch(1),new Sequence(0),control);
  var budget=new BudgetView(new BudgetVector(10,24,8,100,100,100,
    1000,100,1000,100),BudgetMath.zero(),Map.of());
  var state=new DecisionState(world,budget,new OwnershipView(Map.of()),
    List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
  var input=new DecisionInput(sample,remembered,catalog,config,
    List.of(),List.of(),42L,state,Optional.empty());
  var generated=new CandidateGenerator().generate(input,new DecisionRandom(input.seed()));
  helper.assertTrue(generated.candidates().size()==1,"J02 safe_micro generation must return one target");
  var candidate=generated.candidates().getFirst();
  helper.assertTrue(candidate.definition().id().value()
    .equals("proutlost_director:candidate/safe_micro"),
    "J03 generated wrong J02 candidate");
  var step=candidate.definition().steps().getFirst();
  var footprint=new Footprint(dimension,Set.of(loaded),Set.of(),List.of());
  var bindings=new PlanningBindings(candidate.id(),candidate.reference(),
    new EventInstanceId(UUID.randomUUID()),new OperationToken(UUID.randomUUID()),
    step.id(),candidate.roles(),candidate.scopes(),footprint,Set.of(playerId),true);
  var planning=new PlanningSnapshot(sample,state.ownership(),budget,
    new BlobBundle(List.of()),Optional.of(bindings));
  var blob=BlobContent.capture(new ResourceId("proutlost:lab_plan"),1,new byte[]{1});
  PlanningPort port=request->{
   var bound=request.snapshot().bindings().orElseThrow();
   return new PlannedPrimitive(bound,new PreparedEffect(
     new EffectIntent(bound.operationToken(),request.step().primitive(),1,
       request.step().args(),bound.footprint(),bound.instanceId()),
     request.snapshot().snapshot(),List.of(),
     new ReadSet(input.context().scopeRevisions(),Map.of(),Map.of()),
     Map.of(),BudgetMath.zero(),List.of(),Set.of(capId),
     new EffectBlobs(Set.of(),blob.reference(),blob.reference(),Map.of(),Map.of())));
  };
  var director=new WorldDirector(ProviderMode.TEST_ONLY,
    new CandidatePlanner(port,Map.of(candidate.id(),List.of(planning))));
  var proposal=director.evaluate(input);
  helper.assertTrue(proposal.candidates().size()==1,
    "real J03 did not consider bundled J02 event");
  helper.assertTrue(proposal.selectedPlan().isPresent(),
    "real J03 did not select safe_micro despite a verified test-only target");
  helper.assertTrue(proposal.selectedPlan().orElseThrow().effects().size()==1,
    "J03 selected plan lost one sound primitive");
  helper.assertTrue(director.replay(input,proposal.audit()).equals(proposal),
    "real selected J02 event did not replay deterministically");
  helper.assertTrue(!fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().snapshot(server)
      .admitsEvents(),"TEST_ONLY J02/J03 illegally started Minecraft gameplay");
  System.out.println("DIRECTOR_NATIVE_J02_REAL_PACKAGED_J03_WEIGHTED_SELECTED_PLAN_PASS");
  return new TestScene(input,proposal,catalog);
 }
 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
 public static void bundledJ02SafeMicroSelectedByRealJ03Engine(GameTestHelper helper){
  select(helper);
  helper.succeed();
 }
}
