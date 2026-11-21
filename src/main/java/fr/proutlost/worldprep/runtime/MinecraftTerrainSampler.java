package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/** Samples real loaded/generated chunks. Call only from the server thread and in bounded batches. */
public final class MinecraftTerrainSampler {
    public record Sample(int surfaceY,int waterDepth,float slope,float roughness,int coastDistance,long signature) { public boolean water(){return waterDepth>0;} }
    public static Sample sample(ServerLevel level,int blockX,int blockZ){int center=floor(level,blockX,blockZ),water=waterDepth(level,blockX,blockZ),lo=center,hi=center,maxDelta=0;for(int dz=-4;dz<=4;dz+=4)for(int dx=-4;dx<=4;dx+=4){int h=floor(level,blockX+dx,blockZ+dz);lo=Math.min(lo,h);hi=Math.max(hi,h);maxDelta=Math.max(maxDelta,Math.abs(h-center));}int coast=coastDistance(level,blockX,blockZ,water>0,32);long sig=1469598103934665603L;for(long v:new long[]{center,water,maxDelta,hi-lo,coast}){sig^=v;sig*=1099511628211L;}return new Sample(center,water,maxDelta,hi-lo,coast,sig);}
    private static int floor(ServerLevel l,int x,int z){LevelChunk c=ServerExistingChunkAccess.requireSample(l,x,z);int surface=c.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);int floor=c.getHeight(Heightmap.Types.OCEAN_FLOOR,x,z);return surface>floor?floor:surface;}
    private static int waterDepth(ServerLevel level,int x,int z){
        LevelChunk chunk=ServerExistingChunkAccess.requireSample(level,x,z);
        int surface=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);
        int floor=chunk.getHeight(Heightmap.Types.OCEAN_FLOOR,x,z);
        var pos=new net.minecraft.core.BlockPos.MutableBlockPos();pos.set(x,surface,z);
        boolean topWater=chunk.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER);
        boolean belowWater=surface>floor&&!topWater&&
            chunk.getFluidState(pos.set(x,surface-1,z)).is(net.minecraft.tags.FluidTags.WATER);
        return fr.proutlost.worldprep.analysis.SurfaceWaterDepth.fromLayers(floor,surface,topWater,belowWater);
    }
    private static int coastDistance(ServerLevel l,int x,int z,boolean water,int max){for(int r=0;r<=max;r+=4){for(int d=-r;d<=r;d+=4)if((waterDepth(l,x+d,z-r)>0)!=water||(waterDepth(l,x+d,z+r)>0)!=water||(waterDepth(l,x-r,z+d)>0)!=water||(waterDepth(l,x+r,z+d)>0)!=water)return r;}return max;}
    /** A block-complete surface-water grid within 4 blocks: finite-window evidence, never a global nearest-water claim. */
    public record InlandWater(int observedDistance,boolean unresolvedBoundary,boolean noWater) {}
    public static InlandWater inlandWater(ServerLevel level,int x,int z){
        final int width=9,center=4,spacing=1;boolean[] water=new boolean[width*width],seen=new boolean[water.length];int[] surface=new int[water.length];
        for(int dz=0;dz<width;dz++)for(int dx=0;dx<width;dx++){
            int bx=x+(dx-center)*spacing,bz=z+(dz-center)*spacing,i=dz*width+dx;var chunk=ServerExistingChunkAccess.requireSample(level,bx,bz);
            int y=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,bx,bz),floor=chunk.getHeight(Heightmap.Types.OCEAN_FLOOR,bx,bz);
            // A lily pad may sit one block above the actual water surface. Height differences alone are not water.
            if(y>floor&&!chunk.getFluidState(new net.minecraft.core.BlockPos(bx,y,bz)).is(net.minecraft.tags.FluidTags.WATER))y--;
            surface[i]=y;water[i]=y>floor&&chunk.getFluidState(new net.minecraft.core.BlockPos(bx,y,bz)).is(net.minecraft.tags.FluidTags.WATER);
        }
        int nearest=Integer.MAX_VALUE;boolean unresolved=false,any=false;var queue=new java.util.ArrayDeque<Integer>();var component=new java.util.ArrayList<Integer>();
        for(int start=0;start<water.length;start++)if(water[start]&&!seen[start]){
            any=true;component.clear();queue.add(start);seen[start]=true;boolean open=false;
            while(!queue.isEmpty()){
                int i=queue.removeFirst(),cx=i%width,cz=i/width;component.add(i);open|=cx==0||cz==0||cx==width-1||cz==width-1;
                for(int direction=0;direction<4;direction++){
                    int nx=cx+(direction==0?-1:direction==1?1:0),nz=cz+(direction==2?-1:direction==3?1:0);
                    if(nx<0||nz<0||nx>=width||nz>=width)continue;int next=nz*width+nx;
                    if(water[next]&&!seen[next]){seen[next]=true;queue.add(next);}
                }
            }
            for(int i:component){
                // Above the configured marine surface is physical inland-water evidence even for an open river.
                if(!open||surface[i]>=level.getSeaLevel())nearest=Math.min(nearest,spacing*Math.max(Math.abs(i%width-center),Math.abs(i/width-center)));
                else unresolved=true;
            }
        }
        return new InlandWater(nearest==Integer.MAX_VALUE?-1:nearest,unresolved,!any);
    }
    /** All footprint columns must belong to the mask. Coast is a <=4-block physical water/land boundary on either side. */
    public static boolean matchesMask(ServerLevel level,fr.proutlost.worldprep.persistence.WorldPrepSavedData.AreaMask mask,int x,int z,int width,int depth){
        if(mask==fr.proutlost.worldprep.persistence.WorldPrepSavedData.AreaMask.FULL_AREA)return true;
        if(width<1||depth<1||width>4||depth>4)throw new IllegalArgumentException("Mask footprint must be bounded by one biome cell");
        boolean coastal=mask==fr.proutlost.worldprep.persistence.WorldPrepSavedData.AreaMask.COAST||mask==fr.proutlost.worldprep.persistence.WorldPrepSavedData.AreaMask.LAND_AND_COAST;
        int pad=coastal?4:0,w=width+pad*2,d=depth+pad*2;boolean[] water=new boolean[w*d];
        for(int dz=0;dz<d;dz++)for(int dx=0;dx<w;dx++){
            int bx=x+dx-pad,bz=z+dz-pad;var chunk=ServerExistingChunkAccess.requireSample(level,bx,bz);
            int y=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,bx,bz),floor=chunk.getHeight(Heightmap.Types.OCEAN_FLOOR,bx,bz);
            if(y>floor&&!chunk.getFluidState(new net.minecraft.core.BlockPos(bx,y,bz)).is(net.minecraft.tags.FluidTags.WATER))y--;
            water[dz*w+dx]=y>floor&&chunk.getFluidState(new net.minecraft.core.BlockPos(bx,y,bz)).is(net.minecraft.tags.FluidTags.WATER);
        }
        for(int dz=0;dz<depth;dz++)for(int dx=0;dx<width;dx++){
            boolean wet=water[(dz+pad)*w+dx+pad],coast=false;
            if(coastal)for(int oz=-4;oz<=4&&!coast;oz++)for(int ox=-4;ox<=4;ox++)if(water[(dz+pad+oz)*w+dx+pad+ox]!=wet){coast=true;break;}
            if(!mask.includes(wet,coast))return false;
        }
        return true;
    }
    private MinecraftTerrainSampler(){}
}
