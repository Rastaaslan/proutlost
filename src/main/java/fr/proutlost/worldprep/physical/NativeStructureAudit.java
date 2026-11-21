package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.runtime.*;
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
import java.util.concurrent.*;

/** Independent physical + disk audit after a normal save. Never initiates or overlaps an autosave.
 * One read-only bounded chunk task is in flight; all live-world access stays on the server thread.
 */
public final class NativeStructureAudit {
    public record Receipt(int version,UUID operation,String planSha256,String cellsSha256,List<String> chunks,String status) {}
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"worldprep-physical-audit");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
    private final NativeStructurePass.Plan plan;
    private final PhysicalStore store;
    private final List<List<NativeStructurePass.Cell>> chunks;
    private final List<String> inventory=new ArrayList<>();
    private final StringBuilder verified=new StringBuilder();
    private int cursor;
    private CompletableFuture<CompoundTag> pending;
    public NativeStructureAudit(NativeStructurePass.Plan plan,PhysicalStore store) {
        this.plan=plan;this.store=store;
        var grouped=new TreeMap<String,List<NativeStructurePass.Cell>>();
        var all=new ArrayList<>(plan.cells());all.addAll(plan.supports());
        for(var cell:all)grouped.computeIfAbsent((cell.x()>>4)+","+(cell.z()>>4),k->new ArrayList<>()).add(cell);
        chunks=List.copyOf(grouped.values());
    }
    public NativeStructurePass.Plan plan(){return plan;}
    public boolean done(){return cursor==chunks.size();}
    public boolean step(ServerLevel level)throws IOException {
        NativePhysicalRuntime.requireThread(level);
        if(store.exists("rollback-intent"))throw new IOException("Rolled-back structure cannot be exported");
        if(cursor==chunks.size())return true;
        var cells=chunks.get(cursor);var first=cells.getFirst();int x=first.x()>>4,z=first.z()>>4;
        if(pending==null) {
            var region=DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
            pending=CompletableFuture.supplyAsync(()->{try{return ReadOnlyChunkNbt.read(region,x,z);}catch(IOException e){throw new CompletionException(e);}},IO);
            return false;
        }
        if(!pending.isDone())return false;
        CompoundTag nbt;
        try {nbt=pending.join();}catch(CompletionException e){throw new IOException("Disk readback failed",e.getCause());}
        var sections=nbt.getList("sections",Tag.TAG_COMPOUND);
        if(sections.size()>64)throw new IOException("Chunk section limit");
        Map<Integer,PalettedContainer<BlockState>> states=new HashMap<>();
        for(int i=0;i<sections.size();i++) {
            var section=sections.getCompound(i);
            if(section.contains("block_states")) {
                var parsed=PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY,BlockState.CODEC,PalettedContainer.Strategy.SECTION_STATES,Blocks.AIR.defaultBlockState())
                    .parse(NbtOps.INSTANCE,section.getCompound("block_states")).getOrThrow();
                states.put((int)section.getByte("Y"),parsed);
            }
        }
        for(var cell:cells) {
            var pos=new BlockPos(cell.x(),cell.y(),cell.z());
            NativeStructurePass.requireSite(level,WorldPrepRuntime.data(level),plan.area(),pos);
            var section=states.get(cell.y()>>4);
            String disk=BlockMutationRuntime.stateString(section==null?Blocks.AIR.defaultBlockState():section.get(cell.x()&15,cell.y()&15,cell.z()&15));
            if(!disk.equals(cell.after())||!BlockMutationRuntime.stateString(level.getBlockState(pos)).equals(cell.after()))
                throw new IOException("Physical/disk mismatch: "+pos);
            var entities=nbt.getList("block_entities",Tag.TAG_COMPOUND);
            if(entities.size()>4096)throw new IOException("BlockEntity count limit");
            CompoundTag diskEntity=null;
            for(int i=0;i<entities.size();i++) {
                var e=entities.getCompound(i);
                if(e.getInt("x")==cell.x()&&e.getInt("y")==cell.y()&&e.getInt("z")==cell.z()) {
                    if(diskEntity!=null)throw new IOException("Duplicate persisted BlockEntity");diskEntity=e.copy();
                    // Chunk serialization adds this transport flag; it is not template payload.
                    if(diskEntity.contains("keepPacked")) {
                        if(!diskEntity.contains("keepPacked",Tag.TAG_BYTE)||diskEntity.getBoolean("keepPacked"))throw new IOException("Packed BlockEntity not qualified");
                        diskEntity.remove("keepPacked");
                    }
                }
            }
            var liveEntity=level.getBlockEntity(pos);
            if(cell.blockEntity()==null) {
                if(diskEntity!=null||liveEntity!=null)throw new IOException("Unexpected live/disk BlockEntity");
            } else {
                if(diskEntity==null||liveEntity==null
                    ||!ExactTemplateBlockEntities.matches(cell.blockEntity(),diskEntity)
                    ||!ExactTemplateBlockEntities.matches(cell.blockEntity(),liveEntity.saveWithFullMetadata(level.registryAccess())))
                    throw new IOException("BlockEntity live/disk mismatch: "+pos);
                verified.append(cell.blockEntity()).append('\n');
            }
            verified.append(cell.x()).append(',').append(cell.y()).append(',').append(cell.z()).append('=').append(disk).append('\n');
        }
        inventory.add(x+","+z);cursor++;pending=null;
        if(cursor==chunks.size())store.write("disk-verified",new Receipt(1,plan.operation(),PhysicalStore.sha(store.readText("plan")),PhysicalStore.sha(verified.toString()),List.copyOf(inventory),"PLACED_VERIFIED"));
        return cursor==chunks.size();
    }
}
