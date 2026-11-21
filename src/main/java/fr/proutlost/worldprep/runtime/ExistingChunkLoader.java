package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.world.ExistingChunkLease;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Opt-in candidate-only loading. All ticket and Level operations stay on the server thread. */
public final class ExistingChunkLoader {
    private static final TicketType<UUID> TICKET=TicketType.create("proutlost_existing",UUID::compareTo);
    // Explicit cleanup on stop/unload; one lease per level, never an island-wide ticket set.
    private static final Map<ServerLevel, Active> ACTIVE=new HashMap<>();
    private record Active(UUID job,int x,int z,ExistingChunkLease lease) {}

    public static boolean ensure(ServerLevel level, UUID job, int x, int z) {
        requireThread(level);
        if(!WorldPrepConfig.LOAD_EXISTING_CHUNKS.get()){release(level);return true;}
        CandidateGuard.requireCandidate(level);
        if(WorldPrepConfig.MODE.get()!=WorldPrepConfig.Mode.TEST || level.getServer().getPlayerCount()!=0)
            throw new IllegalStateException("Existing chunk loading requires TEST mode and no players");
        requirePinnedLoadingGraph();
        var active=ACTIVE.get(level);
        if(active==null||!active.job().equals(job)||active.x()!=x||active.z()!=z){
            release(level);
            active=new Active(job,x,z,openVerifiedLease(level,x,z));
            ACTIVE.put(level,active);
        }
        try{return active.lease().step();}
        catch(RuntimeException e){release(level);throw e;}
    }

    /** Explicitly scoped lease; caller must close on success, failure and interruption.
     * Used by the batch owner and isolated runtime acceptance tests.
     */
    public static ExistingChunkLease openVerifiedLease(ServerLevel level,int x,int z) {
        requireThread(level);
        CandidateGuard.requireCandidate(level);
        if(WorldPrepConfig.MODE.get()!=WorldPrepConfig.Mode.TEST||level.getServer().getPlayerCount()!=0)
            throw new IllegalStateException("Existing chunk loading requires TEST mode and no players");
        requirePinnedLoadingGraph();
        return new ExistingChunkLease(x,z,new MinecraftPort(level));
    }

    /** Verify the exact native loading dependencies before issuing any ticket. */
    public static void requirePinnedLoadingGraph() {
        var loading=ChunkPyramid.LOADING_PYRAMID;
        if(loading.getStepTo(ChunkStatus.FULL).getAccumulatedRadiusOf(ChunkStatus.EMPTY)!=1
                ||loading.getStepTo(ChunkLevel.getStatusAroundFullChunk(1)).getAccumulatedRadiusOf(ChunkStatus.EMPTY)!=0)
            throw new IllegalStateException("Unsupported Minecraft chunk loading dependency graph");
    }

    public static void release(ServerLevel level) {
        requireThread(level);
        var active=ACTIVE.remove(level);
        if(active!=null)active.lease().close();
    }
    public static void cleanup(ServerLevel level, WorldPrepSavedData data) {
        var active=ACTIVE.get(level);
        if(active==null)return;
        var job=data.jobs().get(active.job());
        if(!WorldPrepConfig.LOAD_EXISTING_CHUNKS.get()||job==null
                ||(job.state()!=WorldPrepSavedData.JobState.RUNNING&&job.state()!=WorldPrepSavedData.JobState.QUEUED))
            release(level);
    }
    public static void unload(net.neoforged.neoforge.event.level.LevelEvent.Unload event) {
        if(event.getLevel() instanceof ServerLevel level){
            MultiTileEcologyPrefetch.cancelLevel(level);
            release(level);
        }
    }
    private static void requireThread(ServerLevel level){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Server thread required");
    }
    private static final class MinecraftPort implements ExistingChunkLease.Port {
        private final ServerLevel level;
        private final UUID leaseId=UUID.randomUUID();
        private final Map<ExistingChunkLease.Position,ChunkAccess> verified=new HashMap<>();
        private final int emptyDistance=33-ChunkLevel.byStatus(ChunkStatus.EMPTY);
        private MinecraftPort(ServerLevel level){this.level=level;}

        @Override public boolean loadAndPinExistingFull(ExistingChunkLease.Position p) {
            var source=level.getChunkSource();var pos=new ChunkPos(p.x(),p.z());
            if(!level.getWorldBorder().isWithinBounds(pos.getMinBlockX(),pos.getMinBlockZ())
                    ||!level.getWorldBorder().isWithinBounds(pos.getMaxBlockX(),pos.getMaxBlockZ()))
                throw new IllegalStateException("Loading halo outside world border");
            try {
                var world=level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
                var region=net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(),world).resolve("region");
                fr.proutlost.worldprep.world.ExistingRegionEntry.requirePresent(region,p.x(),p.z());
            } catch(java.io.IOException e){throw new IllegalStateException("Existing region entry preflight refused at "+p.x()+","+p.z()+": "+e.getMessage(),e);}
            source.addRegionTicket(TICKET,pos,emptyDistance,leaseId);
            try {
                // EMPTY's native task only reads/deserializes. Missing/corrupt data can return a
                // transient empty ProtoChunk; it is refused here, NEVER promoted or generated.
                // Public pinned API waits for this single IO operation; no worker touches Level.
                var chunk=source.getChunk(p.x(),p.z(),ChunkStatus.EMPTY,true);
                if(chunk==null||!chunk.getPos().equals(pos))
                    throw new IllegalStateException("Existing chunk identity absent: "+pos);
                if(!fr.proutlost.worldprep.world.NativeChunkEligibility.canPromote(
                        chunk.getPersistedStatus()==ChunkStatus.FULL,chunk.isLightCorrect(),chunk.isUpgrading(),
                        WorldPrepConfig.STRUCTURES_ONLY.get()))
                    throw new IllegalStateException("Existing chunk refused "+pos+" type="+chunk.getClass().getSimpleName()
                        +" status="+chunk.getPersistedStatus()+" light="+chunk.isLightCorrect()+" upgrading="+chunk.isUpgrading());
                verified.put(p,chunk);
                return true;
            } finally {
                // getChunk creates a short-lived UNKNOWN ticket in addition to our own pin.
                source.removeRegionTicket(TicketType.UNKNOWN,pos,emptyDistance,pos);
            }
        }
        @Override public void requestFull(ExistingChunkLease.Position center) {
            if(verified.size()!=9||verified.values().stream().anyMatch(c->
                    !fr.proutlost.worldprep.world.NativeChunkEligibility.canPromote(c.getPersistedStatus()==ChunkStatus.FULL,
                    c.isLightCorrect(),c.isUpgrading(),WorldPrepConfig.STRUCTURES_ONLY.get())))
                throw new IllegalStateException("Lost FULL dependency evidence");
            // Level 33 is accessible only: no block/entity ticking and no forced ticks.
            // prepareAccessibleChunk asks its 3x3 for FULL / the pinned adjacent status.
            level.getChunkSource().addRegionTicket(TICKET,new ChunkPos(center.x(),center.z()),0,leaseId);
        }
        @Override public boolean isFullReady(ExistingChunkLease.Position center) {
            return level.getChunkSource().getChunkNow(center.x(),center.z())!=null;
        }
        @Override public void release(ExistingChunkLease.Position center,List<ExistingChunkLease.Position> pinned) {
            var source=level.getChunkSource();
            source.removeRegionTicket(TICKET,new ChunkPos(center.x(),center.z()),0,leaseId);
            for(var p:pinned)source.removeRegionTicket(TICKET,new ChunkPos(p.x(),p.z()),emptyDistance,leaseId);
            verified.clear();
        }
    }
    private ExistingChunkLoader(){}
}
