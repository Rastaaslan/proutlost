package fr.proutlost.integration.physical;

import fr.proutlost.contract.director.Bounds;
import fr.proutlost.contract.director.Digest;
import fr.proutlost.worldprep.physical.ReadOnlyChunkNbt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

/** Bounded current-cell observation plus independent Anvil readback. No tickets,
 * chunk load/generation, save, mutation, capability or narrative authority. */
public final class NativeLoadedAndDiskCellWitness {
    public static final int MAX_CELLS=32768,MAX_CHUNKS=96;
    public record Verified(Digest loadedDigest,Digest diskDigest,long cells,int chunks) {}
    private record Chunk(String dimension,int x,int z) {}
    private record Decoded(Map<Integer,PalettedContainer<BlockState>> sections,Set<BlockPos> entities) {}
    private NativeLoadedAndDiskCellWitness() {}
    public static Verified capture(MinecraftServer server,List<Bounds> geometry)throws IOException {
        if(!server.isSameThread())throw new IllegalStateException("physical survey server actor required");
        if(geometry==null||geometry.isEmpty()||geometry.size()>32)throw new IOException("bounded nonempty survey geometry required");
        long cells=0;Set<Chunk> chunks=new HashSet<>();
        try{
            for(var b:geometry){
                ServerLevel dimension=null;for(var current:server.getAllLevels())if(current.dimension().location().toString().equals(b.dimension_id().value()))dimension=current;
                var min=new BlockPos(b.min().x(),b.min().y(),b.min().z());var max=new BlockPos(b.max().x(),b.max().y(),b.max().z());
                if(dimension==null||!dimension.isInWorldBounds(min)||!dimension.isInWorldBounds(max)
                    ||!dimension.getWorldBorder().isWithinBounds(min)||!dimension.getWorldBorder().isWithinBounds(max))
                    throw new IOException("survey geometry exceeds actual world/build/border bounds");
                cells=Math.addExact(cells,Math.multiplyExact(Math.multiplyExact((long)b.max().x()-b.min().x()+1,(long)b.max().y()-b.min().y()+1),(long)b.max().z()-b.min().z()+1));
                if(cells>MAX_CELLS)throw new IOException("survey exceeds existing loaded-state cell bound");
                for(int x=b.min().x()>>4;x<=b.max().x()>>4;x++)for(int z=b.min().z()>>4;z<=b.max().z()>>4;z++){
                    chunks.add(new Chunk(b.dimension_id().value(),x,z));
                    if(chunks.size()>MAX_CHUNKS)throw new IOException("survey chunk bound");
                }
            }
        }catch(ArithmeticException overflow){throw new IOException("survey geometry overflow",overflow);}
        for(var chunk:chunks){
            ServerLevel loaded=null;for(var current:server.getAllLevels())if(current.dimension().location().toString().equals(chunk.dimension()))loaded=current;
            if(loaded==null||loaded.getChunkSource().getChunkNow(chunk.x(),chunk.z())==null)
                throw new IOException("survey requires an already loaded FULL chunk; no load requested");
        }
        Digest before=NativeLoadedCellWitness.stateFingerprint(server,geometry)
            .orElseThrow(()->new IOException("survey unloaded/out-of-bounds/BlockEntity geometry is unsupported"));
        try{
            var digest=MessageDigest.getInstance("SHA-256");Chunk cached=null;Decoded decoded=null;
            for(var b:geometry){
                ServerLevel level=null;
                for(var current:server.getAllLevels())if(current.dimension().location().toString().equals(b.dimension_id().value()))level=current;
                if(level==null)throw new IOException("survey dimension is not loaded");
                Path region=DimensionType.getStorageFolder(level.dimension(),server.getWorldPath(LevelResource.ROOT)).resolve("region");
                for(int x=b.min().x();x<=b.max().x();x++)for(int z=b.min().z();z<=b.max().z();z++){
                    if(level.getChunkSource().getChunkNow(x>>4,z>>4)==null)throw new IOException("survey chunk unloaded during capture");
                    var key=new Chunk(b.dimension_id().value(),x>>4,z>>4);
                    if(!key.equals(cached)){
                        decoded=decode(ReadOnlyChunkNbt.readBounded(region,key.x(),key.z()));cached=key;
                    }
                    for(int y=b.min().y();y<=b.max().y();y++){
                        var pos=new BlockPos(x,y,z);
                        if(!level.isInWorldBounds(pos)||!level.getWorldBorder().isWithinBounds(pos)
                            ||level.getBlockEntity(pos)!=null||decoded.entities().contains(pos))
                            throw new IOException("survey bounds/BlockEntity refusal");
                        var section=decoded.sections().get(y>>4);
                        var disk=section==null?Blocks.AIR.defaultBlockState():section.get(x&15,y&15,z&15);
                        var live=level.getBlockState(pos);
                        if(disk.hasBlockEntity()||live.hasBlockEntity())throw new IOException("survey BlockEntity-dependent state is unsupported");
                        if(!disk.equals(live))throw new IOException("survey loaded/Anvil state mismatch");
                        String row=b.dimension_id().value()+"/"+x+"/"+y+"/"+z+"/"+disk+"\n";
                        digest.update(row.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            var disk=new Digest(HexFormat.of().formatHex(digest.digest()));
            var after=NativeLoadedCellWitness.stateFingerprint(server,geometry)
                .orElseThrow(()->new IOException("survey current observation revoked"));
            if(!before.equals(after)||!before.equals(disk))throw new IOException("survey state changed across independent readback");
            return new Verified(before,disk,cells,chunks.size());
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        catch(IllegalArgumentException malformed){throw new IOException("survey malformed persisted block state",malformed);}
    }
    private static Decoded decode(CompoundTag nbt)throws IOException {
        var sections=nbt.getList("sections",Tag.TAG_COMPOUND);
        if(sections.size()>64)throw new IOException("survey disk section bound");
        var decoded=new HashMap<Integer,PalettedContainer<BlockState>>();
        for(int i=0;i<sections.size();i++){
            var section=sections.getCompound(i);if(!section.contains("block_states"))continue;
            var states=PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY,BlockState.CODEC,PalettedContainer.Strategy.SECTION_STATES,Blocks.AIR.defaultBlockState())
                .parse(NbtOps.INSTANCE,section.getCompound("block_states")).getOrThrow();
            if(decoded.put((int)section.getByte("Y"),states)!=null)throw new IOException("duplicate survey disk section");
        }
        var entities=nbt.getList("block_entities",Tag.TAG_COMPOUND);
        if(entities.size()>4096)throw new IOException("survey disk BlockEntity bound");
        var positions=new HashSet<BlockPos>();
        for(int i=0;i<entities.size();i++){
            var e=entities.getCompound(i);positions.add(new BlockPos(e.getInt("x"),e.getInt("y"),e.getInt("z")));
        }
        return new Decoded(Map.copyOf(decoded),Set.copyOf(positions));
    }
}
