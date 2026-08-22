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

import java.util.List;

/** Quart-resolution biome mutation, exact snapshotting, and tracking-client synchronization. */
public final class BiomeRuntime {
    public static void captureColumn(ServerLevel level, WorldPrepSavedData.Snapshot snapshot,int quartX,int quartZ){LevelChunk chunk=level.getChunk(QuartPos.toBlock(quartX)>>4,QuartPos.toBlock(quartZ)>>4);for(int qy=QuartPos.fromBlock(level.getMinBuildHeight());qy<=QuartPos.fromBlock(level.getMaxBuildHeight()-1);qy++){Holder<Biome> before=chunk.getNoiseBiome(quartX,qy,quartZ);String old=before.unwrapKey().orElseThrow(()->new IllegalStateException("Unregistered biome holder")).location().toString();snapshot.cells.putIfAbsent(WorldPrepSavedData.snapshotKey(quartX,qy,quartZ),new WorldPrepSavedData.SnapshotCell(quartX,qy,quartZ,old));}}
    @SuppressWarnings("unchecked")
    public static void writeColumn(ServerLevel level,int quartX,int quartZ,String biomeId){Holder<Biome> target=level.registryAccess().registryOrThrow(Registries.BIOME).getHolder(ResourceLocation.parse(biomeId)).orElseThrow(()->new IllegalStateException("Missing biome "+biomeId));LevelChunk chunk=level.getChunk(QuartPos.toBlock(quartX)>>4,QuartPos.toBlock(quartZ)>>4);for(int qy=QuartPos.fromBlock(level.getMinBuildHeight());qy<=QuartPos.fromBlock(level.getMaxBuildHeight()-1);qy++){int sectionIndex=chunk.getSectionIndex(QuartPos.toBlock(qy));var mutable=(PalettedContainer<Holder<Biome>>)chunk.getSection(sectionIndex).getBiomes();mutable.set(QuartPos.quartLocal(quartX),QuartPos.quartLocal(qy),QuartPos.quartLocal(quartZ),target);}chunk.setUnsaved(true);}
    @SuppressWarnings("unchecked")
    public static void restoreCell(ServerLevel level,WorldPrepSavedData.SnapshotCell cell){Holder<Biome> target=level.registryAccess().registryOrThrow(Registries.BIOME).getHolder(ResourceLocation.parse(cell.biome())).orElseThrow(()->new IllegalStateException("Missing snapshot biome "+cell.biome()));LevelChunk chunk=level.getChunk(QuartPos.toBlock(cell.quartX())>>4,QuartPos.toBlock(cell.quartZ())>>4);int sectionIndex=chunk.getSectionIndex(QuartPos.toBlock(cell.quartY()));((PalettedContainer<Holder<Biome>>)chunk.getSection(sectionIndex).getBiomes()).set(QuartPos.quartLocal(cell.quartX()),QuartPos.quartLocal(cell.quartY()),QuartPos.quartLocal(cell.quartZ()),target);chunk.setUnsaved(true);}
    public static void refresh(ServerLevel level,LevelChunk chunk){var packet=ClientboundChunksBiomesPacket.forChunks(List.of(chunk));for(var player:level.getChunkSource().chunkMap.getPlayers(chunk.getPos(),false))player.connection.send(packet);}
    private BiomeRuntime(){}
}
