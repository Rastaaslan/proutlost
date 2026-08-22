package fr.proutlost.worldprep.runtime;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/** Samples real loaded/generated chunks. Call only from the server thread and in bounded batches. */
public final class MinecraftTerrainSampler {
    public record Sample(int surfaceY,int waterDepth,float slope,float roughness,int coastDistance,long signature) { public boolean water(){return waterDepth>0;} }
    public static Sample sample(ServerLevel level,int blockX,int blockZ){int center=floor(level,blockX,blockZ),water=waterDepth(level,blockX,blockZ),lo=center,hi=center,maxDelta=0;for(int dz=-4;dz<=4;dz+=4)for(int dx=-4;dx<=4;dx+=4){int h=floor(level,blockX+dx,blockZ+dz);lo=Math.min(lo,h);hi=Math.max(hi,h);maxDelta=Math.max(maxDelta,Math.abs(h-center));}int coast=coastDistance(level,blockX,blockZ,water>0,32);long sig=1469598103934665603L;for(long v:new long[]{center,water,maxDelta,hi-lo,coast}){sig^=v;sig*=1099511628211L;}return new Sample(center,water,maxDelta,hi-lo,coast,sig);}
    private static int floor(ServerLevel l,int x,int z){var c=l.getChunk(x>>4,z>>4);int surface=c.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);int floor=c.getHeight(Heightmap.Types.OCEAN_FLOOR,x,z);return surface>floor?floor:surface;}
    private static int waterDepth(ServerLevel l,int x,int z){var c=l.getChunk(x>>4,z>>4);int surface=c.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);int floor=c.getHeight(Heightmap.Types.OCEAN_FLOOR,x,z);return Math.max(0,surface-floor);}
    private static int coastDistance(ServerLevel l,int x,int z,boolean water,int max){for(int r=0;r<=max;r+=4){for(int d=-r;d<=r;d+=4)if((waterDepth(l,x+d,z-r)>0)!=water||(waterDepth(l,x+d,z+r)>0)!=water||(waterDepth(l,x-r,z+d)>0)!=water||(waterDepth(l,x+r,z+d)>0)!=water)return r;}return max;}
    private MinecraftTerrainSampler(){}
}
