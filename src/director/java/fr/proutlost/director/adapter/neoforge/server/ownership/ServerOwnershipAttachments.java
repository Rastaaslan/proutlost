package fr.proutlost.director.adapter.neoforge.server.ownership;

import com.mojang.serialization.Codec;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** Persisted chunk provenance. Registration is explicit at integrator bootstrap, never automatic.
 * Immutable map replacement and chunk dirty flag happen in the same server-actor turn.
 */
public final class ServerOwnershipAttachments {
    private static final DeferredRegister<AttachmentType<?>> TYPES=DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES,"proutlost_director");
    private static final Supplier<AttachmentType<Map<String,String>>> BLOCKS=TYPES.register("owned_cells",
            ()->AttachmentType.<Map<String,String>>builder(Map::of).serialize(Codec.unboundedMap(Codec.STRING,Codec.STRING)).build());
    private static final ModelCodec<OwnershipRecord> CODEC=new ModelCodec<>(OwnershipRecord.class);
    private final int cellsPerChunk;
    public ServerOwnershipAttachments(int cellsPerChunk){if(cellsPerChunk<1)throw new IllegalArgumentException("cell bound");this.cellsPerChunk=cellsPerChunk;}
    public static void register(IEventBus modBus){TYPES.register(modBus);}
    private static void actor(ServerLevel level){if(!level.getServer().isSameThread())throw new IllegalStateException("server actor required");}
    public static ResourceKey key(ServerLevel level,BlockPos pos){return new ResourceKey(OwnershipDomain.BLOCK,new DimensionId(level.dimension().location().toString()),pos.getX()+","+pos.getY()+","+pos.getZ());}
    private static LevelChunk loaded(ServerLevel level,BlockPos pos){
        actor(level);LevelChunk chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
        if(chunk==null)throw new IllegalStateException("ownership chunk unavailable");return chunk;
    }
    public Optional<OwnershipRecord> read(ServerLevel level,BlockPos pos){
        String encoded=loaded(level,pos).getData(BLOCKS.get()).get(key(level,pos).key());
        if(encoded==null)return Optional.empty();
        OwnershipRecord record=CODEC.decode(encoded.getBytes(StandardCharsets.UTF_8));
        if(!record.resourceKey().equals(key(level,pos)))throw new IllegalStateException("ownership identity corruption");
        return Optional.of(record);
    }
    public void write(ServerLevel level,BlockPos pos,OwnershipRecord next){
        if(!next.resourceKey().equals(key(level,pos)))throw new IllegalArgumentException("foreign ownership resource");
        LevelChunk chunk=loaded(level,pos);Map<String,String> copy=new HashMap<>(chunk.getData(BLOCKS.get()));
        if(!copy.containsKey(next.resourceKey().key()) && copy.size()>=cellsPerChunk)throw new IllegalStateException("ownership chunk capacity");
        copy.put(next.resourceKey().key(),new String(CODEC.encode(next),StandardCharsets.UTF_8));
        chunk.setData(BLOCKS.get(),Map.copyOf(copy));chunk.setUnsaved(true);
    }
    /** External player/physics hooks call before any queued Director work can resume.
     * Return the exact version for asynchronous persistence; no IO happens here.
     */
    public Optional<OwnershipRecord> invalidate(ServerLevel level,BlockPos pos,boolean voluntaryPlayer,Tick tick){
        var before=read(level,pos);if(before.isEmpty())return Optional.empty();OwnershipRecord old=before.orElseThrow();
        if(tick.compareTo(old.header().updatedTick())<0)throw new IllegalArgumentException("clock regression");
        boolean yielded=voluntaryPlayer || old.disposition()==OwnershipDisposition.YIELDED_TO_PLAYER;
        var next=new OwnershipRecord(new AggregateHeader(1,old.header().id(),old.header().revision().plus(1),old.header().lastSequence(),tick),
                old.resourceKey(),old.ownerTokens(),old.epoch().plus(1),yielded?OwnershipDisposition.YIELDED_TO_PLAYER:OwnershipDisposition.CONFLICT_ISOLATED,
                old.creationMutationId(),old.expectedPostHash(),voluntaryPlayer?old.playerOverrideEpoch().plus(1):old.playerOverrideEpoch(),old.exceptionContractId());
        write(level,pos,next);return Optional.of(next);
    }
}
