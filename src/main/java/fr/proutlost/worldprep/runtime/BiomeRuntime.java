package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
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
import java.util.ArrayList;
import java.util.List;

/** Quart-resolution biome mutation, exact owned snapshots, and batched tracking-client synchronization. */
public final class BiomeRuntime {
    public static int captureColumn(ServerLevel level,WorldPrepSavedData.Snapshot snapshot,int quartX,int quartZ,String appliedBiome){LevelChunk chunk=chunk(level,quartX,quartZ);int added=0;for(int qy=minQy(level);qy<=maxQy(level);qy++){String key=WorldPrepSavedData.snapshotKey(quartX,qy,quartZ),current=id(chunk.getNoiseBiome(quartX,qy,quartZ));var owned=snapshot.cells.get(key);if(owned!=null){if(!current.equals(owned.beforeBiome())&&!current.equals(owned.appliedBiome()))throw conflict(quartX,qy,quartZ,owned.beforeBiome(),owned.appliedBiome(),current);continue;}if(current.equals(appliedBiome))continue;snapshot.cells.put(key,new WorldPrepSavedData.SnapshotCell(quartX,qy,quartZ,current,appliedBiome));added++;}return added;}
    public static List<WorldPrepSavedData.SnapshotCell> inspectColumn(ServerLevel level,WorldPrepSavedData.Snapshot snapshot,int quartX,int quartZ,String appliedBiome){LevelChunk chunk=chunk(level,quartX,quartZ);var cells=new ArrayList<WorldPrepSavedData.SnapshotCell>();for(int qy=minQy(level);qy<=maxQy(level);qy++){String key=WorldPrepSavedData.snapshotKey(quartX,qy,quartZ),current=id(chunk.getNoiseBiome(quartX,qy,quartZ));var owned=snapshot.cells.get(key);if(owned!=null){if(!current.equals(owned.beforeBiome())&&!current.equals(owned.appliedBiome()))throw conflict(quartX,qy,quartZ,owned.beforeBiome(),owned.appliedBiome(),current);continue;}if(!current.equals(appliedBiome))cells.add(new WorldPrepSavedData.SnapshotCell(quartX,qy,quartZ,current,appliedBiome));}return List.copyOf(cells);}
    @SuppressWarnings("unchecked") public static void writeColumn(ServerLevel level,int quartX,int quartZ,String biomeId){Holder<Biome> target=holder(level,biomeId);LevelChunk chunk=chunk(level,quartX,quartZ);for(int qy=minQy(level);qy<=maxQy(level);qy++){int section=chunk.getSectionIndex(QuartPos.toBlock(qy));((PalettedContainer<Holder<Biome>>)chunk.getSection(section).getBiomes()).set(QuartPos.quartLocal(quartX),QuartPos.quartLocal(qy),QuartPos.quartLocal(quartZ),target);}chunk.setUnsaved(true);}
    @SuppressWarnings("unchecked") public static void restoreOwnedCell(ServerLevel level,WorldPrepSavedData.SnapshotCell cell){LevelChunk chunk=chunk(level,cell.quartX(),cell.quartZ());String current=id(chunk.getNoiseBiome(cell.quartX(),cell.quartY(),cell.quartZ()));if(current.equals(cell.beforeBiome()))return;if(!current.equals(cell.appliedBiome()))throw conflict(cell.quartX(),cell.quartY(),cell.quartZ(),cell.beforeBiome(),cell.appliedBiome(),current);Holder<Biome> target=holder(level,cell.beforeBiome());int section=chunk.getSectionIndex(QuartPos.toBlock(cell.quartY()));((PalettedContainer<Holder<Biome>>)chunk.getSection(section).getBiomes()).set(QuartPos.quartLocal(cell.quartX()),QuartPos.quartLocal(cell.quartY()),QuartPos.quartLocal(cell.quartZ()),target);chunk.setUnsaved(true);}
    public static void refresh(ServerLevel level,Collection<LevelChunk> chunks){if(chunks.isEmpty())return;for(var player:level.players()){var visible=chunks.stream().filter(c->level.getChunkSource().chunkMap.getPlayers(c.getPos(),false).contains(player)).toList();if(!visible.isEmpty())player.connection.send(ClientboundChunksBiomesPacket.forChunks(visible));}}
    public static String biomeId(ServerLevel level,int qx,int qy,int qz){return id(chunk(level,qx,qz).getNoiseBiome(qx,qy,qz));}
    private static IllegalStateException conflict(int qx,int qy,int qz,String before,String applied,String current){return new IllegalStateException("Biome ownership conflict at "+qx+","+qy+","+qz+": expected "+before+" or "+applied+" but found "+current);}
    private static LevelChunk chunk(ServerLevel l,int qx,int qz){return ServerExistingChunkAccess.requireChunk(l,QuartPos.toBlock(qx)>>4,QuartPos.toBlock(qz)>>4);}private static int minQy(ServerLevel l){return QuartPos.fromBlock(l.getMinBuildHeight());}private static int maxQy(ServerLevel l){return QuartPos.fromBlock(l.getMaxBuildHeight()-1);}private static String id(Holder<Biome> h){return h.unwrapKey().orElseThrow(()->new IllegalStateException("Unregistered biome holder")).location().toString();}private static Holder<Biome> holder(ServerLevel l,String id){return l.registryAccess().registryOrThrow(Registries.BIOME).getHolder(ResourceLocation.parse(id)).orElseThrow(()->new IllegalStateException("Missing biome "+id));}private BiomeRuntime(){}
}
