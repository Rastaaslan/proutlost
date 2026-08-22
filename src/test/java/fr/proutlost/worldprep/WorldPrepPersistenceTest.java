package fr.proutlost.worldprep;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.plan.BiomePlanGuard;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorldPrepPersistenceTest {
    @Test void areasPlansJobsAndSnapshotsRoundTrip() {
        var data=new WorldPrepSavedData();
        var area=new WorldPrepSavedData.Area("proutlost:test","minecraft:overworld",-17,-9,31,48);
        data.areas().put(area.id(),area);
        UUID player=UUID.randomUUID(); data.selections().put(player,new WorldPrepSavedData.Selection(-17,-9,31,48));
        var plan=new WorldPrepSavedData.Plan(area.id(),"proutlost:river_test",42,"abc");
        plan.cells.put(WorldPrepSavedData.cellKey(-2,3),new WorldPrepSavedData.BiomeCell(-2,3,"minecraft:forest",99));data.plans().put(area.id(),plan);
        UUID jobId=UUID.randomUUID();data.jobs().put(jobId,new WorldPrepSavedData.Job(jobId,WorldPrepSavedData.Operation.APPLY_BIOMES,area.id(),WorldPrepSavedData.JobState.PAUSED,17,""));
        var snapshot=new WorldPrepSavedData.Snapshot(area.id(),"abc");snapshot.cells.put(WorldPrepSavedData.snapshotKey(-2,-16,3),new WorldPrepSavedData.SnapshotCell(-2,-16,3,"minecraft:deep_ocean"));data.snapshots().put(area.id(),snapshot);
        CompoundTag encoded=data.save(new CompoundTag(),null);var restored=WorldPrepSavedData.load(encoded,null);
        assertEquals(area,restored.areas().get(area.id()));assertEquals(31,restored.selections().get(player).x2());assertEquals("minecraft:forest",restored.plans().get(area.id()).cells.values().iterator().next().biome());assertEquals(17,restored.jobs().get(jobId).cursor());assertEquals("minecraft:deep_ocean",restored.snapshots().get(area.id()).cells.values().iterator().next().biome());
    }
    @Test void newerSchemaIsRejectedWithoutOverwrite(){var tag=new CompoundTag();tag.putInt("schema",WorldPrepSavedData.SCHEMA+1);assertThrows(IllegalStateException.class,()->WorldPrepSavedData.load(tag,null));}
    @Test void staleTerrainIsRejected(){BiomePlanGuard.requireCurrent(7,7);assertThrows(IllegalStateException.class,()->BiomePlanGuard.requireCurrent(7,8));}
    @Test void overlappingDestructiveAreaIsRejectedEvenWhilePaused(){var d=new WorldPrepSavedData();var a=new WorldPrepSavedData.Area("proutlost:a","minecraft:overworld",-10,-10,10,10);var b=new WorldPrepSavedData.Area("proutlost:b","minecraft:overworld",10,10,20,20);d.areas().put(a.id(),a);d.areas().put(b.id(),b);UUID id=UUID.randomUUID();d.jobs().put(id,new WorldPrepSavedData.Job(id,WorldPrepSavedData.Operation.APPLY_BIOMES,a.id(),WorldPrepSavedData.JobState.PAUSED,4,""));assertThrows(IllegalStateException.class,()->WorldPrepRuntime.assertUnlocked(d,b));}
    @Test void snapshotRetainsExactVerticalBiomeValues(){var s=new WorldPrepSavedData.Snapshot("proutlost:a","fp");s.cells.put(WorldPrepSavedData.snapshotKey(1,-16,2),new WorldPrepSavedData.SnapshotCell(1,-16,2,"minecraft:deep_ocean"));s.cells.put(WorldPrepSavedData.snapshotKey(1,-15,2),new WorldPrepSavedData.SnapshotCell(1,-15,2,"minecraft:ocean"));var data=new WorldPrepSavedData();data.snapshots().put("proutlost:a",s);var restored=WorldPrepSavedData.load(data.save(new CompoundTag(),null),null).snapshots().get("proutlost:a");assertEquals(s.cells,restored.cells);}
}
