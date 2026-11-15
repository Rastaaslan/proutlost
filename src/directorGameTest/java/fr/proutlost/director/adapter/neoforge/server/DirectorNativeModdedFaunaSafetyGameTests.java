package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.BlockTransformBinding;
import fr.proutlost.director.adapter.neoforge.server.effects.FaunaSpawnBinding;
import fr.proutlost.director.adapter.neoforge.server.ownership.EntityDirectorAttachment;
import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real post-add failure cleanup and immutable spawn-policy/adoption recovery gates. */
@GameTestHolder("proutlost_director_modded_fauna")
@PrefixGameTestTemplate(false)
public final class DirectorNativeModdedFaunaSafetyGameTests {
    private static final ResourceId SPECIES=new ResourceId("reanimal:capybara");
    private static final TagId CORRIDOR=new TagId("proutlost:fauna_corridor");
    private record Fixture(EntityOwnershipRecord reserved,AtomicReference<EntityOwnershipRecord> slot,
                           EffectIntent intent,PlanningSnapshot context,BlockPos at) {}
    private DirectorNativeModdedFaunaSafetyGameTests() {}

    private static Fixture fixture(GameTestHelper helper) {
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(2,3,2));
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
            level.setBlockAndUpdate(at.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState());
            for(int y=0;y<=3;y++)level.setBlockAndUpdate(at.offset(x,y,z),Blocks.AIR.defaultBlockState());
        }
        var dimension=new DimensionId(level.dimension().location().toString());
        var point=new BlockPosition(dimension,at.getX(),at.getY(),at.getZ());
        var chunk=new ChunkRef(dimension,at.getX()>>4,at.getZ()>>4);
        var footprint=new Footprint(dimension,Set.of(chunk),Set.of(point),List.of());
        var world=new WorldId(UUID.randomUUID());var instance=new EventInstanceId(UUID.randomUUID());
        var tick=new Tick(level.getGameTime());
        var reserved=new EntityOwnershipRecord(new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(0),tick),
            new OwnerToken(UUID.randomUUID()),UUID.randomUUID(),instance,new CohortId(UUID.randomUUID()),"safety-capybara",
            EntityPhase.RESERVED,OwnershipDisposition.DIRECTOR_ACTIVE,chunk,tick,ArrivalPolicy.SETTLE_WORLD,
            CleanupReason.COMPLETED,Optional.empty(),new Epoch(1));
        var intent=new EffectIntent(new OperationToken(UUID.randomUUID()),new PrimitiveId("proutlost:fauna_spawn_owned"),1,
            new FaunaMigrate(SPECIES,1,CORRIDOR,ArrivalPolicy.SETTLE_WORLD),footprint,instance);
        var cell=new TerrainCellSnapshot(point,BlockTransformBinding.stateBlob(level.getBlockState(at)).reference().hash(),
            BlockTransformBinding.STATE_CODEC,Set.of(CORRIDOR),Set.of(),false,true,new Epoch(0),new Epoch(0));
        var terrain=new TerrainSnapshot(footprint,Map.of(point,cell),List.of(),Map.of(),new Revision(1));
        var snapshot=new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),tick,Map.of(),
            new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(terrain),Map.of()),
            Set.of(),Set.of(chunk),HealthMode.NORMAL);
        var cap=new BudgetVector(100,100,100,10,10,100,1048576,10,1048576,100);
        return new Fixture(reserved,new AtomicReference<>(reserved),intent,
            new PlanningSnapshot(snapshot,new OwnershipView(Map.of()),new BudgetView(cap,BudgetMath.zero(),Map.of()),
                new BlobBundle(List.of())),at);
    }
    private static PreparedEffect substitute(PreparedEffect source,EffectIntent intent) {
        return new PreparedEffect(intent,source.snapshot(),source.claims(),source.preconditions(),source.expectedPost(),
            source.budgetEstimate(),source.inversePlan(),source.capabilities(),source.durableBlobs());
    }
    @GameTest(templateNamespace="proutlost_director",template="fullpack_fauna_lab",timeoutTicks=200)
    public static void heldBirthCallbackFailureReleasesOnlyItsOwnMemories(GameTestHelper helper) {
        var f=fixture(helper);var level=helper.getLevel();
        var foreign=new WalkTarget(f.at().offset(2,0,1),.5F,0);
        var binding=new FaunaSpawnBinding(d->level,i->f.slot().get(),after->{
            f.slot().set(after);var animal=(Animal)level.getEntity(after.entityUuid());
            var wrongOwner=new EntityOwnershipRecord(after.header(),new OwnerToken(UUID.randomUUID()),after.entityUuid(),
                after.instanceId(),after.cohortId(),after.slotId(),after.phase(),after.disposition(),after.lastKnownChunk(),
                after.lastSeenTick(),after.persistMode(),after.cleanupPolicy(),after.adoptedBy(),after.generation());
            FaunaSpawnBinding.releasePreparationHold(animal,wrongOwner);
            helper.assertTrue(animal.getBrain().hasMemoryValue(MemoryModuleType.PATH),
                "wrong owner must not release the newborn's preparation path");
            animal.getBrain().setMemory(MemoryModuleType.WALK_TARGET,foreign);
            throw new IllegalStateException("INJECTED_POST_ADD_CALLBACK_FAILURE");
        },()->true,Set.of(SPECIES),true,true);
        var captured=binding.capture(f.intent(),f.context());boolean injected=false;
        try { binding.apply(captured.prepared().orElseThrow(),captured.blobs()); }
        catch(IllegalStateException expected){injected="INJECTED_POST_ADD_CALLBACK_FAILURE".equals(expected.getMessage());}
        var entity=level.getEntity(f.reserved().entityUuid());
        helper.assertTrue(entity instanceof Animal,"uncertain physical add must retain its exact original UUID");
        var animal=(Animal)entity;
        try {
            helper.assertTrue(injected,"actual post-add failure path must have executed");
            helper.assertTrue(!animal.getBrain().hasMemoryValue(MemoryModuleType.PATH),"failure must release its own held path");
            helper.assertTrue(animal.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElseThrow()==foreign,
                "failure must preserve a foreign target installed after its hold");
            helper.assertTrue(animal.goalSelector.getAvailableGoals().stream()
                .noneMatch(g->g.getGoal().getClass().getSimpleName().equals("PreparationHold")),
                "failed held birth must have no abandoned control goal");
            helper.assertTrue(EntityDirectorAttachment.read(animal).equals(Optional.of(f.slot().get())),
                "transient cleanup must preserve native attachment and durable identity");
            helper.assertTrue(animal.isAlive()&&!animal.isNoAi(),"failure cleanup must preserve native AI and the animal");
            System.out.println("DIRECTOR_FULLPACK_REANIMAL_HELD_BIRTH_FAILURE_CLEANUP_PASS");helper.succeed();
        } finally {animal.discard();}
    }
    @GameTest(templateNamespace="proutlost_director",template="fullpack_fauna_lab",timeoutTicks=200)
    public static void immutableSpawnPolicyAndNewerAdoptionAreRequired(GameTestHelper helper) {
        var f=fixture(helper);var level=helper.getLevel();
        var binding=new FaunaSpawnBinding(d->level,i->f.slot().get(),f.slot()::set,()->true,Set.of(SPECIES),false);
        var changedPolicy=new EffectIntent(f.intent().operationToken(),f.intent().primitive(),1,
            new FaunaMigrate(SPECIES,1,CORRIDOR,ArrivalPolicy.CLEANUP_OWNED),f.intent().expectedFootprint(),f.intent().instanceId());
        boolean rejected=false;
        try {binding.capture(changedPolicy,f.context());}catch(IllegalArgumentException expected){rejected=true;}
        helper.assertTrue(rejected,"capture must reject arrival-policy substitution against the reserved slot");
        var captured=binding.capture(f.intent(),f.context());var plan=captured.prepared().orElseThrow();
        var forged=substitute(plan,changedPolicy);
        helper.assertTrue(!binding.revalidate(forged,captured.blobs()),"same PRE/POST must not authorize another arrival policy");
        binding.apply(plan,captured.blobs());var animal=(Animal)level.getEntity(f.reserved().entityUuid());
        try {
            helper.assertTrue(binding.reconcile(new RecoveryProbe(plan,List.of(),captured.blobs())).status()==ReconcileStatus.SAME_POST,
                "authentic live POST must remain recoverable");
            helper.assertTrue(binding.reconcile(new RecoveryProbe(forged,List.of(),captured.blobs())).status()==ReconcileStatus.AMBIGUOUS,
                "recovery must reject the substituted arrival policy");
            var original=f.slot().get();var player=new PlayerId(UUID.randomUUID());
            var stale=new EntityOwnershipRecord(new AggregateHeader(1,original.header().id(),original.header().revision().plus(1),
                original.header().lastSequence(),original.header().updatedTick()),original.ownerToken(),original.entityUuid(),
                original.instanceId(),original.cohortId(),original.slotId(),EntityPhase.ADOPTED,OwnershipDisposition.YIELDED_TO_PLAYER,
                original.lastKnownChunk(),original.lastSeenTick(),original.persistMode(),original.cleanupPolicy(),Optional.of(player),original.generation());
            EntityDirectorAttachment.compareAndSet(animal,original,stale);
            helper.assertTrue(binding.reconcile(new RecoveryProbe(plan,List.of(),captured.blobs())).status()==ReconcileStatus.AMBIGUOUS,
                "an adopted flag without a newer generation is not player-override proof");
            var newer=new EntityOwnershipRecord(new AggregateHeader(1,original.header().id(),stale.header().revision().plus(1),
                original.header().lastSequence(),original.header().updatedTick()),original.ownerToken(),original.entityUuid(),
                original.instanceId(),original.cohortId(),original.slotId(),EntityPhase.ADOPTED,OwnershipDisposition.YIELDED_TO_PLAYER,
                original.lastKnownChunk(),original.lastSeenTick(),original.persistMode(),original.cleanupPolicy(),Optional.of(player),original.generation().plus(1));
            EntityDirectorAttachment.compareAndSet(animal,stale,newer);
            helper.assertTrue(binding.reconcile(new RecoveryProbe(plan,List.of(),captured.blobs())).status()==ReconcileStatus.PLAYER_OVERRIDE,
                "same lineage and genuinely newer coherent adoption must remain recognizable");
            System.out.println("DIRECTOR_FULLPACK_REANIMAL_SPAWN_POLICY_ADOPTION_FENCES_PASS");helper.succeed();
        } finally {if(animal!=null)animal.discard();}
    }
}
