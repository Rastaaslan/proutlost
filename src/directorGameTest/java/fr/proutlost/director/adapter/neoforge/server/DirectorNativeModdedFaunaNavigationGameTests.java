package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.BlockTransformBinding;
import fr.proutlost.director.adapter.neoforge.server.effects.FaunaRemoveBinding;
import fr.proutlost.director.adapter.neoforge.server.effects.FaunaNavigationBinding;
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
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Full-modpack bounded native Brain navigation gate; not a habitat/import or campaign acceptance. */
@GameTestHolder("proutlost_director_modded_fauna")
@PrefixGameTestTemplate(false)
public final class DirectorNativeModdedFaunaNavigationGameTests {
    private static final ResourceId CAPYBARA=new ResourceId("reanimal:capybara");
    private static final ResourceId HEDGEHOG=new ResourceId("reanimal:hedgehog");
    private static final TagId CORRIDOR=new TagId("proutlost:fauna_corridor");
    private DirectorNativeModdedFaunaNavigationGameTests() {}

    @GameTest(templateNamespace="proutlost_director",template="fullpack_fauna_lab",timeoutTicks=200)
    public static void explicitReanimalBrainWalkRespectsSealedRouteAndForeignTarget(GameTestHelper helper) {
        var level=helper.getLevel();
        var nativeType=BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(CAPYBARA.value()))
            .orElseThrow(()->new IllegalStateException("MISSING_REQUIRED_DEPENDENCY reanimal:capybara"));
        var at=helper.absolutePos(new BlockPos(2,3,2));
        for(int x=-2;x<=6;x++)for(int z=-2;z<=2;z++){
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
        // Actual cohort preparation holds only its own native Brain movement memories.
        var spawner=new FaunaSpawnBinding(d->level,i->slot.get(),slot::set,()->true,Set.of(CAPYBARA),true,true);
        var spawn=spawner.capture(intent,context);
        var spawnPlan=spawn.prepared().orElseThrow();
        helper.assertTrue(spawner.revalidate(spawnPlan,spawn.blobs()),"real modded spawn context must validate");
        helper.assertTrue(spawner.apply(spawnPlan,spawn.blobs()).outcome()==Outcome.SUCCEEDED,"real modded spawn must execute");
        var created=level.getEntity(reserved.entityUuid());
        helper.assertTrue(created instanceof Animal && created.getType()==nativeType,"exact registered ReAnimal type/UUID required");
        var animal=(Animal)created;
        var cells=new ArrayList<BlockPosition>();
        for(int x=0;x<=4;x++)cells.add(new BlockPosition(dimension,at.getX()+x,at.getY(),at.getZ()));
        var chunks=new HashSet<ChunkRef>();var observations=new HashMap<BlockPosition,TerrainCellSnapshot>();
        for(var p:cells){
            chunks.add(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4));
            observations.put(p,new TerrainCellSnapshot(p,BlockTransformBinding.stateBlob(
                level.getBlockState(new BlockPos(p.x(),p.y(),p.z()))).reference().hash(),
                BlockTransformBinding.STATE_CODEC,Set.of(CORRIDOR),Set.of(),false,true,new Epoch(0),new Epoch(0)));
        }
        var navFootprint=new Footprint(dimension,chunks,Set.copyOf(cells),List.of());
        var navTerrain=new TerrainSnapshot(navFootprint,observations,List.of(),Map.of(),new Revision(1));
        var navSnapshot=new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),tick,Map.of(),
            new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(navTerrain),Map.of()),
            Set.of(),chunks,HealthMode.NORMAL);
        var navContext=new PlanningSnapshot(navSnapshot,new OwnershipView(Map.of()),new BudgetView(cap,BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));
        var route=new FaunaNavigationBinding.Route(slot.get(),cells,.8);
        var navigator=new FaunaNavigationBinding(d->level,i->route,Set.of(CAPYBARA));
        var movement=new EffectIntent(new OperationToken(UUID.randomUUID()),new PrimitiveId("proutlost:fauna_migrate"),1,
            fauna,navFootprint,instance);
        helper.runAfterDelay(20,()->{
        try {
            helper.assertTrue(animal.distanceToSqr(at.getX()+.5,at.getY(),at.getZ()+.5)<.1,
                "prepared real Brain animal must remain at its original slot while all births become durable");
            var navigation=navigator.capture(movement,navContext);var plan=navigation.prepared().orElseThrow();
            helper.assertTrue(navigator.apply(plan,navigation.blobs()).outcome()==Outcome.SUCCEEDED,"native modded navigation must start");
            helper.assertTrue(!animal.isNoAi(),"the mod's Brain must remain enabled");
            helper.assertTrue(animal.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                &&animal.getBrain().hasMemoryValue(MemoryModuleType.PATH),"native memory lease must be installed");
            helper.runAfterDelay(120,()->{
                try {
                    helper.assertTrue(level.getEntity(reserved.entityUuid())==animal,"same real modded UUID must arrive");
                    helper.assertTrue(animal.distanceToSqr(at.getX()+4.5,at.getY(),at.getZ()+.5)<=.75,
                        "real Brain-driven entity must walk to sealed corridor endpoint");
                    helper.assertTrue(navigator.observeArrival(plan,navigation.blobs(),new Tick(level.getGameTime())).isPresent(),
                        "actual movement and navigation completion must prove arrival");
                    var foreign=new WalkTarget(at.offset(4,0,2),.5F,0);
                    animal.getBrain().setMemory(MemoryModuleType.WALK_TARGET,foreign);
                    helper.assertTrue(navigator.observeArrival(plan,navigation.blobs(),new Tick(level.getGameTime())).isEmpty(),
                        "foreign Brain target must invalidate an apparently completed arrival");
                    navigator.releaseNavigation(animal.getUUID());
                    helper.assertTrue(animal.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElseThrow()==foreign,
                        "release must preserve the foreign controller's target");
                    helper.assertTrue(!animal.getBrain().hasMemoryValue(MemoryModuleType.PATH),
                        "release must remove only its own sealed path memory");
                    System.out.println("DIRECTOR_FULLPACK_REANIMAL_BRAIN_SEALED_NAVIGATION_PASS species="+CAPYBARA.value());
                    helper.succeed();
                } finally {navigator.releaseNavigation(animal.getUUID());if(!animal.isRemoved())animal.discard();}
            });
        } catch(RuntimeException|Error failure){
            navigator.releaseNavigation(animal.getUUID());if(!animal.isRemoved())animal.discard();throw failure;
        }
        });
    }
}
