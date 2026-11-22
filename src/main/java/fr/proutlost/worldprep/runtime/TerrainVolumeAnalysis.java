package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.analysis.TerrainAnalysisExtras;
import fr.proutlost.worldprep.analysis.TerrainAnalysisExtras.*;
import fr.proutlost.worldprep.ecology.VegetationPlanner;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;

/** Read-only ANALYZE extension. Counts actual host blocks, estimates sampled surface suitability.
 * A slice debits up to64 block probes per aligned4x4x4 cell against the existing budget.
 * Existing terrain-sampling units/cursor and every mutation/admission rule remain unchanged.
 */
public final class TerrainVolumeAnalysis {
    // Only immutable tag/resource cohorts are cached. Profile/seed/protections and geometry
    // still pass the original current-input identity check every slice. Reload gets a new manager.
    private static final java.util.Map<net.minecraft.server.packs.resources.ResourceManager,java.util.Map<Object,String>> POLICIES=new java.util.WeakHashMap<>();
    private TerrainVolumeAnalysis(){}
    public static Geometry geometry(ServerLevel level,WorldPrepSavedData.Area area) {
        return new Geometry(area.minX(),level.getMinBuildHeight(),area.minZ(),area.maxX(),level.getMaxBuildHeight(),area.maxZ());
    }
    public static String policyIdentity(ServerLevel level) {
        var registry=level.registryAccess().registryOrThrow(Registries.BIOME);
        var cohort=POLICIES.computeIfAbsent(level.getServer().getResourceManager(),key->new java.util.IdentityHashMap<>());
        var cached=cohort.get(registry);if(cached!=null)return cached;
        var parts=new java.util.ArrayList<String>();
        parts.add("analysis-exact-host-quart4-and-sampled-suitability-v1");
        parts.add(EcologyMutationPlanner.tagFingerprint(level));
        parts.add(BlockMutationRuntime.hostTagFingerprintForAnalysis());
        parts.add(EcologyMutationPlanner.validateProfile(level).toString());
        for(var tag:java.util.List.of(BiomeTags.IS_FOREST,BiomeTags.IS_OCEAN,BiomeTags.IS_RIVER))
            parts.add(tag.location()+"="+registry.getTag(tag).map(values->values.stream().map(h->h.unwrapKey().orElseThrow().location().toString()).sorted().toList().toString()).orElse(""));
        var identity=fr.proutlost.worldprep.plan.PlanFingerprint.of(parts.toArray(String[]::new)).value();cohort.put(registry,identity);return identity;
    }
    public static Surface surface(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,
            MinecraftTerrainSampler.Sample terrain,String predictedBiome,int x,int z,long representedColumns) {
        var registry=level.registryAccess().registryOrThrow(Registries.BIOME);
        var predicted=registry.getHolder(ResourceKey.create(Registries.BIOME,ResourceLocation.parse(predictedBiome)));
        String habitat=predicted.isEmpty()?"UNKNOWN_REGISTRY":WorldPrepHabitatRuntime.classify(terrain,terrain.waterDepth(),
            predicted.orElseThrow().is(BiomeTags.IS_FOREST),predicted.orElseThrow().is(BiomeTags.IS_OCEAN),predicted.orElseThrow().is(BiomeTags.IS_RIVER)).habitat().name();
        boolean flora=false,trees=false,aquatic=false;
        int y=terrain.surfaceY();
        if(y>=level.getMinBuildHeight()&&y+2<level.getMaxBuildHeight()) {
            var chunk=ServerExistingChunkAccess.requireBlock(level,area,x,y,z);var floor=new BlockPos(x,y,z);var above=floor.above();
            boolean protect=data.protectedBlock(area.dimension(),x,z)||!chunk.getAllStarts().isEmpty()||!chunk.getAllReferences().isEmpty();
            var ground=chunk.getBlockState(floor);var head=chunk.getBlockState(above);var cfg=EcologyMutationPlanner.validateProfile(level);
            boolean dry=terrain.waterDepth()==0&&terrain.slope()<=cfg.maxSlope()&&!protect&&!ground.hasBlockEntity()
                &&head.isAir()&&level.canSeeSky(above);
            flora=dry&&Blocks.SHORT_GRASS.defaultBlockState().canSurvive(level,above);
            boolean root=ground.is(Blocks.GRASS_BLOCK)||ground.is(Blocks.DIRT)||ground.is(Blocks.PODZOL)||ground.is(Blocks.COARSE_DIRT)||ground.is(Blocks.ROOTED_DIRT);
            double altitude=(y-level.getMinBuildHeight())/(double)(level.getMaxBuildHeight()-level.getMinBuildHeight());
            trees=dry&&root&&altitude<=.8&&area.treeMode()!=VegetationPlanner.TreeMode.NO_TREES&&area.treeMode()!=VegetationPlanner.TreeMode.MANUAL_ONLY;
            aquatic=!protect&&terrain.waterDepth()>=2&&terrain.waterDepth()<=16
                &&head.equals(Blocks.WATER.defaultBlockState())&&chunk.getBlockState(above.above()).equals(Blocks.WATER.defaultBlockState())
                &&Blocks.SEAGRASS.defaultBlockState().canSurvive(level,above);
        }
        return new Surface(representedColumns,flora,trees,aquatic,habitat);
    }
    public static void slice(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Job job,
            WorldPrepSavedData.Analysis analysis,int budget) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("Analysis server thread required");
        var area=WorldPrepRuntime.requireArea(data,job.area());var extra=analysis.extended;
        if(extra==null||!extra.geometry.equals(geometry(level,area)))throw new IllegalStateException("Analysis volume identity changed");
        extra.requirePrefix(analysis.samples);
        // Keep the existing probe budget, not256 groups of64. Preserve the atomic-cell minimum.
        int remaining=budget>0?Math.max(1,budget/64):0;
        while(remaining-->0&&extra.volumeCells<extra.geometry.cells()) {
            var cell=extra.geometry.at(extra.volumeCells);int cx=cell.minX()>>4,cz=cell.minZ()>>4;
            if(level.getChunkSource().getChunkNow(cx,cz)==null&&!ExistingChunkLoader.ensure(level,job.id(),cx,cz))return;
            var chunk=ServerExistingChunkAccess.requireBlock(level,area,cell.minX(),cell.minY(),cell.minZ());
            boolean structure=!chunk.getAllStarts().isEmpty()||!chunk.getAllReferences().isEmpty();
            long geo=0,ore=0,eligibleGeo=0,eligibleOre=0,protectedBlocks=0;
            var pos=new BlockPos.MutableBlockPos();
            for(int dz=0;dz<=cell.maxZ()-cell.minZ();dz++)for(int dx=0;dx<=cell.maxX()-cell.minX();dx++) {
                int z=cell.minZ()+dz,x=cell.minX()+dx;
                boolean protectedColumn=structure||data.protectedBlock(area.dimension(),x,z);
                for(int dy=0;dy<=cell.maxY()-cell.minY();dy++) {
                    int y=cell.minY()+dy;
                    var state=chunk.getBlockState(pos.set(x,y,z));
                    boolean geology=BlockMutationRuntime.geologicalHostForAnalysis(state),ores=BlockMutationRuntime.oreHostForAnalysis(state);
                    if(geology)geo++;if(ores)ore++;if(protectedColumn)protectedBlocks++;
                    if(!protectedColumn&&!state.hasBlockEntity()){if(geology)eligibleGeo++;if(ores)eligibleOre++;}
                }
            }
            extra.appendVolume(new Volume(cell.blocks(),geo,ore,eligibleGeo,eligibleOre,protectedBlocks));data.changed();
        }
    }
}
