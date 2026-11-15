package fr.proutlost.worldprep;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.plan.BiomeBounds;
import fr.proutlost.worldprep.plan.BiomePlanGuard;
import fr.proutlost.worldprep.plan.PlanIdentity;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class WorldPrepPersistenceTest {
    private static WorldPrepSavedData.PlanInput input(){return new WorldPrepSavedData.PlanInput("minecraft:overworld",-17,-9,31,48,42,"proutlost:river_test","profile","config","registry","override","protection");}
    @Test void areasPlansJobsProtectionsOverridesAndSnapshotsRoundTrip(){var data=new WorldPrepSavedData();var area=new WorldPrepSavedData.Area("proutlost:test","minecraft:overworld",-17,-9,31,48);area=area.withMask(WorldPrepSavedData.AreaMask.LAND_AND_COAST);data.areas().put(area.id(),area);UUID player=UUID.randomUUID();data.selections().put(player,new WorldPrepSavedData.Selection(-17,-9,31,48));data.protections().put("site",new WorldPrepSavedData.ProtectedZone("site","minecraft:overworld",0,0,3,3));data.overrides().put(WorldPrepSavedData.cellKey(-2,3),new WorldPrepSavedData.OverrideCell(-2,3,"minecraft:forest",null));var plan=new WorldPrepSavedData.Plan(area.id(),input(),"abc",UUID.randomUUID(),1);plan.cells.put(WorldPrepSavedData.cellKey(-2,3),new WorldPrepSavedData.BiomeCell(-2,3,"minecraft:forest",99));data.plans().put(area.id(),plan);UUID jobId=UUID.randomUUID(),snapshotId=UUID.randomUUID();data.jobs().put(jobId,new WorldPrepSavedData.Job(jobId,WorldPrepSavedData.Operation.APPLY_BIOMES,area.id(),WorldPrepSavedData.JobState.PAUSED,17,"",snapshotId));var snapshot=new WorldPrepSavedData.Snapshot(snapshotId,jobId,plan.planId,area.dimension(),area.id(),"BIOMES","abc");snapshot.pageCount=2;snapshot.entryCount=192;snapshot.journalRoot="root";data.snapshots().put(snapshotId,snapshot);var restored=WorldPrepSavedData.load(data.save(new CompoundTag(),null),null);assertEquals(area,restored.areas().get(area.id()));assertEquals(WorldPrepSavedData.AreaMask.LAND_AND_COAST,restored.areas().get(area.id()).mask());var legacy=data.save(new CompoundTag(),null);legacy.getList("areas",net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).remove("mask");assertEquals(WorldPrepSavedData.AreaMask.FULL_AREA,WorldPrepSavedData.load(legacy,null).areas().get(area.id()).mask());legacy.getList("areas",net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putString("mask","CONNECTED_ISLAND");assertThrows(IllegalArgumentException.class,()->WorldPrepSavedData.load(legacy,null));assertEquals(31,restored.selections().get(player).x2());assertTrue(restored.protectedAt(area.dimension(),0,0));assertEquals("minecraft:forest",restored.overrides().values().iterator().next().exactBiome());assertEquals(input(),restored.plans().get(area.id()).input);assertEquals(17,restored.jobs().get(jobId).cursor());assertEquals(192,restored.snapshots().get(snapshotId).entryCount);}
    @Test void treeModesAndEnvironmentOperationsRoundTripWithLegacyDefaults(){
        var data=new WorldPrepSavedData();
        for(var mode:fr.proutlost.worldprep.ecology.VegetationPlanner.TreeMode.values()){
            var area=new WorldPrepSavedData.Area("proutlost:"+mode.name().toLowerCase(java.util.Locale.ROOT),"minecraft:overworld",0,0,7,7)
                .withTreeMode(mode).withMask(WorldPrepSavedData.AreaMask.LAND);
            assertEquals(mode,area.treeMode());assertEquals(WorldPrepSavedData.AreaMask.LAND,area.withTreeMode(mode).mask());data.areas().put(area.id(),area);
        }
        for(var op:WorldPrepSavedData.Operation.values()){var id=UUID.randomUUID();data.jobs().put(id,new WorldPrepSavedData.Job(id,op,"proutlost:normal",WorldPrepSavedData.JobState.QUEUED,0,"",null));}
        var saved=data.save(new CompoundTag(),null);assertEquals(7,saved.getInt("schema"));
        var originalJobs=java.util.Map.copyOf(data.jobs());
        var restored=WorldPrepSavedData.load(saved,null);assertEquals(data.areas(),restored.areas());
        var pausedJobs=new java.util.LinkedHashMap<UUID,WorldPrepSavedData.Job>();
        originalJobs.forEach((id,job)->pausedJobs.put(id,job.withState(WorldPrepSavedData.JobState.PAUSED)));
        assertEquals(pausedJobs,restored.jobs(),"Cold reload retains every operation and its metadata while pausing pending work for explicit revalidation");
        assertEquals(originalJobs,data.jobs(),"Loading a new state must not mutate the original pending jobs");
        assertEquals(fr.proutlost.worldprep.ecology.VegetationPlanner.TreeMode.NORMAL,new WorldPrepSavedData.Area("x","minecraft:overworld",0,0,7,7,WorldPrepSavedData.AreaMask.COAST).treeMode());
        for(int schema:new int[]{0,4,5,6,7}){
            var legacy=saved.copy();legacy.putInt("schema",schema);
            for(var value:legacy.getList("areas",net.minecraft.nbt.Tag.TAG_COMPOUND))((CompoundTag)value).remove("treeMode");
            for(var area:WorldPrepSavedData.load(legacy,null).areas().values())assertEquals(fr.proutlost.worldprep.ecology.VegetationPlanner.TreeMode.NORMAL,area.treeMode());
        }
        var invalid=saved.copy();invalid.getList("areas",net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putString("treeMode","UNSUPPORTED");
        assertThrows(IllegalArgumentException.class,()->WorldPrepSavedData.load(invalid,null));
    }
    @Test void newerSchemaIsRejectedWithoutOverwrite(){var tag=new CompoundTag();tag.putInt("schema",WorldPrepSavedData.SCHEMA+1);assertThrows(IllegalStateException.class,()->WorldPrepSavedData.load(tag,null));}
    @Test void staleTerrainIsRejected(){BiomePlanGuard.requireCurrent(7,7);assertThrows(IllegalStateException.class,()->BiomePlanGuard.requireCurrent(7,8));}
    @Test void completePlanInputStaleMatrix(){var base=input();PlanIdentity.requireCurrent(base,base);assertStale(new WorldPrepSavedData.PlanInput("minecraft:nether",base.minX(),base.minZ(),base.maxX(),base.maxZ(),base.seed(),base.profile(),base.profileDigest(),base.configDigest(),base.registryDigest(),base.overrideDigest(),base.protectionDigest()));assertStale(new WorldPrepSavedData.PlanInput(base.dimension(),0,base.minZ(),base.maxX(),base.maxZ(),base.seed(),base.profile(),base.profileDigest(),base.configDigest(),base.registryDigest(),base.overrideDigest(),base.protectionDigest()));assertStale(change("seed"));assertStale(change("profile"));assertStale(change("profileDigest"));assertStale(change("config"));assertStale(change("registry"));assertStale(change("override"));assertStale(change("protection"));}
    private static void assertStale(WorldPrepSavedData.PlanInput changed){assertThrows(IllegalStateException.class,()->PlanIdentity.requireCurrent(input(),changed));}
    private static WorldPrepSavedData.PlanInput change(String field){var i=input();return new WorldPrepSavedData.PlanInput(i.dimension(),i.minX(),i.minZ(),i.maxX(),i.maxZ(),field.equals("seed")?43:i.seed(),field.equals("profile")?"x:y":i.profile(),field.equals("profileDigest")?"x":i.profileDigest(),field.equals("config")?"x":i.configDigest(),field.equals("registry")?"x":i.registryDigest(),field.equals("override")?"x":i.overrideDigest(),field.equals("protection")?"x":i.protectionDigest());}
    @Test void overlappingDestructiveAreaIsRejectedEvenWhilePaused(){var d=new WorldPrepSavedData();var a=new WorldPrepSavedData.Area("proutlost:a","minecraft:overworld",-10,-10,10,10);var b=new WorldPrepSavedData.Area("proutlost:b","minecraft:overworld",10,10,20,20);d.areas().put(a.id(),a);d.areas().put(b.id(),b);UUID id=UUID.randomUUID();d.jobs().put(id,new WorldPrepSavedData.Job(id,WorldPrepSavedData.Operation.APPLY_BIOMES,a.id(),WorldPrepSavedData.JobState.PAUSED,4,"",UUID.randomUUID()));assertThrows(IllegalStateException.class,()->WorldPrepRuntime.assertUnlocked(d,b));}
    @Test void snapshotOwnershipRejectsAreaDimensionPassAndFingerprintMismatch(){var area=new WorldPrepSavedData.Area("proutlost:a","minecraft:overworld",0,0,15,15);var s=new WorldPrepSavedData.Snapshot(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),area.dimension(),area.id(),"BIOMES","fp");WorldPrepRuntime.requireSnapshotOwnership(s,area,"fp");assertThrows(IllegalStateException.class,()->WorldPrepRuntime.requireSnapshotOwnership(s,new WorldPrepSavedData.Area("proutlost:b",area.dimension(),0,0,15,15),"fp"));assertThrows(IllegalStateException.class,()->WorldPrepRuntime.requireSnapshotOwnership(s,new WorldPrepSavedData.Area(area.id(),"minecraft:nether",0,0,15,15),"fp"));assertThrows(IllegalStateException.class,()->WorldPrepRuntime.requireSnapshotOwnership(s,area,"later"));}
    @Test void destructiveBoundsOnlyContainCompleteQuartCells(){for(var area:new WorldPrepSavedData.Area[]{new WorldPrepSavedData.Area("proutlost:p","minecraft:overworld",1,2,18,21),new WorldPrepSavedData.Area("proutlost:n","minecraft:overworld",-19,-18,-2,-1),new WorldPrepSavedData.Area("proutlost:c","minecraft:overworld",15,15,36,36)}){var b=BiomeBounds.inside(area);for(int z=b.minQuartZ();z<=b.maxQuartZ();z++)for(int x=b.minQuartX();x<=b.maxQuartX();x++)assertTrue(b.footprintInside(area,x,z));assertFalse(b.contains(b.minQuartX()-1,b.minQuartZ()));assertFalse(b.contains(b.maxQuartX()+1,b.maxQuartZ()));}}
    @Test void snapshotPersistsOnlyCompactJournalMetadata(){UUID id=UUID.randomUUID(),job=UUID.randomUUID(),plan=UUID.randomUUID();var snapshot=new WorldPrepSavedData.Snapshot(id,job,plan,"minecraft:overworld","proutlost:a","BIOMES","fp");snapshot.pageCount=9;snapshot.entryCount=864;snapshot.journalRoot="root";var data=new WorldPrepSavedData();data.snapshots().put(id,snapshot);var restored=WorldPrepSavedData.load(data.save(new CompoundTag(),null),null).snapshots().get(id);assertEquals(plan,restored.planId);assertEquals(9,restored.pageCount);assertEquals(864,restored.entryCount);assertEquals("root",restored.journalRoot);assertFalse(data.save(new CompoundTag(),null).toString().contains("before"));}
}
