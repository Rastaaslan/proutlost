package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.BlockTransformBinding;
import fr.proutlost.director.adapter.neoforge.server.effects.FaunaRemoveBinding;
import fr.proutlost.director.adapter.neoforge.server.effects.FaunaSpawnBinding;
import fr.proutlost.director.adapter.neoforge.server.ownership.EntityDirectorAttachment;
import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Full-modpack provider gate. Does not qualify habitats, migrations or campaign acceptance. */
@GameTestHolder("proutlost_director_modded_fauna")
@PrefixGameTestTemplate(false)
public final class DirectorNativeModdedFaunaLifecycleGameTests {
    private static final ResourceId CAPYBARA=new ResourceId("reanimal:capybara");
    private static final ResourceId HEDGEHOG=new ResourceId("reanimal:hedgehog");
    private static final TagId CORRIDOR=new TagId("proutlost:fauna_corridor");
    private DirectorNativeModdedFaunaLifecycleGameTests() {}

    @GameTest(templateNamespace="proutlost_director",template="fullpack_fauna_lab",timeoutTicks=200)
    public static void explicitReanimalSpawnAndCleanupProtectOwnership(GameTestHelper helper) {
        var level=helper.getLevel();
        var nativeType=BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(CAPYBARA.value()))
            .orElseThrow(()->new IllegalStateException("MISSING_REQUIRED_DEPENDENCY reanimal:capybara"));
        var at=helper.absolutePos(new BlockPos(2,3,2));
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
            new OwnerToken(UUID.randomUUID()),UUID.randomUUID(),instance,new CohortId(UUID.randomUUID()),
            "fullpack-capybara",EntityPhase.RESERVED,OwnershipDisposition.DIRECTOR_ACTIVE,chunk,tick,
            ArrivalPolicy.SETTLE_WORLD,CleanupReason.COMPLETED,Optional.empty(),new Epoch(1));
        var slot=new AtomicReference<>(reserved);
        var fauna=new FaunaMigrate(CAPYBARA,1,CORRIDOR,ArrivalPolicy.SETTLE_WORLD);
        var intent=new EffectIntent(new OperationToken(UUID.randomUUID()),new PrimitiveId("proutlost:fauna_spawn_owned"),
            1,fauna,footprint,instance);
        var cell=new TerrainCellSnapshot(point,BlockTransformBinding.stateBlob(level.getBlockState(at)).reference().hash(),
            BlockTransformBinding.STATE_CODEC,Set.of(CORRIDOR),Set.of(),false,true,new Epoch(0),new Epoch(0));
        var terrain=new TerrainSnapshot(footprint,Map.of(point,cell),List.of(),Map.of(),new Revision(1));
        var snapshot=new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),tick,Map.of(),
            new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(terrain),Map.of()),
            Set.of(),Set.of(chunk),HealthMode.NORMAL);
        var cap=new BudgetVector(100,100,100,10,10,100,1048576,10,1048576,100);
        var context=new PlanningSnapshot(snapshot,new OwnershipView(Map.of()),new BudgetView(cap,BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));
        // Explicit spawn-only profile: no Goal MOVE lease is imposed on the mod's Brain.
        var spawner=new FaunaSpawnBinding(d->level,i->slot.get(),slot::set,()->true,Set.of(CAPYBARA),false);
        var spawn=spawner.capture(intent,context);
        var spawnPlan=spawn.prepared().orElseThrow();
        helper.assertTrue(spawner.revalidate(spawnPlan,spawn.blobs()),"real modded spawn context must validate");
        helper.assertTrue(spawner.apply(spawnPlan,spawn.blobs()).outcome()==Outcome.SUCCEEDED,"real modded spawn must execute");
        var created=level.getEntity(reserved.entityUuid());
        helper.assertTrue(created instanceof Animal && created.getType()==nativeType,"exact registered ReAnimal type/UUID required");
        var animal=(Animal)created;
        try {
            helper.assertTrue(EntityDirectorAttachment.read(animal).equals(Optional.of(slot.get())),"same owned UUID attached after spawn");
            helper.assertTrue(animal.goalSelector.getAvailableGoals().stream()
                .noneMatch(g->g.getGoal().getClass().getSimpleName().equals("PreparationHold")),
                "spawn-only mode must preserve native Brain without unresolved navigation hold");
            var cleanup=new FaunaRemoveBinding(d->level,i->slot.get(),slot::set,Set.of(CAPYBARA,HEDGEHOG));
            var removal=new EffectIntent(new OperationToken(UUID.randomUUID()),FaunaRemoveBinding.REMOVE,1,fauna,footprint,instance);
            boolean legacyRejected=false;
            try { new FaunaRemoveBinding(d->level,i->slot.get(),slot::set).capture(removal,context); }
            catch(IllegalArgumentException expected){legacyRejected=true;}
            helper.assertTrue(legacyRejected,"legacy constructor must remain sheep-only");
            var captured=cleanup.capture(removal,context);var plan=captured.prepared().orElseThrow();
            helper.assertTrue(cleanup.revalidate(plan,captured.blobs()),"profiled real modded cleanup must validate");
            var substituted=new EffectIntent(removal.operationToken(),removal.primitive(),1,
                new FaunaMigrate(HEDGEHOG,1,CORRIDOR,ArrivalPolicy.SETTLE_WORLD),footprint,instance);
            var changed=new PreparedEffect(substituted,plan.snapshot(),plan.claims(),plan.preconditions(),plan.expectedPost(),
                plan.budgetEstimate(),plan.inversePlan(),plan.capabilities(),plan.durableBlobs());
            helper.assertTrue(!cleanup.revalidate(changed,captured.blobs()),"same UUID/owner cannot authorize a different species");
            animal.setCustomName(Component.literal("QA adopted animal"));
            helper.assertTrue(!cleanup.revalidate(plan,captured.blobs()),"named animal must survive stale ownership cleanup");
            helper.assertTrue(cleanup.reconcile(new RecoveryProbe(plan,List.of(),captured.blobs())).status()==ReconcileStatus.PLAYER_OVERRIDE,
                "native adoption must reconcile as player override");
            helper.assertTrue(level.getEntity(reserved.entityUuid())==animal&&!animal.isRemoved(),"adopted animal still physically present");
            animal.setCustomName(null); // Owned disposable fixture only; no player adoption occurred.
            helper.assertTrue(cleanup.apply(plan,captured.blobs()).outcome()==Outcome.SUCCEEDED,"real profiled cleanup must execute");
            helper.assertTrue(animal.isRemoved(),"cleanup must physically remove exact owned entity");
            helper.assertTrue(cleanup.reconcile(new RecoveryProbe(plan,List.of(),captured.blobs())).status()==ReconcileStatus.UNAVAILABLE,
                "missing mobile UUID must not fabricate removal proof or replacement animal");
            System.out.println("DIRECTOR_FULLPACK_REANIMAL_SPAWN_CLEANUP_OWNERSHIP_PASS species="+CAPYBARA.value());
            helper.succeed();
        } finally { if(!animal.isRemoved())animal.discard(); }
    }
}
