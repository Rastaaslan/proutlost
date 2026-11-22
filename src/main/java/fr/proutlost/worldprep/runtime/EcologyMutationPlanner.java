package fr.proutlost.worldprep.runtime;

import com.google.gson.JsonParser;
import fr.proutlost.worldprep.analysis.TerrainAnalysis.Kind;
import fr.proutlost.worldprep.ecology.AquaticPlanner;
import fr.proutlost.worldprep.ecology.SoilPlanner;
import fr.proutlost.worldprep.ecology.VegetationPlanner;
import fr.proutlost.worldprep.geology.GeologyPlanner;
import fr.proutlost.worldprep.persistence.BlockWorldPrepData;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import java.nio.charset.StandardCharsets;

/** Existing pure planners connected to loaded vanilla terrain and a closed reversible palette. */
public final class EcologyMutationPlanner {
    public static final String VERSION="vanilla-environment-1";
    private static final TagKey<Block> SOIL=BlockTags.create(ResourceLocation.parse("proutlost:soil_replaceable"));
    private static final TagKey<Block> ROCK=BlockTags.create(ResourceLocation.parse("proutlost:geology_replaceable"));
    public record Settings(double floraDensity,double forestDensity,double maxSlope,double coldTemperature) {}
    private static Settings settings;
    public static synchronized Settings validateProfile(){
        if(settings!=null)return settings;
        try(var in=EcologyMutationPlanner.class.getResourceAsStream("/data/proutlost/worldprep/profiles/river_test.json")){
            if(in==null)throw new IllegalStateException("Missing environment profile");
            return settings=parseEnvironmentProfile(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }catch(Exception ex){throw new IllegalStateException("Environment profile unavailable or invalid",ex);}
    }
    /** Runtime ecology must use the same active data-pack cohort as biome identity. */
    public static Settings validateProfile(ServerLevel level){return BiomeProfileRuntime.environment(level);}
    static Settings parseEnvironmentProfile(String text){
            var e=JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("environment");
            if(e==null||e.get("schema").getAsInt()!=1)throw new IllegalStateException("Unknown environment profile schema");
            double flora=e.get("flora_density").getAsDouble(),forest=e.get("forest_density").getAsDouble(),slope=e.get("max_slope").getAsDouble(),cold=e.get("cold_temperature").getAsDouble();
            if(!Double.isFinite(flora)||flora<0||flora>1||!Double.isFinite(forest)||forest<0||forest>1||!Double.isFinite(slope)||slope<0||slope>16||!Double.isFinite(cold)||cold<0||cold>2)throw new IllegalStateException("Invalid environment profile bounds");
            return new Settings(flora,forest,slope,cold);
    }
    public static boolean supports(String pass){return pass.equals("SOILS")||pass.equals("FLORA")||pass.equals("TREES")||pass.equals("AQUATIC");}
    /** One immutable column, cached by the existing incremental scan; it retains no world/chunk. */
    public record Column(int floor,int surface,int waterDepth,float slope,float roughness,int coast,int inlandDistance,
            double normalizedAltitude,double moisture,float temperature,boolean forest,String biome,long noise,
            SoilPlanner.Result soil,AquaticPlanner.Result aquatic,boolean blocked) {}
    public static Column observe(ServerLevel level,WorldPrepSavedData.Area area,int x,int z){
        validateProfile(level);var chunk=ServerExistingChunkAccess.requireBlock(level,area,x,level.getMinBuildHeight(),z);
        var terrain=MinecraftTerrainSampler.sample(level,x,z);int surface=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x,z),floor=chunk.getHeight(Heightmap.Types.OCEAN_FLOOR,x,z);
        int top=surface;if(top>floor&&!chunk.getFluidState(new BlockPos(x,top,z)).is(FluidTags.WATER))top--;
        int water=top>floor&&chunk.getFluidState(new BlockPos(x,top,z)).is(FluidTags.WATER)?top-floor:0;
        int coast=MinecraftTerrainSampler.matchesMask(level,WorldPrepSavedData.AreaMask.COAST,x,z,1,1)?0:Math.max(8,terrain.coastDistance());
        var inland=MinecraftTerrainSampler.inlandWater(level,x,z);
        var biome=chunk.getNoiseBiome(QuartPos.fromBlock(x),QuartPos.fromBlock(Math.max(level.getMinBuildHeight(),floor)),QuartPos.fromBlock(z));
        String biomeId=biome.unwrapKey().orElseThrow().location().toString();boolean forest=biome.is(BiomeTags.IS_FOREST);
        double altitude=Math.max(0,Math.min(1,(floor-level.getMinBuildHeight())/(double)Math.max(1,level.getMaxBuildHeight()-level.getMinBuildHeight())));
        double moisture=water>0?1:inland.observedDistance()>=0?.9:coast<=4?.75:forest?.65:.35;
        long noise=GeologyPlanner.mix(level.getSeed()^x*341873128712L^z*132897987541L);
        Kind kind=water>20?Kind.DEEP_MARINE:water>6?Kind.MARINE:water>0?Kind.SHALLOW_MARINE:
            floor>=level.getSeaLevel()+70?Kind.MOUNTAIN:terrain.slope()>=8?Kind.CLIFF:
            inland.observedDistance()>=0?Kind.WET_LOWLAND:coast<=4?(terrain.slope()>3?Kind.STONY_COAST:Kind.BEACH):
            floor>=level.getSeaLevel()+30?Kind.ROCKY_HIGHLAND:forest?Kind.FORESTABLE_LOWLAND:Kind.OPEN_LOWLAND;
        return new Column(floor,surface,water,terrain.slope(),terrain.roughness(),coast,inland.observedDistance(),altitude,moisture,biome.value().getBaseTemperature(),forest,biomeId,noise,SoilPlanner.plan(kind,moisture,noise),AquaticPlanner.plan(water,terrain.slope(),coast,noise),!chunk.getAllStarts().isEmpty()||!chunk.getAllReferences().isEmpty());
    }
    public static BlockWorldPrepData.Change change(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,String pass,int x,int y,int z,Column c){
        var chunk=ServerExistingChunkAccess.requireBlock(level,area,x,y,z);var pos=new BlockPos(x,y,z);var before=chunk.getBlockState(pos);
        if(c.blocked||data.protectedBlock(area.dimension(),x,z)||before.hasBlockEntity())return null;
        var cfg=validateProfile(level);BlockState target=null;
        if(pass.equals("SOILS")&&c.waterDepth==0&&c.surface==c.floor&&c.soil.depth()>0&&y<=c.floor&&y>c.floor-c.soil.depth()&&(before.is(SOIL)||before.is(ROCK))){
            target=switch(c.soil.type()){
                case ROCK->BuiltInRegistries.BLOCK.get(ResourceLocation.parse(GeologyPlanner.replacement(level.getSeed(),x,y,z,GeologyPlanner.province(level.getSeed(),x,y,z)))).defaultBlockState();
                case SAND->Blocks.SAND.defaultBlockState();case GRAVEL->Blocks.GRAVEL.defaultBlockState();case MUD->Blocks.MUD.defaultBlockState();
                case DIRT->(y==c.floor?(c.forest?Blocks.PODZOL:Blocks.GRASS_BLOCK):Blocks.DIRT).defaultBlockState();case NONE->null;
            };
        }else if(pass.equals("FLORA")&&c.waterDepth==0&&c.surface==c.floor&&y==c.floor+1&&before.isAir()&&c.slope<=cfg.maxSlope&&level.canSeeSky(pos)&&VegetationPlanner.flora(level.getSeed(),x,z,cfg.floraDensity,(.35+c.moisture)*Math.max(.1,1-c.normalizedAltitude))){
            double v=VegetationPlanner.unit(level.getSeed(),x,z,99);
            target=(c.temperature<cfg.coldTemperature?(v<.08?Blocks.SWEET_BERRY_BUSH:Blocks.FERN):c.moisture>.8&&v<.2?Blocks.BLUE_ORCHID:v<.08?Blocks.DANDELION:v<.16?Blocks.POPPY:Blocks.SHORT_GRASS).defaultBlockState();
        }else if(pass.equals("AQUATIC")&&c.waterDepth>0){
            if(y==c.floor&&(before.is(SOIL)||before.is(ROCK)))target=switch(c.aquatic.substrate()){case SAND->Blocks.SAND.defaultBlockState();case GRAVEL->Blocks.GRAVEL.defaultBlockState();case STONE->Blocks.STONE.defaultBlockState();};
            else if(y==c.floor+1&&c.aquatic.vegetation()&&c.coast<=32&&c.temperature>=.15&&c.temperature<=1.5&&before.equals(Blocks.WATER.defaultBlockState())&&chunk.getBlockState(pos.above()).equals(Blocks.WATER.defaultBlockState()))target=(c.waterDepth>=6&&c.temperature>=cfg.coldTemperature&&Math.floorMod(c.noise,3)==0?Blocks.KELP:Blocks.SEAGRASS).defaultBlockState();
        }
        if(target==null||target.equals(before))return null;
        String metadata="ENV1|"+pass+"|"+c.biome+"|"+c.floor+"|"+c.waterDepth+"|"+c.soil.depth();
        var change=new BlockWorldPrepData.Change(x,y,z,BlockMutationRuntime.stateString(before),BlockMutationRuntime.stateString(target),metadata);
        return safe(level,area,pass,change,target)?change:null;
    }
    /** Commit checks use pinned column facts, allowing this job's earlier owned soil/substrate writes. */
    public static boolean safe(ServerLevel level,WorldPrepSavedData.Area area,String pass,BlockWorldPrepData.Change change,BlockState target){
        var pos=new BlockPos(change.x(),change.y(),change.z());var chunk=ServerExistingChunkAccess.requireBlock(level,area,pos.getX(),pos.getY(),pos.getZ());
        if(!chunk.getAllStarts().isEmpty()||!chunk.getAllReferences().isEmpty()||pos.getY()<=level.getMinBuildHeight()||pos.getY()+1>=level.getMaxBuildHeight())return false;
        var current=chunk.getBlockState(pos);if(current.hasBlockEntity())return false;
        String[] m=change.province().split("\\|");if(m.length!=6||!m[0].equals("ENV1")||!m[1].equals(pass))return false;
        int floor=Integer.parseInt(m[3]),water=Integer.parseInt(m[4]),depth=Integer.parseInt(m[5]);
        if(!chunk.getNoiseBiome(QuartPos.fromBlock(pos.getX()),QuartPos.fromBlock(Math.max(level.getMinBuildHeight(),floor)),QuartPos.fromBlock(pos.getZ())).unwrapKey().orElseThrow().location().toString().equals(m[2]))return false;
        if(pass.equals("SOILS")){
            if(water!=0||depth<1||depth>4||pos.getY()>floor||pos.getY()<=floor-depth||chunk.getHeight(Heightmap.Types.WORLD_SURFACE,pos.getX(),pos.getZ())!=floor||!(current.is(SOIL)||current.is(ROCK)))return false;
            boolean allowed=target.is(Blocks.GRASS_BLOCK)||target.is(Blocks.PODZOL)||target.is(Blocks.DIRT)||target.is(Blocks.MUD)||target.is(Blocks.SAND)||target.is(Blocks.GRAVEL)||target.is(Blocks.STONE)||target.is(Blocks.DEEPSLATE)||target.is(Blocks.TUFF)||target.is(Blocks.GRANITE)||target.is(Blocks.DIORITE)||target.is(Blocks.ANDESITE);
            return allowed&&(!(target.is(Blocks.SAND)||target.is(Blocks.GRAVEL))||chunk.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP));
        }
        var support=chunk.getBlockState(pos.below());
        if(pass.equals("FLORA"))return water==0&&pos.getY()==floor+1&&current.isAir()&&support.is(SOIL)&&
            (target.is(Blocks.SHORT_GRASS)||target.is(Blocks.FERN)||target.is(Blocks.SWEET_BERRY_BUSH)||target.is(Blocks.DANDELION)||target.is(Blocks.POPPY)||target.is(Blocks.BLUE_ORCHID))&&target.canSurvive(level,pos);
        if(pass.equals("AQUATIC")){
            int top=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,pos.getX(),pos.getZ());if(top>floor&&!chunk.getFluidState(new BlockPos(pos.getX(),top,pos.getZ())).is(FluidTags.WATER))top--;
            if(water<=0||top-floor!=water||!chunk.getFluidState(new BlockPos(pos.getX(),top,pos.getZ())).is(FluidTags.WATER))return false;
            if(pos.getY()==floor)return(current.is(SOIL)||current.is(ROCK))
                    &&(target.is(Blocks.SAND)||target.is(Blocks.GRAVEL)||target.is(Blocks.STONE))
                    &&(!(target.is(Blocks.SAND)||target.is(Blocks.GRAVEL))
                        ||chunk.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP));
            return pos.getY()==floor+1&&water>=2&&water<=16&&current.equals(Blocks.WATER.defaultBlockState())&&chunk.getBlockState(pos.above()).equals(Blocks.WATER.defaultBlockState())&&(target.is(Blocks.KELP)||target.is(Blocks.SEAGRASS))&&target.canSurvive(level,pos);
        }
        return false;
    }
    public static String tagFingerprint(ServerLevel level){
        String soils=PlanFingerprint.of(BuiltInRegistries.BLOCK.stream().filter(b->b.defaultBlockState().is(SOIL)||b.defaultBlockState().is(ROCK)).map(b->BuiltInRegistries.BLOCK.getKey(b).toString()+":"+b.defaultBlockState().is(SOIL)+":"+b.defaultBlockState().is(ROCK)).sorted().toArray(String[]::new)).value();
        var registry=level.registryAccess().registryOrThrow(Registries.BIOME);
        return PlanFingerprint.of(soils,registry.getTag(BiomeTags.IS_FOREST).map(values->PlanFingerprint.of(values.stream().map(h->h.unwrapKey().orElseThrow().location().toString()).sorted().toArray(String[]::new)).value()).orElse("")).value();
    }
    /** Legacy bounded immutable prefetch callers use the same ENV1 observation and commit checks. */
    public static void requireBounded(WorldPrepSavedData.Area area){
        long width=(long)area.maxX()-area.minX()+1,depth=(long)area.maxZ()-area.minZ()+1;
        if(width<=0||depth<=0||width>256||depth>256||width*depth>256)
            throw new IllegalArgumentException("Ecology preview requires tiles of at most 256 columns");
    }
    public static BlockWorldPrepData.Change change(ServerLevel level,WorldPrepSavedData data,
            WorldPrepSavedData.Area area,String pass,int x,int y,int z){
        return change(level,data,area,pass,x,y,z,observe(level,area,x,z));
    }
    private EcologyMutationPlanner(){}
}
