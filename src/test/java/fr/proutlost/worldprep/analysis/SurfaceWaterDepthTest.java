package fr.proutlost.worldprep.analysis;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceWaterDepthTest {
    @Test void dryFlowersSnowAndCanopiesAreNotWaterFromHeightGapAlone(){
        for(int top:new int[]{61,62,90})assertEquals(0,SurfaceWaterDepth.fromLayers(60,top,false,false));
    }
    @Test void realSurfaceWaterRetainsShallowAndDeepPhysicalDepths(){
        for(int depth:new int[]{1,4,6,7,20,21,64})
            assertEquals(depth,SurfaceWaterDepth.fromLayers(-60,-60+depth,true,true));
    }
    @Test void waterBelowNonWaterTopLayerDoesNotIncludeTheTopLayer(){
        assertEquals(1,SurfaceWaterDepth.fromLayers(59,61,false,true));
        assertEquals(6,SurfaceWaterDepth.fromLayers(54,61,false,true));
        assertEquals(21,SurfaceWaterDepth.fromLayers(39,61,false,true));
    }
    @Test void zeroOrNegativeHeightGapCannotDeclareWaterDepth(){
        assertEquals(0,SurfaceWaterDepth.fromLayers(60,60,true,true));
        assertEquals(0,SurfaceWaterDepth.fromLayers(61,60,false,true));
    }
    @Test void fluidAtTheFloorDoesNotCreateAColumnAboveTheFloor(){
        assertEquals(0,SurfaceWaterDepth.fromLayers(60,61,false,true));
    }
    @Test void explicitDryTerrainBelowSeaLevelStaysDryInPureAnalysis(){
        var dry=TerrainAnalysis.analyze(1,1,63,new int[]{60},new boolean[]{false});
        assertEquals(0,dry.waterDepth(0,0));assertEquals(TerrainAnalysis.Kind.LOWLAND,dry.classify(0,0));
    }
    @Test void explicitlyObservedWaterStillClassifiesAsShallowMarine(){
        var wet=TerrainAnalysis.analyze(1,1,63,new int[]{60},new boolean[]{true});
        assertEquals(3,wet.waterDepth(0,0));assertEquals(TerrainAnalysis.Kind.SHALLOW_MARINE,wet.classify(0,0));
    }
    @Test void mixedGridDoesNotTurnDryNeighborsIntoMarineWater(){
        int[] heights={60,60,60,60,60,60,60,60,60};boolean[] water={false,false,false,false,true,false,false,false,false};
        var mixed=TerrainAnalysis.analyze(3,3,63,heights,water);
        for(int z=0;z<3;z++)for(int x=0;x<3;x++){
            boolean wet=x==1&&z==1;assertEquals(wet?3:0,mixed.waterDepth(x,z));
            if(!wet)assertNotEquals(TerrainAnalysis.Kind.SHALLOW_MARINE,mixed.classify(x,z));
        }
    }
}
