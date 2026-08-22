package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.Collection;

/** Quart-resolution biome mutation, exact owned snapshots, and batched tracking-client synchronization. */
public final class BiomeRuntime {
    public static int captureColumn(ServerLevel level,WorldPrepSavedData.Snapshot snapshot,int quartX,int quartZ,String appliedBiome){LevelChunk chunk=chunk(level,quartX,quartZ);int added=0;for(int qy=minQy(level);qy<=maxQy(level);qy++){String before=id(chunk.getNoiseBiome(quartX,qy,quartZ));if(!before.equals(appliedBiome)&&snapshot.cells.putIfAbsent(WorldPrepSavedData.snapshotKey(quartX,qy,quartZ),new WorldPrepSavedData.SnapshotCell(quartX,qy,quartZ,before,appliedBiome))==null)added++;}return added;}
    @SuppressWarnings("unchecked") public static void writeColumn(ServerLevel level,int quartX,int quartZ,String biomeId){Holder<Biome> target=holder(level,biomeId);LevelChunk chunk=chunk(level,quartX,quartZ);for(int qy=minQy(level);qy<=maxQy(level);qy++){int section=chunk.getSectionIndex(QuartPos.toBlock(qy));((PalettedContainer<Holder<Biome>>)chunk.getSection(section).getBiomes()).set(QuartPos.quartLocal(quartX),QuartPos.quartLocal(qy),QuartPos.quartLocal(quartZ),target);}chunk.setUnsaved(true);}
    @SuppressWarnings("unchecked") public static void restoreOwnedCell(ServerLevel level,WorldPrepSavedData.SnapshotCell cell){LevelChunk chunk=chunk(level,cell.quartX(),cell.quartZ());String current=id(chunk.getNoiseBiome(cell.quartX(),cell.quartY(),cell.quartZ()));if(!current.equals(cell.appliedBiome()))throw new IllegalStateException("Rollback ownership conflict at "+cell.quartX()+","+cell.quartY()+","+cell.quartZ()+": expected "+cell.appliedBiome()+" but found "+current);Holder<Biome> target=holder(level,cell.beforeBiome());int section=chunk.getSectionIndex(QuartPos.toBlock(cell.quartY()));((PalettedContainer<Holder<Biome>>)chunk.getSection(section).getBiomes()).set(QuartPos.quartLocal(cell.quartX()),QuartPos.quartLocal(cell.quartY()),QuartPos.quartLocal(cell.quartZ()),target);chunk.setUnsaved(true);}
    public static void refresh(ServerLevel level,Collection<LevelChunk> chunks){if(chunks.isEmpty())return;for(var player:level.players()){var visible=chunks.stream().filter(c->level.getChunkSource().chunkMap.getPlayers(c.getPos(),false).contains(player)).toList();if(!visible.isEmpty())player.connection.send(ClientboundChunksBiomesPacket.forChunks(visible));}}
    public static String biomeId(ServerLevel level,int qx,int qy,int qz){return id(chunk(level,qx,qz).getNoiseBiome(qx,qy,qz));}
    private static LevelChunk chunk(ServerLevel l,int qx,int qz){return l.getChunk(QuartPos.toBlock(qx)>>4,QuartPos.toBlock(qz)>>4);}private static int minQy(ServerLevel l){return QuartPos.fromBlock(l.getMinBuildHeight());}private static int maxQy(ServerLevel l){return QuartPos.fromBlock(l.getMaxBuildHeight()-1);}private static String id(Holder<Biome> h){return h.unwrapKey().orElseThrow(()->new IllegalStateException("Unregistered biome holder")).location().toString();}private static Holder<Biome> holder(ServerLevel l,String id){return l.registryAccess().registryOrThrow(Registries.BIOME).getHolder(ResourceLocation.parse(id)).orElseThrow(()->new IllegalStateException("Missing biome "+id));}private BiomeRuntime(){}
}
