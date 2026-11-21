package fr.proutlost.worldprep.analysis;

/** Surface-water depth from actual fluid observations, including a non-water top layer. */
public final class SurfaceWaterDepth {
    public static int fromLayers(int oceanFloorY,int worldSurfaceY,
            boolean surfaceIsWater,boolean belowSurfaceIsWater) {
        if(worldSurfaceY<=oceanFloorY)return 0;
        if(surfaceIsWater)return Math.subtractExact(worldSurfaceY,oceanFloorY);
        int below=worldSurfaceY-1;
        return belowSurfaceIsWater&&below>oceanFloorY?Math.subtractExact(below,oceanFloorY):0;
    }
    private SurfaceWaterDepth() {}
}
