package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.BlockTransformBinding;
import fr.proutlost.director.adapter.neoforge.server.effects.ServerEffectPort;
import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.engine.*;
import fr.proutlost.director.api.port.PlanningPort;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;

/**
 * Native J05 EventRunner with a REAL one-cell physical transform:
 * ownership -> logical reservation -> J04 PREPARE fsync -> J05 permit
 * -> Minecraft block write -> J04 RESULT fsync -> store restart/readback.
 *
 * Unlike the earlier explicit J05 fixture, this GameTest selects the REAL
 * bundled J02 camp_regrowth via WorldDirector J03 with synthetic TEST_ONLY
 * player/region evidence, commits the real J03 audit to J04 before J05,
 * and applies the selected primitive to one actual GameTest block.
 * NOT a production event: the player, camp and calibration are test-only.
 * The inverse case also executes the actual NativeGameplay selected-snapshot
 * branch with an absent live owner, then genuine owner/START ACK and PREPARE.
 * That seam is not a live player beginSelected or J01/J06 qualification.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeSelectedCampRegrowthGameTests {
 private DirectorNativeSelectedCampRegrowthGameTests(){}
 private static final StoreOptions OPTIONS=new StoreOptions(
   1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);

 private enum FinishMode { FIXTURE_RESET, DIRECTOR_INVERSE, PLAYER_YIELD }
 private static final class Probe {
  final FinishMode finishMode;
  final boolean selectedOwnerRegression;
  final DecisionInput fixtureInput;
  final OwnershipRecord unrelated;
  DurableBatch pendingOwnerStart;CompletableFuture<CommitAck> pendingOwnerStartAck;Sequence selectedAuditCut;
  String selectedCaptureFailure;
  final ServerLevel level;
  final BlockPos block;
  final BlockState original, desired;
  final WorldId world;
  final ResourceKey resource;
  final BlockPosition position;
  final EffectIntent intent;
  final DecisionProposal selection;
  final DirectorWorldState firstWorld;
  final RegionRecord firstRegion;
  final PlayerOperationalMemory firstPlayerMemory;
  final Footprint footprint;
  EffectPreparation captured;
  PreparedEffect prepared;
  PreparedSlice slice;
  final AtomicReference<OwnershipView> ledger;
  final BlockTransformBinding binding;
  final java.nio.file.Path root;
  final CompletableFuture<JournalStore> opening;
  JournalStore store;
  CommitPermit.Authority authority;
  ResourceArbiter arbiter;
  EventRunner runner;
  ServerEffectPort effectPort;
  ReservationToken token;
  PrepareRecord prepare;
  LiveValidationSnapshot live;
  CompletableFuture<CommitAck> ownerAck;
  CompletableFuture<CommitAck> yieldedAck;
  CompletableFuture<CommitAck> releasedAck;
  EventRunner inverseRunner;
  TransactionId inverseId;
  LiveValidationSnapshot inverseLive;
  OwnershipRecord releaseAfterAck;
  DirectorDecisionJournal decisionJournal;
  DecisionId decisionId;
  CompletableFuture<Void> closing;
  CompletableFuture<JournalStore> reopening;
  int phase;

  Probe(GameTestHelper helper){this(helper,FinishMode.FIXTURE_RESET,false);}
  Probe(GameTestHelper helper,FinishMode finishMode){this(helper,finishMode,false);}
  Probe(GameTestHelper helper,FinishMode finishMode,boolean selectedOwnerRegression){
   this.finishMode=finishMode;this.selectedOwnerRegression=selectedOwnerRegression;
   level=helper.getLevel();
   var server=level.getServer();
   if(server==null||!server.isSameThread())
    throw new IllegalStateException("native actor");
   block=helper.absolutePos(new BlockPos(1,2,1));
   original=level.getBlockState(block);
   desired=original.is(Blocks.MOSS_BLOCK)?
     Blocks.STONE.defaultBlockState():Blocks.MOSS_BLOCK.defaultBlockState();
   var dimension=new DimensionId(level.dimension().location().toString());
   position=new BlockPosition(dimension,block.getX(),block.getY(),block.getZ());
   resource=new ResourceKey(OwnershipDomain.BLOCK,dimension,
     block.getX()+","+block.getY()+","+block.getZ());
   var preBlob=BlockTransformBinding.stateBlob(original);
   var owner=new OwnershipRecord(new AggregateHeader(1,
     UUID.randomUUID(),new Revision(1),new Sequence(0),new Tick(1)),
     resource,Set.of(new OwnerToken(UUID.randomUUID())),new Epoch(1),
     OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),
     preBlob.reference().hash(),new Epoch(0),Optional.empty());
   ledger=new AtomicReference<>(new OwnershipView(Map.of(resource,owner)));
   var otherBlock=block.east();var otherResource=new ResourceKey(OwnershipDomain.BLOCK,dimension,
     otherBlock.getX()+","+otherBlock.getY()+","+otherBlock.getZ());
   unrelated=new OwnershipRecord(new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(0),new Tick(1)),
     otherResource,Set.of(new OwnerToken(UUID.randomUUID())),new Epoch(7),OwnershipDisposition.DIRECTOR_ACTIVE,
     new MutationId(UUID.randomUUID()),BlockTransformBinding.stateBlob(level.getBlockState(otherBlock)).reference().hash(),
     new Epoch(0),Optional.empty());
   footprint=new Footprint(dimension,
     Set.of(new ChunkRef(dimension,block.getX()>>4,block.getZ()>>4)),
     Set.of(position),List.of());
   var terrain=new TerrainCellSnapshot(position,preBlob.reference().hash(),
     BlockTransformBinding.STATE_CODEC,Set.of(),Set.of(),false,true,
     new Epoch(1),new Epoch(0));
   var area=new TerrainSnapshot(footprint,Map.of(position,terrain),
     List.of(),Map.of(),new Revision(1));
   world=server.overworld().getDataStorage().computeIfAbsent(
     DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY).worldId();
   // TEST_ONLY observed camp/visitor fixtures; physical block is real Minecraft.
   var tick=new Tick(20000);
   var regionId=new RegionId(UUID.randomUUID());
   var playerId=new PlayerId(UUID.randomUUID());
   var anchorId=new AnchorId(UUID.randomUUID());
   var worldScope=new ScopeRef(ScopeKind.WORLD,world.value());
   var regionScope=new ScopeRef(ScopeKind.REGION,regionId.value());
   var playerScope=new ScopeRef(ScopeKind.PLAYER,playerId.value());
   var scopes=new ScopeSet(world,Set.of(worldScope,regionScope,playerScope));
   var versions=Map.of(worldScope,new Revision(1),
                       regionScope,new Revision(1),playerScope,new Revision(1));
   Map<ScopeRef,ScopeSnapshot> scopeRows=new HashMap<>();
   for(var scope:scopes.scopes())
    scopeRows.put(scope,new ScopeSnapshot(scope,new Revision(1),
      EnumSet.allOf(SensorKind.class),Set.of(),Map.of()));
   var player=new PlayerSnapshot(playerId,new Revision(1),tick,
     PlayerEligibility.ELIGIBLE,new PositionSnapshot(position,tick),
     Set.of(regionId),Set.of(),new DurationTicks(20000),Set.of(),
     new ClientCapabilitySnapshot(1,Set.of(),Map.of(),true),Set.of());
   firstRegion=new RegionRecord(
     new AggregateHeader(1,regionId.value(),new Revision(1),new Sequence(0),tick),
     regionId,dimension,RegionStatus.ACTIVE,
     new SupportShape(List.of(),List.of(),List.of()),Set.of(anchorId),
     new UnitValue(.5),Set.of(),Set.of(),new Revision(1),
     new RegionPersonality(Map.of()),Set.of());
   var camp=new CampSnapshot(anchorId,10,new DurationTicks(20000),
     Map.of(),Map.of(),1,new UnitValue(1));
   var region=new RegionSnapshot(firstRegion,
     Set.of(new TagId("proutlost:abandoned_camp")),Set.of(playerId),
     List.of(camp),Map.of());
   var capabilityId=new CapabilityId("mutation.plants_v1");
   var capability=new CapabilityDescriptor(capabilityId,1,ProviderMode.TEST_ONLY,
     CapabilityStatus.AVAILABLE,new ResourceId("proutlost:lab_mutations"),
     Set.of("test_only"));
   var observed=new ObservationContext(scopeRows,Map.of(playerId,player),
     Map.of(),Map.of(regionId,region),Map.of(),Map.of(),List.of(area),
     Map.of(capabilityId,capability));
   var sample=new SnapshotBatch(new RequestId(UUID.randomUUID()),scopes,
     tick,versions,observed,Set.of(),footprint.loadedChunks(),HealthMode.NORMAL);
   var regionMemory=new RegionMemory(
     new AggregateHeader(1,regionId.value(),new Revision(1),new Sequence(0),tick),
     regionId,Map.of(),tick,tick,
     Set.of(new ResourceId("proutlost:camp_abandoned")),Set.of(),
     new RegionActivityState(Map.of(),Map.of()));
   firstPlayerMemory=new PlayerOperationalMemory(
     new AggregateHeader(1,playerId.value(),new Revision(1),new Sequence(0),tick),
     playerId,new DurationTicks(20000),Optional.of(regionId),Optional.empty(),
     List.of(),List.of(),Set.of(),new UnitValue(.5),Map.of(),
     Optional.empty(),new Epoch(0));
   var memory=new MemoryView(scopes,versions,List.of(regionMemory),
     List.of(firstPlayerMemory),List.of(),Map.of(regionId,firstRegion),
     Map.of(),Map.of());
   var config=DirectorLabObservationProfile.load();
   var catalog=new DirectorLabCampRegrowthCatalog(config).snapshot();
   var control=new EngineControlState(1,new Revision(0),tick,tick,
     new UnitValue(0),tick,Optional.empty(),new UnitValue(.5));
   firstWorld=new DirectorWorldState(
     new AggregateHeader(1,world.value(),new Revision(1),new Sequence(0),tick),
     world,new Epoch(1),tick,RunControl.RUNNING,HealthMode.NORMAL,
     PacingState.REST,GlobalTension.CALM,
     new ResourceId("proutlost:lab_growth"),catalog.hash(),
     config.provenanceHash(),new Epoch(1),new Sequence(0),control);
   var budget=new BudgetView(
     new BudgetVector(100,100,100,100,100,100,100,100,100,100),
     BudgetMath.zero(),Map.of(regionScope,BudgetMath.zero()));
   var state=new DecisionState(firstWorld,budget,ledger.get(),
     List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
   var input=new DecisionInput(sample,memory,catalog,config,
     List.of(),List.of(),42L,state,Optional.empty());
   fixtureInput=input;
   var generated=new CandidateGenerator().generate(input,
     new DecisionRandom(input.seed())).candidates();
   if(generated.size()!=1 ||
      !generated.getFirst().definition().id().value().equals(
        "proutlost_director:candidate/camp_regrowth"))
    throw new IllegalStateException("J03 did not generate actual camp event");
   var candidate=generated.getFirst();
   var step=candidate.definition().steps().getFirst();
   var bindings=new PlanningBindings(candidate.id(),candidate.reference(),
     new EventInstanceId(UUID.randomUUID()),new OperationToken(UUID.randomUUID()),
     step.id(),candidate.roles(),candidate.scopes(),footprint,Set.of(playerId),true);
   var planning=new PlanningSnapshot(sample,ledger.get(),budget,
     new BlobBundle(List.of()),Optional.of(bindings));
   var recipe=((CampGrowth)step.args()).recipe();
   binding=new BlockTransformBinding(dim->level,ledger::get,
     (p,next)->{
      if(!p.equals(position))throw new IllegalStateException("foreign cell");
      ledger.set(new OwnershipView(Map.of(resource,next)));
     },()->new Tick(20005),Map.of(recipe,new BlockTransformBinding.Recipe(
      Map.of(new ResourceId(BuiltInRegistries.BLOCK.getKey(original.getBlock()).toString()),
             new ResourceId(BuiltInRegistries.BLOCK.getKey(desired.getBlock()).toString())),
      true)),1);
   // Adversarial real-world preparation: an identity recipe must not reserve
   // a WAL permit for a physical mutation that will never change this cell.
   var sourceId=new ResourceId(BuiltInRegistries.BLOCK.getKey(original.getBlock()).toString());
   var noopBinding=new BlockTransformBinding(dim->level,ledger::get,
     (p,next)->{throw new IllegalStateException("noop ledger changed");},
     ()->new Tick(20005),Map.of(recipe,new BlockTransformBinding.Recipe(
       Map.of(sourceId,sourceId),true)),1);
   var noopIntent=new EffectIntent(new OperationToken(UUID.randomUUID()),
     step.primitive(),1,step.args(),footprint,bindings.instanceId());
   boolean noopRejected=false;
   try{noopBinding.capture(noopIntent,planning);}
   catch(IllegalArgumentException expected){
     noopRejected="no-op block replacement".equals(expected.getMessage());
   }
   if(!noopRejected)throw new IllegalStateException("J05 no-op block recipe admitted");
   System.out.println("DIRECTOR_NATIVE_J05_NOOP_RECIPE_REJECTED_PASS");
   intent=new EffectIntent(bindings.operationToken(),step.primitive(),1,
     step.args(),footprint,bindings.instanceId());
   captured=binding.capture(intent,planning);
   if(!captured.validation().valid()||captured.prepared().isEmpty())
    throw new IllegalStateException("selected physical J05 prepare unavailable");
   prepared=captured.prepared().orElseThrow();
   PlanningPort plannerPort=request->{
     var bindingInfo=request.snapshot().bindings().orElseThrow();
     var i=new EffectIntent(bindingInfo.operationToken(),
       request.step().primitive(),1,request.step().args(),
       bindingInfo.footprint(),bindingInfo.instanceId());
     var actual=binding.capture(i,request.snapshot());
     return new PlannedPrimitive(bindingInfo,actual.prepared().orElseThrow());
   };
   var planner=new CandidatePlanner(plannerPort,Map.of(candidate.id(),List.of(planning)));
   var directPlan=planner.plan(candidate,input);
   System.out.println("DIRECTOR_NATIVE_J03_CAMP_DIRECT_PLAN reasons="+
       directPlan.reasons()+" plan="+directPlan.plan().isPresent()+
       " bindings_match="+planning.bindings().orElseThrow().candidateId().equals(candidate.id()));
   var engine=new WorldDirector(ProviderMode.TEST_ONLY,planner);
   selection=engine.evaluate(input);
   System.out.println("DIRECTOR_NATIVE_J03_CAMP_SELECTION_DIAGNOSTIC chosen="+
     selection.selectedPlan().isPresent()+" candidates="+
     selection.candidates().stream().map(x->x.eligible()+":"+x.reasons()).toList()+
     " audit_chosen="+selection.audit().chosen());
   if(selection.selectedPlan().isEmpty() ||
      selection.selectedPlan().orElseThrow().effects().size()!=1 ||
      !selection.selectedPlan().orElseThrow().effects().getFirst().equals(intent) ||
      !engine.replay(input,selection.audit()).equals(selection))
    throw new IllegalStateException("J02 camp was not truly selected by J03");
   System.out.println("DIRECTOR_NATIVE_J02_CAMP_REGROWTH_J03_SELECTED_TEST_ONLY");
   slice=new PreparedSlice(new SliceId(UUID.randomUUID()),
     intent.operationToken(),footprint,prepared.preconditions(),
     List.of(intent),prepared.budgetEstimate());
   root=server.getWorldPath(LevelResource.ROOT)
     .resolve("director-eventrunner-test-only").resolve(UUID.randomUUID().toString());
   opening=JournalStore.open(root,world,OPTIONS).toCompletableFuture();
  }
  void advance(GameTestHelper helper){
   var server=level.getServer();
   if(!server.isSameThread())throw new IllegalStateException("actor");
   if(phase<2)helper.assertTrue(level.getBlockState(block).equals(original),
     "physical effect before durable PREPARE");
   switch(phase){
    case 0 -> {
     helper.assertTrue(opening.isDone()&&!opening.isCompletedExceptionally(),
       "J05 J04 dedicated writer startup");
     store=opening.getNow(null);
     ownerAck=store.append(ResultTransaction.batch(
       new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),
       Map.of(),List.of(new DurableOwnership(selectedOwnerRegression?unrelated:ledger.get().records().get(resource)),
         new DurableAggregate(new WorldAggregate(firstWorld)),
         new DurableAggregate(new RegionAggregate(firstRegion)),
         new DurableAggregate(new PlayerOperationalAggregate(firstPlayerMemory))),
       List.of())).toCompletableFuture();
     phase=1;
    }
    case 1 -> {
     helper.assertTrue(ownerAck.isDone() && !ownerAck.isCompletedExceptionally() &&
       ownerAck.getNow(null).ackStatus()==AckStatus.DURABLE,
       "J05 ownership not durable");
     if(selectedOwnerRegression){
      var absentQuery=new StoreQuery(world,Set.of(new OwnershipAggregate(ledger.get().records().get(resource)).key()),
        new Sequence(0),Optional.empty(),1);
      var absent=store.view(absentQuery);ReadContracts.validate(absent,absentQuery,store.session());
      helper.assertTrue(absent.status()==ReadStatus.AVAILABLE&&absent.page().orElseThrow().aggregates().isEmpty(),
        "fresh selected owner must really be absent from J04 before admission");
     }
     decisionJournal=new DirectorDecisionJournal(store,4);
     var receipt=decisionJournal.submit(selection);
     helper.assertTrue(!receipt.mayProceedToAdmission(),
       "J05 cannot reserve before J03 audit durability");
     decisionId=selection.proposalId();
     phase=11;
    }
    case 11 -> {
     var audited=decisionJournal.poll(decisionId);
     helper.assertTrue(audited.state()!=DirectorDecisionJournal.State.REJECTED &&
       audited.state()!=DirectorDecisionJournal.State.UNAVAILABLE,
       "selected camp J03 audit was rejected");
     if(audited.state()==DirectorDecisionJournal.State.WAITING)return;
     helper.assertTrue(audited.mayProceedToAdmission(),
       "J05 physical must await selected J03 audit fsync");
     var worldSnapshot=store.view(new StoreQuery(world,
       Set.of(new AggregateKey(AggregateKind.WORLD,world.value())),
       new Sequence(0),Optional.empty(),1));
     helper.assertTrue(worldSnapshot.status()==ReadStatus.AVAILABLE &&
       worldSnapshot.page().orElseThrow().aggregates().getFirst()
       .state().header().revision().value()==2,
       "J03 selected WORLD CAS not durable");
     // The J03 audit advances WORLD from rev1 to rev2. Do not reuse a
     // stale physical PREPARE: recapture the still-loaded LAB block and
     // every unchanged player/region scope against the verified J04 cut.
     var previous=prepared.snapshot();
     var context=previous.context();
     var worldScopeKey=new ScopeRef(ScopeKind.WORLD,world.value());
     var revisedScopes=new HashMap<ScopeRef,ScopeSnapshot>(context.scopes());
     var oldWorld=revisedScopes.get(worldScopeKey);
     revisedScopes.put(worldScopeKey,new ScopeSnapshot(worldScopeKey,
       new Revision(2),oldWorld.availableSensors(),
       oldWorld.missingSensors(),oldWorld.scoringFeatures()));
     var revisedVersions=new HashMap<ScopeRef,Revision>(previous.scopeRevisions());
     revisedVersions.put(worldScopeKey,new Revision(2));
     var revisedContext=new ObservationContext(revisedScopes,
       context.players(),context.groups(),context.regions(),
       context.anchors(),context.locations(),context.terrain(),
       context.capabilities());
     var updated=new SnapshotBatch(new RequestId(UUID.randomUUID()),
       previous.scopes(),new Tick(previous.capturedTick().value()+1),
       revisedVersions,revisedContext,previous.missingData(),
       previous.loadedChunks(),previous.serverHealth());
     var planning=new PlanningSnapshot(updated,ledger.get(),
       new BudgetView(new BudgetVector(100,100,100,100,100,100,100,100,100,100),
         BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));
     if(selectedOwnerRegression){
      var actualWorld=((WorldAggregate)worldSnapshot.page().orElseThrow().aggregates().getFirst().state()).value();
      planning=selectedAdmissionSnapshot(helper,updated,actualWorld);
      selectedAuditCut=worldSnapshot.stamp().orElseThrow().snapshotSequence();
     }
     try{captured=binding.capture(intent,planning);}
     catch(RuntimeException failed){
      if(!selectedOwnerRegression)throw failed;
      selectedCaptureFailure=failed.toString();throw new GameTestAssertException("selected admission capture failed: "+selectedCaptureFailure);
     }
     helper.assertTrue(captured.validation().valid() && captured.prepared().isPresent(),
       "selected block no longer safely captureable after audit");
     prepared=captured.prepared().orElseThrow();
     slice=new PreparedSlice(slice.sliceId(),intent.operationToken(),
       footprint,prepared.preconditions(),List.of(intent),prepared.budgetEstimate());
     System.out.println("DIRECTOR_NATIVE_J05_SELECTED_CAMP_REVALIDATED_AFTER_AUDIT");
     if(selectedOwnerRegression){
      var admitted=ledger.get().records().get(resource);
      var startFact=new DirectorFact(new FactId(UUID.randomUUID()),
        new FactType("proutlost_director:native_selected_growth_owner_start"),
        List.of(new ScopeRef(ScopeKind.WORLD,world.value())),Optional.of(intent.instanceId()),
        EvidenceStatus.CONFIRMED,Map.of("test_only",new JsonValue.Bool(true),
          "owner",new JsonValue.Text(admitted.header().id().toString())),true,Optional.empty());
      pendingOwnerStart=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),
        Map.of(),List.of(new DurableOwnership(admitted),new DurableFact(startFact)),List.of());
      pendingOwnerStartAck=store.append(pendingOwnerStart).toCompletableFuture();phase=12;return;
     }
     submitPhysical(helper);
    }
    case 12 -> {
     helper.assertTrue(level.getBlockState(block).equals(original),"selected admission/START must not mutate the physical PRE");
     if(!pendingOwnerStartAck.isDone())return;
     helper.assertTrue(!pendingOwnerStartAck.isCompletedExceptionally(),"actual selected owner START append failed");
     var ack=pendingOwnerStartAck.getNow(null);
     helper.assertTrue(ack!=null&&ack.ackStatus()==AckStatus.DURABLE&&ack.transactionId().equals(pendingOwnerStart.transactionId())
       &&ack.transactionHash().equals(pendingOwnerStart.transactionHash())&&ack.storeGeneration().equals(store.session().storeGeneration())
       &&ack.durableSequence().compareTo(selectedAuditCut)>0,"exact owner/START ACK must follow actual J03 audit");
     var admitted=ledger.get().records().get(resource);
     var query=new StoreQuery(world,Set.of(new OwnershipAggregate(admitted).key()),new Sequence(0),Optional.empty(),1);
     var read=store.view(query);ReadContracts.validate(read,query,store.session());
     helper.assertTrue(read.status()==ReadStatus.AVAILABLE&&read.page().orElseThrow().aggregates().size()==1
       &&read.page().orElseThrow().aggregates().getFirst().state().equals(new OwnershipAggregate(admitted))
       &&read.page().orElseThrow().aggregates().getFirst().committedSequence().equals(ack.durableSequence()),
       "pending selected owner must be exact in actual J04 before PREPARE");
     submitPhysical(helper);
    }
    case 2 -> {
     var result=runner.poll(prepare.transactionId(),live);
     if(result.stage()==EventRunner.Stage.REJECTED ||
        result.stage()==EventRunner.Stage.RECOVERY_REQUIRED)
       System.out.println("DIRECTOR_NATIVE_J05_CAMP_FAILURE stage="+result.stage()+
          " reason="+result.failure());
     helper.assertTrue(result.stage()!=EventRunner.Stage.REJECTED &&
       result.stage()!=EventRunner.Stage.RECOVERY_REQUIRED,
       "J05 runner rejected physical effect or durable result");
     if(result.stage()!=EventRunner.Stage.COMMITTED)return;
     helper.assertTrue(level.getBlockState(block).equals(desired),
       "J05 EventRunner did not mutate actual Minecraft block");
     helper.assertTrue(result.result().orElseThrow().outcome()==Outcome.SUCCEEDED,
       "J05 result did not confirm real block");
     // Recover the exact PHYSICAL POST against the captured PRE owner and the
     // actual local ownership revision, never a synthetic replacement state.
     var currentOwner=ledger.get().records().get(resource);
     var currentHash=BlockTransformBinding.stateBlob(level.getBlockState(block)).reference().hash();
     var recoveredProof=new RecoveryEvidence(resource,EvidenceStatus.CONFIRMED,
       Optional.of(currentHash),Optional.of(currentOwner.ownerTokens().iterator().next()),
       currentOwner.epoch(),currentOwner.playerOverrideEpoch(),false);
     helper.assertTrue(binding.reconcile(new RecoveryProbe(prepared,List.of(recoveredProof),
       captured.blobs())).status()==ReconcileStatus.SAME_POST,
       "J05 physical POST not recognized under correct owner revision and proof");
     var forgedHashProof=new RecoveryEvidence(resource,EvidenceStatus.CONFIRMED,
       Optional.of(new Fingerprint("f".repeat(64))),recoveredProof.ownerToken(),
       recoveredProof.ownerEpoch(),recoveredProof.playerOverrideEpoch(),false);
     helper.assertTrue(binding.reconcile(new RecoveryProbe(prepared,List.of(forgedHashProof),
       captured.blobs())).status()==ReconcileStatus.AMBIGUOUS,
       "J05 stale physical evidence must never become SAME_POST");
     var forgedOwnerProof=new RecoveryEvidence(resource,EvidenceStatus.CONFIRMED,
       Optional.of(currentHash),Optional.of(new OwnerToken(UUID.randomUUID())),
       recoveredProof.ownerEpoch(),recoveredProof.playerOverrideEpoch(),false);
     helper.assertTrue(binding.reconcile(new RecoveryProbe(prepared,List.of(forgedOwnerProof),
       captured.blobs())).status()==ReconcileStatus.AMBIGUOUS,
       "J05 proof of foreign owner must never become SAME_POST");
     for(var proofs:List.of(List.of(recoveredProof,forgedOwnerProof),
         List.of(forgedOwnerProof,recoveredProof))) {
       var contradictory=binding.reconcile(new RecoveryProbe(prepared,proofs,captured.blobs()));
       helper.assertTrue(contradictory.status()==ReconcileStatus.AMBIGUOUS
           && contradictory.proofs().size()==2,
           "J05 block reconciliation selected a favorable conflicting proof");
     }
     System.out.println("DIRECTOR_NATIVE_J05_BLOCK_RECOVERY_POST_PROOF_CAS_PASS");
     var receipt=store.view(new StoreQuery(world,Set.of(),new Sequence(0),
       Optional.empty(),32));
     helper.assertTrue(receipt.status()==ReadStatus.AVAILABLE &&
       receipt.page().orElseThrow().records().stream()
        .filter(r->r instanceof DurablePrepare).count()==1 &&
       receipt.page().orElseThrow().records().stream()
        .filter(r->r instanceof DurableResult).count()==1 &&
       receipt.page().orElseThrow().records().stream()
        .filter(r->r instanceof DurableAudit).count()==1,
       "J04 must persist exactly one PREPARE and RESULT");
     closing=store.closeAsync().toCompletableFuture();
     phase=3;
     System.out.println("DIRECTOR_NATIVE_J05_EVENTRUNNER_REAL_BLOCK_AND_DURABLE_RESULT_PASS");
     if(selectedOwnerRegression)System.out.println("DIRECTOR_NATIVE_SELECTED_ADMISSION_OWNER_OVERLAY_REAL_PREPARE_RESULT_PASS event="+
       intent.instanceId().value()+" scope=TEST_ONLY_DTO_NO_J01_J06 raw_owner_absent=true other_wal_owner_preserved=true");
    }
    case 3 -> {
     helper.assertTrue(closing.isDone() && !closing.isCompletedExceptionally(),
       "J04 writer close failed after physical result");
     reopening=JournalStore.open(root,world,OPTIONS).toCompletableFuture();
     phase=4;
    }
    case 4 -> {
     helper.assertTrue(reopening.isDone() && !reopening.isCompletedExceptionally(),
       "J05 J04 WAL reboot failed");
     store=reopening.getNow(null);
     var query=new StoreQuery(world,Set.of(),new Sequence(0),
       Optional.empty(),32);
     var read=store.view(query);
     helper.assertTrue(read.status()==ReadStatus.AVAILABLE &&
       read.page().orElseThrow().records().stream()
        .filter(r->r instanceof DurablePrepare).count()==1 &&
       read.page().orElseThrow().records().stream()
        .filter(r->r instanceof DurableResult).count()==1 &&
       read.page().orElseThrow().records().stream()
        .filter(r->r instanceof DurableAudit).count()==1,
       "J05 physical result or PREPARE missing after durable restart");
     helper.assertTrue(level.getBlockState(block).equals(desired),
       "J05 block unexpectedly reverted before safe cleanup");
     if(finishMode==FinishMode.DIRECTOR_INVERSE) {
       phase=20;
     } else if(finishMode==FinishMode.PLAYER_YIELD) {
       phase=30;
     } else {
       closing=store.closeAsync().toCompletableFuture();
       phase=5;
     }
    }
    case 5 -> {
     helper.assertTrue(closing.isDone() && !closing.isCompletedExceptionally(),
       "J05 recovered store writer did not close");
     // This restores only the disposable GameTest fixture. It does not
     // qualify the Director ownership-aware production cleanup saga.
     level.setBlock(block,original,
       Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS);
     helper.assertTrue(level.getBlockState(block).equals(original),
       "disposable fixture not reset");
     phase=6;
     System.out.println("DIRECTOR_NATIVE_J02_J03_J04_J05_SELECTED_CAMP_REAL_BLOCK_REBOOT_PASS");
    }
    case 6 -> {}
    case 20 -> startDirectorInverse(helper);
    case 21 -> finishDirectorInverse(helper);
    case 215 -> {
     helper.assertTrue(releasedAck!=null && releasedAck.isDone() &&
       !releasedAck.isCompletedExceptionally() &&
       releasedAck.getNow(null).ackStatus()==AckStatus.DURABLE,
       "J05 cleanup ownership RELEASED tombstone not durable");
     ledger.set(new OwnershipView(Map.of(resource,releaseAfterAck)));
     helper.assertTrue(arbiter.release(token),
       "J05 logical claim was not released after full cleanup");
     closing=store.closeAsync().toCompletableFuture();
     phase=22;
    }
    case 22 -> {
     helper.assertTrue(closing.isDone() && !closing.isCompletedExceptionally(),
       "J05 inverse WAL close pending");
     reopening=JournalStore.open(root,world,OPTIONS).toCompletableFuture();
     phase=23;
    }
    case 23 -> {
     helper.assertTrue(reopening.isDone() && !reopening.isCompletedExceptionally(),
       "J05 inverse WAL reopen pending");
     store=reopening.getNow(null);
     var records=store.view(new StoreQuery(world,Set.of(),
       new Sequence(0),Optional.empty(),64));
     helper.assertTrue(records.status()==ReadStatus.AVAILABLE &&
       records.page().orElseThrow().records().stream()
         .filter(r->r instanceof DurablePrepare).count()==2 &&
       records.page().orElseThrow().records().stream()
         .filter(r->r instanceof DurableResult).count()==2 &&
       records.page().orElseThrow().records().stream()
         .filter(r->r instanceof DurableOwnership owned &&
           owned.value().disposition()==OwnershipDisposition.RELEASED).count()==1,
       "J05 cleanup PREPARE/RESULT or RELEASED ownership lost after recovery");
     helper.assertTrue(level.getBlockState(block).equals(original),
       "J05 inverse physical rollback not preserved");
     closing=store.closeAsync().toCompletableFuture();
     phase=24;
    }
    case 24 -> {
     helper.assertTrue(closing.isDone() && !closing.isCompletedExceptionally(),
       "cleanup recovery store close pending");
     phase=25;
     System.out.println("DIRECTOR_NATIVE_J05_OWNED_BLOCK_INVERSE_WAL_REBOOT_PASS");
    }
    case 25 -> {}
    case 30 -> startPlayerYield(helper);
    case 31 -> {
     helper.assertTrue(yieldedAck!=null && yieldedAck.isDone() &&
       !yieldedAck.isCompletedExceptionally() &&
       yieldedAck.getNow(null).ackStatus()==AckStatus.DURABLE,
       "J05 player ownership tombstone not durably recorded");
     helper.assertTrue(level.getBlockState(block).is(Blocks.GOLD_BLOCK),
       "J05 cleanup overwrote changed player block");
     closing=store.closeAsync().toCompletableFuture();
     phase=32;
    }
    case 32 -> {
     helper.assertTrue(closing.isDone() && !closing.isCompletedExceptionally(),
       "J05 player override WAL close pending");
     reopening=JournalStore.open(root,world,OPTIONS).toCompletableFuture();
     phase=33;
    }
    case 33 -> {
     helper.assertTrue(reopening.isDone() && !reopening.isCompletedExceptionally(),
       "J05 player override WAL reopen pending");
     store=reopening.getNow(null);
     var view=store.view(new StoreQuery(world,Set.of(),
       new Sequence(0),Optional.empty(),64));
     helper.assertTrue(view.status()==ReadStatus.AVAILABLE &&
       view.page().orElseThrow().records().stream()
           .filter(r->r instanceof DurablePrepare).count()==1 &&
       view.page().orElseThrow().records().stream()
           .filter(r->r instanceof DurableResult).count()==1 &&
       view.page().orElseThrow().records().stream()
           .filter(r->r instanceof DurableOwnership owned &&
             owned.value().disposition()==OwnershipDisposition.YIELDED_TO_PLAYER).count()==1,
       "J05 player cession missing or cleanup emitted a forbidden inverse");
     helper.assertTrue(level.getBlockState(block).is(Blocks.GOLD_BLOCK),
       "durable player edit was overwritten after reboot");
     closing=store.closeAsync().toCompletableFuture();
     phase=34;
    }
    case 34 -> {
     helper.assertTrue(closing.isDone() && !closing.isCompletedExceptionally(),
       "J05 player override closed WAL pending");
     // Restore test structure ONLY after proof; player-owned resource is
     // not touched by the Director's cleanup.
     level.setBlock(block,original,
       Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS);
     phase=35;
     System.out.println("DIRECTOR_NATIVE_J05_PLAYER_OVERRIDE_TOMBSTONE_NO_INVERSE_PASS");
    }
    case 35 -> {}
    default -> throw new IllegalStateException("J05 EventRunner stage");
   }
  }
  void submitPhysical(GameTestHelper helper){
   arbiter=new ResourceArbiter(world,new Epoch(0),1000,4,
     new ResourceArbiter.Compatibility(){
      public boolean share(ResourceClaim a,ResourceClaim b){return false;}
      public boolean merge(ResourceId f,ResourceClaim a,ResourceClaim b){return false;}
     });
   var worldScope=new ScopeRef(ScopeKind.WORLD,world.value());
   var claims=prepared.claims();
   Map<ResourceKey,Epoch> liveEpochs=new HashMap<>();
   for(var claim:claims)liveEpochs.put(claim.resource(),claim.expectedEpoch());
   var rank=new ResourceArbiter.Rank(false,false,false,
     Priority.AMBIENT,0,1.0,42);
   var selection=new ResourceArbiter.Candidate(
     new ReservationId(UUID.randomUUID()),intent.instanceId(),
     rank,new Tick(800),claims,Map.of(worldScope,prepared.budgetEstimate()));
   var capacity=Map.of(worldScope,
     new BudgetVector(1000,1000,1000,1000,1000,1000,1000,1000,1000,1000));
   var decisions=arbiter.arbitrate(List.of(selection),new Tick(25),
     liveEpochs,capacity);
   helper.assertTrue(decisions.size()==1 &&
     decisions.getFirst().accepted().isPresent(),
     "J05 resource arbiter did not logically reserve real block");
   token=decisions.getFirst().accepted().orElseThrow();
   prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),
     intent.operationToken(),token.generation(),
     prepared.preconditions(),new Tick(800),slice,token,
     captured.validation(),List.of(),Set.of(),List.of(),
     prepared.durableBlobs());
   live=new LiveValidationSnapshot(prepared.preconditions(),
     footprint.loadedChunks(),HealthMode.NORMAL,new Tick(25),
     token.generation());
   authority=new CommitPermit.Authority(store,4);
   effectPort=new ServerEffectPort(authority,
     i->{throw new IllegalStateException("planning already captured");},
     s->live,Map.of(intent.primitive(),binding),
     Set.of(new CapabilityId("mutation.plants_v1")),4,
     s->new StabilizationLiveSnapshot(store.session(),live,ledger.get()),
     marker->{},(marker,result)->{});
   effectPort.hydrate(prepared,captured.blobs());
   runner=new EventRunner(authority,store,effectPort,arbiter,4);
   var durablePrepare=new DurableBatch(prepare.transactionId(),
     store.session().storeGeneration(),prepare.readSet().revisions(),
     List.of(new DurablePrepare(prepare)),captured.blobs().contents(),
     Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
   runner.submit(durablePrepare,prepared.expectedPost(),live,actual->{
     var resultRecord=new ResultRecord(prepare.transactionId(),
       prepare.operationToken(),actual,Set.of(),List.of(),List.of());
     return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),
       store.session().storeGeneration(),Map.of(),
       List.of(new DurableResult(resultRecord),
         new DurableOwnership(ledger.get().records().get(resource))),
       List.of());
   });
   phase=2;
   System.out.println("DIRECTOR_NATIVE_J05_EVENTRUNNER_LOGICAL_RESERVED_NO_BLOCK_CHANGE");
  }
  PlanningSnapshot selectedAdmissionSnapshot(GameTestHelper helper,SnapshotBatch observed,DirectorWorldState actualWorld){
   if(selectedCaptureFailure!=null)throw new GameTestAssertException(selectedCaptureFailure);
   var query=new StoreQuery(world,Set.of(new OwnershipAggregate(unrelated).key()),new Sequence(0),Optional.empty(),1);
   var read=store.view(query);ReadContracts.validate(read,query,store.session());
   helper.assertTrue(read.status()==ReadStatus.AVAILABLE&&read.page().orElseThrow().aggregates().size()==1,
     "unrelated ownership must come from actual ACKed J04 readback");
   var other=((OwnershipAggregate)read.page().orElseThrow().aggregates().getFirst().state()).value();
   helper.assertTrue(other.equals(unrelated),"unrelated WAL owner changed");
   var before=fixtureInput.state();
   var c=observed.context();var oldTerrain=new ArrayList<TerrainSnapshot>();
   for(var area:c.terrain()){
    var cells=new HashMap<>(area.cells());var stale=cells.get(position);
    if(stale!=null)cells.put(position,new TerrainCellSnapshot(stale.position(),stale.stateFingerprint(),stale.codec(),
      stale.tags(),stale.hazards(),stale.protectedContent(),stale.knownContent(),new Epoch(0),stale.playerOverrideEpoch()));
    oldTerrain.add(new TerrainSnapshot(area.loadedFootprint(),cells,area.safeExits(),area.risks(),area.revision()));
   }
   var rawObserved=new SnapshotBatch(observed.requestId(),observed.scopes(),observed.capturedTick(),observed.scopeRevisions(),
     new ObservationContext(c.scopes(),c.players(),c.groups(),c.regions(),c.anchors(),c.locations(),oldTerrain,c.capabilities()),
     observed.missingData(),observed.loadedChunks(),observed.serverHealth());
   var state=new DecisionState(actualWorld,before.budgets(),new OwnershipView(Map.of(other.resourceKey(),other)),
     before.cooldowns(),before.uniqueness(),before.facts(),before.events(),before.chains(),before.disasters());
   var live=new DecisionInput(rawObserved,fixtureInput.memory(),fixtureInput.catalog(),fixtureInput.config(),
     fixtureInput.requests(),fixtureInput.chains(),fixtureInput.seed(),state,fixtureInput.advisorySnapshot());
   var selected=new SelectedEventStart(fixtureInput,selection,selection.selectedPlan().orElseThrow(),Optional.empty())
     .withLiveContext(()->Optional.of(live));
   var gameplay=new DirectorNativeGameplay(level.getServer(),world,store);
   try{
    var type=Arrays.stream(DirectorNativeGameplay.class.getDeclaredClasses()).filter(t->t.getSimpleName().equals("Flight")).findFirst().orElseThrow();
    var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);var flight=constructor.newInstance();
    for(var item:Map.<String,Object>of("position",position,"key",resource,"selection",selected,"selectedPlan",selected.plan(),"phase",0).entrySet()){
     var field=type.getDeclaredField(item.getKey());field.setAccessible(true);field.set(flight,item.getValue());
    }
    var owners=DirectorNativeGameplay.class.getDeclaredField("owners");owners.setAccessible(true);
    @SuppressWarnings("unchecked") var local=(Map<ResourceKey,OwnershipRecord>)owners.get(gameplay);
    var admitted=ledger.get().records().get(resource);local.put(resource,admitted);
    var method=DirectorNativeGameplay.class.getDeclaredMethod("snapshot",type);method.setAccessible(true);
    var actual=(PlanningSnapshot)method.invoke(gameplay,flight);
    helper.assertTrue(!live.state().ownership().records().containsKey(resource)
      &&live.state().ownership().records().equals(Map.of(other.resourceKey(),other)),"selected capture mutated immutable live J04 ownership");
    helper.assertTrue(actual.ownership().records().equals(Map.of(resource,admitted,other.resourceKey(),other))
      &&actual.snapshot().scopeRevisions().equals(observed.scopeRevisions())&&actual.budgets().equals(state.budgets()),
      "selected native branch lost exact pending owner, unrelated WAL owner, CAS revisions or budget");
    var raw=NativeCataloguePlanning.raw(live);
    helper.assertTrue(actual.blobs().equals(raw.blobs())&&actual.bindings().equals(raw.bindings()),"selected branch changed raw blobs/bindings");
    var measured=actual.snapshot().context().terrain().stream().map(t->t.cells().get(position)).filter(Objects::nonNull).toList();
    helper.assertTrue(!measured.isEmpty()&&measured.stream().allMatch(t->t.ownershipEpoch().equals(admitted.epoch())),
      "old raw epoch0 terrain must never overwrite the fresh admitted epoch1");
    return actual;
   }catch(ReflectiveOperationException failure){
    selectedCaptureFailure=failure.toString();throw new GameTestAssertException("actual selected snapshot seam failed: "+selectedCaptureFailure);
   }finally{gameplay.close();}
  }
  void startDirectorInverse(GameTestHelper helper) {
   var current=ledger.get().records().get(resource);
   var observed=BlockTransformBinding.stateBlob(level.getBlockState(block))
      .reference().hash();
   var expectedPost=prepared.expectedPost().get(resource);
   var originalOwner=current.ownerTokens().iterator().next();
   helper.assertTrue(OwnershipRules.rollback(current,originalOwner,
       current.epoch(),current.playerOverrideEpoch(),expectedPost,
       Optional.of(observed),true)==OwnershipRules.Rollback.ELIGIBLE,
       "J05 cannot inverse an unmodified Director-owned block");
   var reverseRecipe=new ResourceId("proutlost_director:camp_growth_cleanup");
   var inverseIntent=new EffectIntent(new OperationToken(UUID.randomUUID()),
       intent.primitive(),1,new CampGrowth(
         new TagId("proutlost:abandoned_camp"),reverseRecipe,1),
       footprint,intent.instanceId());
   var inverseBinding=new BlockTransformBinding(dim->level,ledger::get,
       (p,next)->{
         if(!p.equals(position))throw new IllegalStateException("foreign inverse");
         ledger.set(new OwnershipView(Map.of(resource,next)));
       },()->new Tick(20100),
       Map.of(reverseRecipe,new BlockTransformBinding.Recipe(
          Map.of(new ResourceId(BuiltInRegistries.BLOCK.getKey(desired.getBlock()).toString()),
                 new ResourceId(BuiltInRegistries.BLOCK.getKey(original.getBlock()).toString())),
          true)),1);
   var postTerrain=new TerrainCellSnapshot(position,observed,
       BlockTransformBinding.STATE_CODEC,Set.of(),Set.of(),
       false,true,current.epoch(),current.playerOverrideEpoch());
   var area=new TerrainSnapshot(footprint,Map.of(position,postTerrain),
       List.of(),Map.of(),new Revision(2));
   var worldScope=new ScopeRef(ScopeKind.WORLD,world.value());
   var snapshot=new SnapshotBatch(new RequestId(UUID.randomUUID()),
       new ScopeSet(world,Set.of(worldScope)),new Tick(20100),
       Map.of(worldScope,new Revision(2)),
       new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),
           Map.of(),Map.of(),List.of(area),Map.of()),
       Set.of(),footprint.loadedChunks(),HealthMode.NORMAL);
   var planning=new PlanningSnapshot(snapshot,ledger.get(),
       new BudgetView(new BudgetVector(100,100,100,100,100,100,100,100,100,100),
           BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));
   var capture=inverseBinding.capture(inverseIntent,planning);
   helper.assertTrue(capture.validation().valid() && capture.prepared().isPresent(),
       "inverse physical cell is no longer safely capturable");
   var inverse=capture.prepared().orElseThrow();
   helper.assertTrue(inverse.expectedPost().get(resource).equals(
       BlockTransformBinding.stateBlob(original).reference().hash()),
       "inverse is not exactly the captured prestate");
   var inverseSlice=new PreparedSlice(new SliceId(UUID.randomUUID()),
       inverseIntent.operationToken(),footprint,inverse.preconditions(),
       List.of(inverseIntent),inverse.budgetEstimate());
   inverseLive=new LiveValidationSnapshot(inverse.preconditions(),
       footprint.loadedChunks(),HealthMode.NORMAL,new Tick(26),token.generation());
   var authority=new CommitPermit.Authority(store,4);
   var port=new ServerEffectPort(authority,
       i->{throw new IllegalStateException("inverse planning captured");},
       s->inverseLive,Map.of(inverseIntent.primitive(),inverseBinding),
       Set.of(new CapabilityId("mutation.plants_v1")),4,
       s->new StabilizationLiveSnapshot(store.session(),inverseLive,ledger.get()),
       marker->{},(marker,result)->{});
   port.hydrate(inverse,capture.blobs());
   var inversePrepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),
       inverseIntent.operationToken(),token.generation(),
       inverse.preconditions(),new Tick(800),inverseSlice,token,
       capture.validation(),List.of(),Set.of(),List.of(),inverse.durableBlobs());
   var durable=new DurableBatch(inversePrepare.transactionId(),
       store.session().storeGeneration(),inversePrepare.readSet().revisions(),
       List.of(new DurablePrepare(inversePrepare)),capture.blobs().contents(),
       Optional.empty(),CommitPermit.Authority.prepareHash(inversePrepare));
   inverseId=inversePrepare.transactionId();
   inverseRunner=new EventRunner(authority,store,port,arbiter,4);
   inverseRunner.submit(durable,inverse.expectedPost(),inverseLive,actual->{
       var result=new ResultRecord(inversePrepare.transactionId(),
           inversePrepare.operationToken(),actual,Set.of(),List.of(),List.of());
       return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),
           store.session().storeGeneration(),Map.of(),
           List.of(new DurableResult(result),
             new DurableOwnership(ledger.get().records().get(resource))),
           List.of());
   });
   phase=21;
   System.out.println("DIRECTOR_NATIVE_J05_OWNED_INVERSE_PREPARE_SUBMITTED");
  }
  void finishDirectorInverse(GameTestHelper helper) {
   var outcome=inverseRunner.poll(inverseId,inverseLive);
   helper.assertTrue(outcome.stage()!=EventRunner.Stage.REJECTED &&
       outcome.stage()!=EventRunner.Stage.RECOVERY_REQUIRED,
       "J05 inverse WAL permit/physical result refused "+outcome.failure());
   if(outcome.stage()!=EventRunner.Stage.COMMITTED)return;
   helper.assertTrue(level.getBlockState(block).equals(original),
       "J05 inverse did not restore exactly original Minecraft state");
   var current=ledger.get().records().get(resource);
   var hdr=current.header();
   var nextHdr=new AggregateHeader(hdr.schemaVersion(),hdr.id(),
       hdr.revision().plus(1),hdr.lastSequence(),new Tick(20101));
   releaseAfterAck=new OwnershipRecord(nextHdr,resource,
       current.ownerTokens(),current.epoch(),OwnershipDisposition.RELEASED,
       current.creationMutationId(),current.expectedPostHash(),
       current.playerOverrideEpoch(),current.exceptionContractId());
   releasedAck=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),
       store.session().storeGeneration(),Map.of(),
       List.of(new DurableOwnership(releaseAfterAck)),List.of()))
       .toCompletableFuture();
   phase=215;
  }
  void startPlayerYield(GameTestHelper helper) {
   var before=ledger.get().records().get(resource);
   level.setBlock(block,Blocks.GOLD_BLOCK.defaultBlockState(),
       Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS);
   helper.assertTrue(level.getBlockState(block).is(Blocks.GOLD_BLOCK),
       "external player modification was not actually applied");
   var yielded=OwnershipRules.cede(before,OwnershipRules.ExternalChange.PLAYER,
       new Tick(20100));
   ledger.set(new OwnershipView(Map.of(resource,yielded)));
   var observed=BlockTransformBinding.stateBlob(level.getBlockState(block))
       .reference().hash();
   helper.assertTrue(OwnershipRules.rollback(yielded,
       before.ownerTokens().iterator().next(),before.epoch(),
       before.playerOverrideEpoch(),prepared.expectedPost().get(resource),
       Optional.of(observed),true)==OwnershipRules.Rollback.YIELDED,
       "J05 might overwrite an externally modified player block");
   yieldedAck=store.append(ResultTransaction.batch(
       new TransactionId(UUID.randomUUID()),
       store.session().storeGeneration(),Map.of(),
       List.of(new DurableOwnership(yielded)),List.of()))
       .toCompletableFuture();
   phase=31;
   System.out.println("DIRECTOR_NATIVE_J05_PLAYER_EDIT_TOMBSTONE_SUBMITTED");
  }
 }

 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
 public static void selectedCampRegrowthOwnershipAwareInverse(GameTestHelper helper){
   var probe=new Probe(helper,FinishMode.DIRECTOR_INVERSE,true);
  helper.succeedWhen(()->{
   probe.advance(helper);
   helper.assertTrue(probe.phase==25,
       "owned J05 inverse effect/ownership RELEASED/recovery still pending");
  });
 }
 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
 public static void selectedCampRegrowthPlayerOverridePreserved(GameTestHelper helper){
  var probe=new Probe(helper,FinishMode.PLAYER_YIELD);
  helper.succeedWhen(()->{
   probe.advance(helper);
   helper.assertTrue(probe.phase==35,
       "player override J05 tombstone or WAL reboot still pending");
  });
 }

 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
 public static void selectedCampRegrowthThenPhysicalBlockAndReboot(
   GameTestHelper helper) {
  var probe=new Probe(helper);
  helper.succeedWhen(()->{
   probe.advance(helper);
   helper.assertTrue(probe.phase==6,
     "native J05 physical EventRunner/WAL result/reboot pending");
  });
 }
}
