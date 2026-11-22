package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.ecology.ControlledOak;
import fr.proutlost.worldprep.ecology.VegetationPlanner;
import fr.proutlost.worldprep.persistence.BlockWorldPrepData;
import fr.proutlost.worldprep.persistence.BlockWorldPrepData.Change;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import java.util.LinkedHashMap;
import java.util.Map;

/** Whole footprint validated at admission; journalled APPLY remains incremental and reversible. */
public final class TreeMutationPlanner {
    public record Candidate(int chunkX,int chunkZ,Map<String,Change> changes){public Candidate{changes=Map.copyOf(changes);}}
    public static Candidate planChunk(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,int cx,int cz){
        var empty=new Candidate(cx,cz,Map.of());
        if(area.treeMode()==VegetationPlanner.TreeMode.NO_TREES||area.treeMode()==VegetationPlanner.TreeMode.MANUAL_ONLY)return empty;
        int x=ControlledOak.rootX(level.getSeed(),cx,cz),z=ControlledOak.rootZ(level.getSeed(),cx,cz);
        if(x-2<area.minX()||x+2>area.maxX()||z-2<area.minZ()||z+2>area.maxZ())return empty;
        var c=EcologyMutationPlanner.observe(level,area,x,z);var cfg=EcologyMutationPlanner.validateProfile(level);
        double density=cfg.forestDensity()*(c.forest()?1:.3)*c.moisture()*Math.max(.1,1-c.normalizedAltitude())*(c.temperature()<cfg.coldTemperature()?.8:1);
        double clearing=new SimplexNoise(RandomSource.create(level.getSeed())).getValue(x/128.0,z/128.0)*.5+.5;
        if(c.blocked()||c.waterDepth()!=0||c.surface()!=c.floor()||c.slope()>cfg.maxSlope()||c.normalizedAltitude()>.8||!VegetationPlanner.treeCandidate(level.getSeed(),cx,cz,area.treeMode(),density,clearing))return empty;
        var root=new BlockPos(x,c.floor()+1,z);if(root.getY()+4>=level.getMaxBuildHeight())return empty;
        var chunk=ServerExistingChunkAccess.requireBlock(level,area,x,c.floor(),z);
        if(!rootSupport(chunk.getBlockState(root.below()))||!level.canSeeSky(root))return empty;
        var changes=new LinkedHashMap<String,Change>();String metadata="TREE1|"+x+"|"+root.getY()+"|"+z+"|"+c.biome();
        for(var v:ControlledOak.SHAPE){
            var pos=root.offset(v.x(),v.y(),v.z());var target=ServerExistingChunkAccess.requireBlock(level,area,pos.getX(),pos.getY(),pos.getZ());var before=target.getBlockState(pos);
            if(!before.isAir()||before.hasBlockEntity()||data.protectedBlock(area.dimension(),pos.getX(),pos.getZ())||!target.getAllStarts().isEmpty()||!target.getAllReferences().isEmpty()||!MinecraftTerrainSampler.matchesMask(level,area.mask(),pos.getX(),pos.getZ(),1,1))return empty;
            changes.put(BlockWorldPrepData.posKey(pos.getX(),pos.getY(),pos.getZ()),new Change(pos.getX(),pos.getY(),pos.getZ(),BlockMutationRuntime.stateString(before),v.blockState(),metadata));
        }
        return new Candidate(cx,cz,changes);
    }
    public static boolean safe(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,Change change){
        String[] fields=change.province().split("\\|");if(fields.length!=5||!fields[0].equals("TREE1"))return false;
        var root=new BlockPos(Integer.parseInt(fields[1]),Integer.parseInt(fields[2]),Integer.parseInt(fields[3]));
        var base=ServerExistingChunkAccess.requireBlock(level,area,root.getX(),root.getY()-1,root.getZ());
        if(!rootSupport(base.getBlockState(root.below()))||!base.getNoiseBiome(QuartPos.fromBlock(root.getX()),QuartPos.fromBlock(root.getY()-1),QuartPos.fromBlock(root.getZ())).unwrapKey().orElseThrow().location().toString().equals(fields[4]))return false;
        boolean member=false;
        for(var v:ControlledOak.SHAPE){
            var pos=root.offset(v.x(),v.y(),v.z());var chunk=ServerExistingChunkAccess.requireBlock(level,area,pos.getX(),pos.getY(),pos.getZ());var current=chunk.getBlockState(pos);
            if(data.protectedBlock(area.dimension(),pos.getX(),pos.getZ())||current.hasBlockEntity()||!chunk.getAllStarts().isEmpty()||!chunk.getAllReferences().isEmpty()||!MinecraftTerrainSampler.matchesMask(level,area.mask(),pos.getX(),pos.getZ(),1,1))return false;
            // Earlier slices of this exact tree may already have installed their owned voxel.
            if(!current.isAir()&&!BlockMutationRuntime.stateString(current).equals(v.blockState()))return false;
            if(pos.getX()==change.x()&&pos.getY()==change.y()&&pos.getZ()==change.z())member=current.isAir()&&v.blockState().equals(change.applied());
        }
        return member;
    }
    private static boolean rootSupport(net.minecraft.world.level.block.state.BlockState state){return state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.DIRT)||state.is(Blocks.PODZOL)||state.is(Blocks.COARSE_DIRT)||state.is(Blocks.ROOTED_DIRT);}
    private TreeMutationPlanner(){}
}
