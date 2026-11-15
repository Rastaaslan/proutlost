package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.orchestration.SpawnReachability;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.BlockMutationRuntime;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.util.*;

/** Read-only, one column or persisted chunk per step. No spawn movement, tickets or save.
 * Caller supplies a normal-save boundary. Unknown floors and non-air clearance are refused.
 */
public final class NativeSpawnAudit implements AutoCloseable {
    private final NativeChunkWindow window;
    public void close(){window.close();}
    public record Spec(String profile,int x,int y,int z,int radius,int verticalRange,int minimumReachable) {
        public Spec {
            if(!Set.of("MAP","FIXTURE","CONTRA_S2").contains(profile)||radius<2||radius>4||verticalRange<0||verticalRange>3
                ||Math.abs((long)x)>29999990||Math.abs((long)z)>29999990||y< -2048||y>2047
                ||minimumReachable<5||minimumReachable>(2*radius+1)*(2*radius+1))throw new IllegalArgumentException("Explicit bounded spawn audit configuration required");
            if(profile.equals("MAP")&&(x!=4871||y!=75||z!=-1370))throw new IllegalArgumentException("Map spawn reference cannot move");
            if(profile.equals("CONTRA_S2")&&(x!=2048||y!=127||z!=2048))
                throw new IllegalArgumentException("Exact original Contra spawn coordinates cannot move");
        }
    }
    record Sample(int x,int y,int z,String state) {}
    record Receipt(int version,Spec configuration,String dimension,String samplesSha256,int samples,
                   List<String> diskChunks,int reachable,Set<String> exits,String status) {}
    private final ServerLevel level;
    private final WorldPrepSavedData.Area area;
    private final Spec spec;
    private final PhysicalStore store;
    private final List<Sample> samples=new ArrayList<>();
    private final List<SpawnReachability.Cell> cells=new ArrayList<>();
    private final TreeMap<String,List<Sample>> chunks=new TreeMap<>();
    private final List<String> verified=new ArrayList<>();
    private int column;
    private Iterator<Map.Entry<String,List<Sample>>> pending;
    private SpawnReachability.Result result;
    public NativeSpawnAudit(ServerLevel level,WorldPrepSavedData.Area area,Spec spec,PhysicalStore store)throws IOException {
        if(spec==null)throw new IOException("Spawn audit configuration missing; cannot qualify final map");
        NativePhysicalRuntime.requireThread(level);this.level=level;this.area=area;this.spec=spec;this.store=store;window=new NativeChunkWindow(level);
        if(spec.profile().equals("FIXTURE")&&net.neoforged.fml.loading.FMLLoader.isProduction())throw new IOException("Fixture spawn audit forbidden in production");
        if(spec.profile().equals("MAP")&&(!level.dimension().location().toString().equals("minecraft:overworld")
            ||!level.getSharedSpawnPos().equals(new BlockPos(spec.x(),spec.y(),spec.z()))))throw new IOException("Actual map spawn differs from fixed reference; never moved by audit");
        if(spec.profile().equals("CONTRA_S2")&&(
            !ContraV5SectorLineage.PARENT.equals(area.id())
            ||!"minecraft:overworld".equals(area.dimension())
            ||area.minX()!=16||area.minZ()!=16||area.maxX()!=4079||area.maxZ()!=4079
            ||level.getSeed()!=27594267L
            ||!level.dimension().location().toString().equals("minecraft:overworld")
            ||!level.getSharedSpawnPos().equals(new BlockPos(spec.x(),spec.y(),spec.z()))))
            throw new IOException("Native original Contra spawn does not match frozen world/area/seed");
        store.write("configuration",spec);
    }
    public boolean step()throws IOException {
        NativePhysicalRuntime.requireThread(level);
        if(!window.blocks(spec.x()-spec.radius(),spec.z()-spec.radius(),spec.x()+spec.radius(),spec.z()+spec.radius()))return false;
        int width=2*spec.radius()+1;
        if(column<width*width) {
            int dx=column%width-spec.radius(),dz=column/width-spec.radius();
            var observed=new HashMap<Integer,BlockState>();
            int low=spec.y()-spec.verticalRange()-1,high=spec.y()+spec.verticalRange()+2;
            for(int y=low;y<=high;y++) {
                int x=spec.x()+dx,z=spec.z()+dz;
                ServerExistingChunkAccess.requireBlock(level,area,x,y,z);
                var pos=new BlockPos(x,y,z);var state=level.getBlockState(pos);
                if(level.getBlockEntity(pos)!=null)throw new IOException("Spawn audit refuses BlockEntity-dependent terrain");
                observed.put(y,state);samples.add(new Sample(x,y,z,BlockMutationRuntime.stateString(state)));
            }
            Integer selected=null;
            for(int y=spec.y()-spec.verticalRange();y<=spec.y()+spec.verticalRange();y++) {
                if(stable(observed.get(y-1))&&walkableClearance(observed.get(y))
                    &&walkableClearance(observed.get(y+1))
                    &&walkableClearance(observed.get(y+2))) {
                    if(selected==null||Math.abs(y-spec.y())<Math.abs(selected-spec.y()))selected=y;
                }
            }
            if(selected!=null)cells.add(new SpawnReachability.Cell(dx,selected-spec.y(),dz));
            column++;return false;
        }
        if(pending==null) {
            try{result=SpawnReachability.verify(spec.radius(),spec.verticalRange(),spec.minimumReachable(),cells);}
            catch(RuntimeException refused){throw new IOException("Spawn playability refused: "+refused.getMessage(),refused);}
            for(var sample:samples)chunks.computeIfAbsent((sample.x()>>4)+","+(sample.z()>>4),k->new ArrayList<>()).add(sample);
            pending=chunks.entrySet().iterator();return false;
        }
        if(pending.hasNext()) {
            var chunk=pending.next();var first=chunk.getValue().getFirst();int cx=first.x()>>4,cz=first.z()>>4;
            var region=DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
            var nbt=ReadOnlyChunkNbt.readBounded(region,cx,cz);var sections=nbt.getList("sections",Tag.TAG_COMPOUND);
            if(sections.size()>64)throw new IOException("Spawn disk section bound");
            var decoded=new HashMap<Integer,PalettedContainer<BlockState>>();
            for(int i=0;i<sections.size();i++) {
                var section=sections.getCompound(i);if(!section.contains("block_states"))continue;
                var states=PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY,BlockState.CODEC,PalettedContainer.Strategy.SECTION_STATES,Blocks.AIR.defaultBlockState())
                    .parse(NbtOps.INSTANCE,section.getCompound("block_states")).getOrThrow();
                if(decoded.put((int)section.getByte("Y"),states)!=null)throw new IOException("Duplicate spawn chunk section");
            }
            var persistedEntities=nbt.getList("block_entities",Tag.TAG_COMPOUND);
            if(persistedEntities.size()>4096)throw new IOException("Spawn disk BlockEntity bound");
            var entityPositions=new HashSet<BlockPos>();
            for(int i=0;i<persistedEntities.size();i++) {
                var entity=persistedEntities.getCompound(i);
                entityPositions.add(new BlockPos(entity.getInt("x"),entity.getInt("y"),entity.getInt("z")));
            }
            for(var sample:chunk.getValue()) {
                ServerExistingChunkAccess.requireBlock(level,area,sample.x(),sample.y(),sample.z());
                var pos=new BlockPos(sample.x(),sample.y(),sample.z());var section=decoded.get(sample.y()>>4);
                if(entityPositions.contains(pos)||level.getBlockEntity(pos)!=null)throw new IOException("Spawn BlockEntity terrain refused on readback");
                var disk=section==null?Blocks.AIR.defaultBlockState():section.get(sample.x()&15,sample.y()&15,sample.z()&15);
                if(!sample.state().equals(BlockMutationRuntime.stateString(disk))||!sample.state().equals(BlockMutationRuntime.stateString(level.getBlockState(pos))))
                    throw new IOException("Spawn terrain changed/live-disk mismatch");
            }
            verified.add(chunk.getKey());return false;
        }
        if(!level.noCollision(null,new net.minecraft.world.phys.AABB(spec.x()+0.2,spec.y(),spec.z()+0.2,spec.x()+0.8,spec.y()+1.8,spec.z()+0.8)))
            throw new IOException("Actual spawn collision volume obstructed");
        store.write("samples",samples);
        store.write("verified",new Receipt(1,spec,area.dimension(),PhysicalStore.sha(store.readText("samples")),samples.size(),List.copyOf(verified),result.reachable(),result.exits(),"SPAWN_WALKING_AND_DISK_VERIFIED"));
        return true;
    }
    /** Only explicitly allowlisted walk-through vegetation, never generic
     * empty collision shapes: water, fire and unfamiliar vegetation stay refused.
     * Every block is bound to exact unchanged Anvil readback before qualification.
     */
    private static boolean walkableClearance(BlockState state) {
        return state.getFluidState().isEmpty()&&SpawnWalkabilityPolicy.clearance(
            net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).toString());
    }
    /** DIRT_PATH is an actual walkable 15/16 height support. */
    private static boolean stable(BlockState state) {
        return state.getFluidState().isEmpty()&&SpawnWalkabilityPolicy.footing(
            net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).toString());
    }
}
