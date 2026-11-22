package fr.proutlost.worldprep.analysis;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData.*;
import fr.proutlost.worldprep.runtime.MinecraftTerrainSampler;
import fr.proutlost.worldprep.runtime.WorldPrepHabitatRuntime;
import fr.proutlost.worldprep.ecology.HabitatMap.Habitat;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class TerrainAnalysisExtrasTest {
    @Test void clippedNegativeQuartCellsCoverEverySelectedBlockExactlyOnceAndEveryPrefixCountsExactly() {
        var g=new TerrainAnalysisExtras.Geometry(-5,-2,-3,5,7,6);var seen=new HashSet<String>();
        assertEquals(990,g.blocks());
        for(long i=0;i<g.cells();i++) {
            assertEquals(seen.size(),g.blocksBefore(i));var c=g.at(i);assertTrue(c.blocks()>=1&&c.blocks()<=64);
            for(int z=c.minZ();z<=c.maxZ();z++)for(int x=c.minX();x<=c.maxX();x++)for(int y=c.minY();y<=c.maxY();y++)
                assertTrue(seen.add(x+","+y+","+z));
        }
        assertEquals(990,seen.size());assertEquals(990,g.blocksBefore(g.cells()));
        assertThrows(IndexOutOfBoundsException.class,()->g.at(g.cells()));
        assertThrows(IllegalArgumentException.class,()->g.blocksBefore(g.cells()+1));
    }
    private static TerrainAnalysisExtras readySurface() {
        var e=new TerrainAnalysisExtras(new TerrainAnalysisExtras.Geometry(-5,-2,-3,5,7,6));
        for(long i=0;i<e.geometry.surfaceCells();i++)e.appendSurface(new TerrainAnalysisExtras.Surface(
            e.geometry.surfaceColumnsBefore(i+1)-e.geometry.surfaceColumnsBefore(i),i%2==0,false,false,"OPEN_GRASSLAND"));
        return e;
    }
    @Test void partialVolumeAndSurfaceAggregatesRoundTripAndMalformedPrefixesRefuse() {
        var e=readySurface();
        for(int n=0;n<7;n++){long blocks=e.geometry.at(n).blocks();e.appendVolume(new TerrainAnalysisExtras.Volume(blocks,blocks,blocks,0,0,blocks));}
        e.requirePrefix(e.geometry.surfaceCells());var tag=e.save();var restored=TerrainAnalysisExtras.load(tag,e.surfaceSamples);
        assertEquals(tag,restored.save());assertEquals(e.geometry.blocksBefore(7),restored.volumeBlocks);
        var missing=tag.copy();missing.remove("geologyHosts");assertThrows(IllegalStateException.class,()->TerrainAnalysisExtras.load(missing,e.surfaceSamples));
        var cursor=tag.copy();cursor.putLong("volumeCells",8);assertThrows(IllegalStateException.class,()->TerrainAnalysisExtras.load(cursor,e.surfaceSamples));
        var protectedCount=tag.copy();protectedCount.putLong("protectedVolumeBlocks",e.volumeBlocks+1);assertThrows(IllegalStateException.class,()->TerrainAnalysisExtras.load(protectedCount,e.surfaceSamples));
        var future=tag.copy();future.putInt("schema",2);assertThrows(IllegalStateException.class,()->TerrainAnalysisExtras.load(future,e.surfaceSamples));
    }
    @Test void actualSavedDataColdLoadRetainsSameAnalysisJobAndVolumePhaseWhileLegacyRemainsDeclaredAbsent() {
        var e=readySurface();for(int n=0;n<7;n++){long blocks=e.geometry.at(n).blocks();e.appendVolume(new TerrainAnalysisExtras.Volume(blocks,blocks,0,blocks,0,0));}
        var input=new PlanInput("minecraft:overworld",-5,-3,5,6,42,"profile","a","b","c","d","e");
        var a=new Analysis(input,e.geometry.surfaceCells(),-2,7,1,0,"a".repeat(64));a.extended=e;
        a.samples=e.surfaceSamples;a.terrainSamples=a.samples;a.altitudeBands.put("0",a.samples);a.slopeBands.put("0",a.samples);a.biomes.put("minecraft:plains",a.samples);
        var id=UUID.randomUUID();var d=new WorldPrepSavedData();d.analyses().put(id,a);d.jobs().put(id,new Job(id,Operation.ANALYZE,"proutlost:test",JobState.RUNNING,a.samples,"",null));
        var loaded=WorldPrepSavedData.load(d.save(new CompoundTag(),null),null);
        assertEquals(JobState.PAUSED,loaded.jobs().get(id).state());assertEquals(a.samples,loaded.jobs().get(id).cursor());
        assertEquals(e.save(),loaded.analyses().get(id).extended.save());
        a.extended=null;var legacy=WorldPrepSavedData.load(d.save(new CompoundTag(),null),null);assertNull(legacy.analyses().get(id).extended);
    }
    @Test void habitatClassificationUsesRealHeightWaterAndPredictedTagsInsteadOfWrongCurrentBiome() {
        var mountain=new MinecraftTerrainSampler.Sample(150,0,0,0,32,0);
        assertEquals(Habitat.MOUNTAIN,WorldPrepHabitatRuntime.classify(mountain,0,false,true,false).habitat());
        var river=new MinecraftTerrainSampler.Sample(70,3,0,0,1,0);
        assertEquals(Habitat.RIVER,WorldPrepHabitatRuntime.classify(river,3,false,false,true).habitat());
        assertEquals(Habitat.DEEP_MARINE,WorldPrepHabitatRuntime.classify(river,21,false,true,false).habitat());
        assertEquals(Habitat.FOREST,WorldPrepHabitatRuntime.classify(new MinecraftTerrainSampler.Sample(64,0,1,1,32,0),0,true,false,false).habitat());
    }
}
