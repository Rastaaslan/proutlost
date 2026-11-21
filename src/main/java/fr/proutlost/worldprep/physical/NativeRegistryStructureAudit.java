package fr.proutlost.worldprep.physical;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Independent disk/live inspection of registered native placement.
 * Exact sampled state remains the default. The sole normalization is a vanilla
 * flat rail changing only shape after an adjacent chunk supplies a reciprocal
 * rail connection; the literal before/after and disk/live neighbors are retained.
 * No placement or block write is performed by this auditor.
 */
public final class NativeRegistryStructureAudit implements AutoCloseable {
    public static final String SETTLEMENT_RULE="VANILLA_FLAT_RAIL_RECIPROCAL_NEIGHBORS_V1";
    public record RailNeighbor(int x,int y,int z,String direction,String disk,String live,
                               String supportDisk,String supportLive) {}
    public record SettledRail(int x,int y,int z,String expected,String actual,String rule,
                              List<RailNeighbor> neighbors) {
        public SettledRail {neighbors=List.copyOf(neighbors);}
    }
    public record Receipt(int version,UUID operation,String registryId,String nativePlanSha256,
                          int chunks,long changedBlocks,List<String> auditedChunks,String status,
                          String auditRule,List<SettledRail> settledRails) {
        public Receipt {
            auditedChunks=List.copyOf(auditedChunks);
            settledRails=settledRails==null?List.of():List.copyOf(settledRails);
            auditRule=auditRule==null?"EXACT_STATE_V1":auditRule;
        }
        public Receipt(int version,UUID operation,String registryId,String nativePlanSha256,
                       int chunks,long changedBlocks,List<String> auditedChunks,String status) {
            this(version,operation,registryId,nativePlanSha256,chunks,changedBlocks,
                auditedChunks,status,"EXACT_STATE_V1",List.of());
        }
    }
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"worldprep-native-structure-readonly-audit");
        t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;
    });
    private final NativeRegistryStructurePass.Prepared prepared;
    private final PhysicalStore store;
    private final List<String> verified=new ArrayList<>();
    private final List<SettledRail> settlements=new ArrayList<>();
    private final Map<Long,Map<Integer,PalettedContainer<BlockState>>> diskStates=new HashMap<>();
    private CompletableFuture<CompoundTag> pending;
    private NativeChunkWindow leases;
    private final Map<Long,NativeChunkWindow> railNeighborLeases=new LinkedHashMap<>();
    private int cursor;
    private long countedChanges;
    private boolean closed;

    public NativeRegistryStructureAudit(NativeRegistryStructurePass.Prepared prepared,PhysicalStore store)throws IOException {
        this.prepared=prepared;this.store=store;
        var finished=store.read("native-all-chunks-placed",NativeRegistryStructurePass.NativeReceipt.class);
        if(!"PLACED_UNVERIFIED_DISK".equals(finished.state())
                ||!finished.operation().equals(prepared.operation())
                ||!finished.registryId().equals(prepared.registryId())
                ||finished.completedChunks()!=prepared.chunks().size()
                ||finished.totalChunks()!=prepared.chunks().size()||finished.changedBlocks()<1)
            throw new IOException("Native placement has no physical changes/complete in-memory receipt");
    }
    public boolean done(){return cursor==prepared.chunks().size();}
    public int checkedChunks(){return cursor;}
    public int settledRailCount(){return settlements.size();}
    public boolean step(ServerLevel level)throws IOException {
        NativePhysicalRuntime.requireThread(level);
        if(done())return true;
        if(closed)throw new IOException("Native audit already closed");
        if(store.exists("rollback-intent"))throw new IOException("Native placement rolled back");
        var pos=prepared.chunks().get(cursor);
        if(leases==null)leases=new NativeChunkWindow(level);
        if(!leases.blocks(pos.getMinBlockX(),pos.getMinBlockZ(),pos.getMaxBlockX(),pos.getMaxBlockZ()))return false;
        if(pending==null) {
            Path region=region(level);
            pending=CompletableFuture.supplyAsync(()->{
                try{return ReadOnlyChunkNbt.read(region,pos.x,pos.z);}
                catch(IOException err){throw new CompletionException(err);}
            },IO);
            return false;
        }
        if(!pending.isDone())return false;
        CompoundTag nbt;
        try{nbt=pending.join();}
        catch(CompletionException e){throw new IOException("Native structure disk readback failed",e.getCause());}
        var delta=store.read("native-chunk-placed-"+cursor,NativeRegistryStructurePass.ChunkDelta.class);
        if(delta.chunkX()!=pos.x||delta.chunkZ()!=pos.z||delta.changedBlocks()<0
                ||delta.samples()==null||delta.samples().size()>16)
            throw new IOException("Native placement chunk journal identity mismatch");
        var structureNbt=nbt.getCompound("structures");
        var starts=structureNbt.getCompound("starts");
        var refs=structureNbt.getCompound("References");
        if(!refs.contains(prepared.registryId(),Tag.TAG_LONG_ARRAY))
            throw new IOException("Native structure references not persisted in "+pos);
        var origin=prepared.start().getChunkPos();
        if(java.util.Arrays.stream(refs.getLongArray(prepared.registryId())).noneMatch(value->value==origin.toLong()))
            throw new IOException("Native structure reference points to wrong start");
        if(pos.equals(origin)&&!starts.contains(prepared.registryId(),Tag.TAG_COMPOUND))
            throw new IOException("Native StructureStart not persisted");
        if(!pos.equals(origin)&&starts.contains(prepared.registryId(),Tag.TAG_COMPOUND))
            throw new IOException("Unexpected native start in secondary chunk");
        diskStates.clear();diskStates.put(pos.toLong(),decode(nbt));
        if(!loadSettledRailNeighbors(level,pos,delta))return false;
        for(var sample:delta.samples()) {
            int x=sample.x(),y=sample.y(),z=sample.z();
            if((x>>4)!=pos.x||(z>>4)!=pos.z||y<prepared.actualBounds().minY()||y>prepared.actualBounds().maxY())
                throw new IOException("Sample outside native structure bounds");
            var blockPos=new BlockPos(x,y,z);
            BlockState disk=diskState(level,blockPos);
            String actual=state(disk),live=state(level.getBlockState(blockPos));
            if(!actual.equals(live))throw new IOException("Native block sample disk/live divergence at "+x+","+y+","+z);
            if(!sample.after().equals(actual)) {
                if(!flatRailShapeOnly(sample.after(),actual))
                    throw new IOException("Native block sample mismatches disk/live at "+x+","+y+","+z);
                settlements.add(verifySettledRail(level,blockPos,sample.after(),actual));
            }
        }
        countedChanges+=delta.changedBlocks();verified.add(pos.x+","+pos.z);
        cursor++;pending=null;diskStates.clear();closeLeases();
        if(done()) {
            var declared=store.read("native-all-chunks-placed",NativeRegistryStructurePass.NativeReceipt.class);
            if(countedChanges<1||declared.changedBlocks()!=countedChanges)
                throw new IOException("Native placement has no verified physical block differences");
            store.write("native-disk-verified",new Receipt(1,prepared.operation(),prepared.registryId(),
                PhysicalStore.sha(store.readText("native-plan")),cursor,countedChanges,List.copyOf(verified),
                "PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED",
                settlements.isEmpty()?"EXACT_STATE_V1":"EXACT_STATE_WITH_"+SETTLEMENT_RULE,List.copyOf(settlements)));
        }
        return done();
    }
    private static Path region(ServerLevel level){return DimensionType.getStorageFolder(level.dimension(),
        level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");}
    private static String state(BlockState s){return fr.proutlost.worldprep.runtime.BlockMutationRuntime.stateString(s);}
    private static Map<Integer,PalettedContainer<BlockState>> decode(CompoundTag nbt)throws IOException {
        var sections=nbt.getList("sections",Tag.TAG_COMPOUND);
        if(sections.size()>64)throw new IOException("Oversized native disk chunk");
        Map<Integer,PalettedContainer<BlockState>> states=new HashMap<>();
        for(int i=0;i<sections.size();i++) {
            var section=sections.getCompound(i);
            if(section.contains("block_states")) {
                var decoded=PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY,BlockState.CODEC,
                    PalettedContainer.Strategy.SECTION_STATES,Blocks.AIR.defaultBlockState())
                    .parse(NbtOps.INSTANCE,section.getCompound("block_states")).getOrThrow();
                if(states.put((int)section.getByte("Y"),decoded)!=null)throw new IOException("Duplicate native chunk section");
            }
        }
        return states;
    }
    private BlockState diskState(ServerLevel level,BlockPos p)throws IOException {
        var cp=new ChunkPos(p);var map=diskStates.get(cp.toLong());
        if(map==null){map=decode(ReadOnlyChunkNbt.read(region(level),cp.x,cp.z));diskStates.put(cp.toLong(),map);}
        var sec=map.get(p.getY()>>4);
        return sec==null?Blocks.AIR.defaultBlockState():sec.get(p.getX()&15,p.getY()&15,p.getZ()&15);
    }
    private static Map<String,String> railProperties(String text) {
        if(text==null||!text.startsWith("minecraft:rail[")||!text.endsWith("]"))return Map.of();
        var result=new TreeMap<String,String>();
        for(var token:text.substring("minecraft:rail[".length(),text.length()-1).split(",")) {
            var pair=token.split("=",-1);if(pair.length!=2||result.put(pair[0],pair[1])!=null)return Map.of();
        }
        if(!result.keySet().equals(Set.of("shape","waterlogged"))
                ||!Set.of("true","false").contains(result.get("waterlogged")))return Map.of();
        return result;
    }
    /** Pure admissibility check; physical reciprocal-neighbor proof remains mandatory. */
    static boolean flatRailShapeOnly(String expected,String actual) {
        var before=railProperties(expected);var after=railProperties(actual);
        return before.size()==2&&after.size()==2&&!before.get("shape").equals(after.get("shape"))
            &&before.get("waterlogged").equals(after.get("waterlogged"))
            &&flatEnds(before.get("shape"))!=null&&flatEnds(after.get("shape"))!=null;
    }
    private static Direction[] flatEnds(String shape) {
        return switch(shape) {
            case "east_west"->new Direction[]{Direction.WEST,Direction.EAST};
            case "north_south"->new Direction[]{Direction.NORTH,Direction.SOUTH};
            case "south_west"->new Direction[]{Direction.SOUTH,Direction.WEST};
            case "south_east"->new Direction[]{Direction.SOUTH,Direction.EAST};
            case "north_west"->new Direction[]{Direction.NORTH,Direction.WEST};
            case "north_east"->new Direction[]{Direction.NORTH,Direction.EAST};
            default->null;
        };
    }
    /** A lease pins its 3x3 persisted dependencies but only promotes its center.
     * Promote precisely the adjacent centers required by a divergent rail; merely
     * having read the loading halo is not proof that getChunkNow is available.
     * One advance per tick, all leases retained until this chunk is fully audited.
     */
    private boolean loadSettledRailNeighbors(ServerLevel level,ChunkPos primary,
            NativeRegistryStructurePass.ChunkDelta delta)throws IOException {
        for(var sample:delta.samples()) {
            int x=sample.x(),y=sample.y(),z=sample.z();
            if((x>>4)!=primary.x||(z>>4)!=primary.z
                    ||y<prepared.actualBounds().minY()||y>prepared.actualBounds().maxY())
                throw new IOException("Sample outside native structure bounds");
            var position=new BlockPos(x,y,z);String actual=state(diskState(level,position));
            if(sample.after().equals(actual))continue;
            if(!flatRailShapeOnly(sample.after(),actual))
                throw new IOException("Native block sample mismatches disk at "+x+","+y+","+z);
            for(var direction:flatEnds(railProperties(actual).get("shape"))) {
                var adjacent=new ChunkPos(position.relative(direction));
                if(adjacent.equals(primary))continue;
                var lease=railNeighborLeases.computeIfAbsent(adjacent.toLong(),key->new NativeChunkWindow(level));
                if(!lease.blocks(adjacent.getMinBlockX(),adjacent.getMinBlockZ(),
                        adjacent.getMaxBlockX(),adjacent.getMaxBlockZ()))return false;
            }
        }
        return true;
    }
    private SettledRail verifySettledRail(ServerLevel level,BlockPos pos,String expected,String actual)throws IOException {
        var ends=flatEnds(railProperties(actual).get("shape"));
        if(ends==null)throw new IOException("Unsupported rail settlement geometry");
        var ownFloor=pos.below();
        if(!state(diskState(level,ownFloor)).equals(state(level.getBlockState(ownFloor)))
                ||!level.getBlockState(ownFloor).isFaceSturdy(level,ownFloor,Direction.UP))
            throw new IOException("Settled rail support not stable on disk/live");
        var evidence=new ArrayList<RailNeighbor>();
        for(var direction:ends) {
            var neighbor=pos.relative(direction);
            var chunk=level.getChunkSource().getChunkNow(neighbor.getX()>>4,neighbor.getZ()>>4);
            if(chunk==null)throw new IOException("Settled rail neighbor not loaded by existing-chunk lease");
            var disk=diskState(level,neighbor);var live=level.getBlockState(neighbor);
            String diskText=state(disk),liveText=state(live);
            if(!disk.is(Blocks.RAIL)||!diskText.equals(liveText))
                throw new IOException("Settled rail neighbor absent or disk/live divergence");
            var neighborEnds=flatEnds(railProperties(diskText).getOrDefault("shape",""));
            if(neighborEnds==null||Arrays.stream(neighborEnds).noneMatch(d->d==direction.getOpposite()))
                throw new IOException("Settled rail neighbor does not connect reciprocally");
            var support=neighbor.below();String sd=state(diskState(level,support)),sl=state(level.getBlockState(support));
            if(!sd.equals(sl)||!level.getBlockState(support).isFaceSturdy(level,support,Direction.UP))
                throw new IOException("Settled neighboring rail support not stable");
            evidence.add(new RailNeighbor(neighbor.getX(),neighbor.getY(),neighbor.getZ(),direction.getName(),diskText,liveText,sd,sl));
        }
        return new SettledRail(pos.getX(),pos.getY(),pos.getZ(),expected,actual,SETTLEMENT_RULE,evidence);
    }
    private void closeLeases(){
        RuntimeException failure=null;
        if(leases!=null){try{leases.close();}catch(RuntimeException e){failure=e;}finally{leases=null;}}
        for(var lease:railNeighborLeases.values())try{lease.close();}catch(RuntimeException e){
            if(failure==null)failure=e;else failure.addSuppressed(e);
        }
        railNeighborLeases.clear();if(failure!=null)throw failure;
    }
    @Override public void close(){
        closed=true;
        try{closeLeases();}finally{
            if(pending!=null){pending.cancel(false);pending=null;}diskStates.clear();
        }
    }
}
