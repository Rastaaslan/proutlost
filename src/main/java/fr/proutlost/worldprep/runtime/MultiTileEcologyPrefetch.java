package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.ecology.BoundedEcologyCandidatePool.Column;
import fr.proutlost.worldprep.ecology.BoundedTilePipeline;
import fr.proutlost.worldprep.ecology.EcologyTiles;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.world.ExistingChunkLease;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * RUN41: four independent tiles in flight, ONE verified future FULL 3x3
 * lease at a time, plus the original single active ecology lease. The
 * server thread captures/validates every snapshot; pure tiles run on workers.
 * Never forces worldgen, touches a player world or commits out of order.
 */
public final class MultiTileEcologyPrefetch {
    private static final Map<WorldPrepSavedData,State> STATES=new WeakHashMap<>();

    private static final class State implements AutoCloseable {
        final ServerLevel level;
        final UUID batch;
        final String area;
        final long seed;
        final int workers;
        final BoundedTilePipeline pipeline;
        final java.util.Map<Long,List<Column>> snapshots=new java.util.HashMap<>();
        ExistingChunkLease futureLease;
        long futureIndex=-1;
        int offeredDistinctTiles;
        int peakOutstandingTiles;
        boolean closed;
        State(ServerLevel level, WorldPrepSavedData.Job parent, int workers) {
            this.level=level;this.batch=parent.id();this.area=parent.area();
            this.seed=level.getSeed();this.workers=workers;
            this.pipeline=new BoundedTilePipeline(workers);
        }
        void releaseFuture() {
            if(futureLease!=null) {
                futureLease.close();futureLease=null;futureIndex=-1;
            }
        }
        @Override public void close() {
            if(closed)return;
            closed=true;
            releaseFuture();
            pipeline.close();
            snapshots.clear();
        }
    }
    private static void server(ServerLevel level) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("RUN41 server thread is mandatory");
    }
    private static State getOrStart(ServerLevel level,WorldPrepSavedData data,
                                    WorldPrepSavedData.Job parent,int workers) {
        if(workers<1||workers>4)throw new IllegalArgumentException("RUN41 max 4 independent native tiles");
        State old=STATES.get(data);
        if(old!=null && (old.level!=level || !old.batch.equals(parent.id())
                || !old.area.equals(parent.area()) || old.seed!=level.getSeed()
                || old.workers!=workers || old.closed)) {
            old.close();STATES.remove(data);old=null;
        }
        if(old==null) {
            old=new State(level,parent,workers);STATES.put(data,old);
        }
        return old;
    }
    private static WorldPrepSavedData.Area tileArea(WorldPrepSavedData.Area parent,
                                                    EcologyTiles tiles,long index) {
        var tile=tiles.at(index);
        return new WorldPrepSavedData.Area("proutlost:run41_snapshot_"+index,
            parent.dimension(),tile.minX(),tile.minZ(),tile.maxX(),tile.maxZ());
    }
    private static void offer(State state,ServerLevel level,WorldPrepSavedData data,
                              WorldPrepSavedData.Area parent,EcologyTiles tiles,long index) {
        if(state.pipeline.has(index))return;
        if(state.pipeline.outstanding()>=state.workers)throw new IllegalStateException("RUN41 bounded tile window overflow");
        var columns=EcologyParallelPreviewRuntime.capture(level,data,tileArea(parent,tiles,index));
        var immutable=List.copyOf(columns);
        state.pipeline.offer(new BoundedTilePipeline.TileInput(index,state.seed,immutable));
        state.snapshots.put(index,immutable);
        state.offeredDistinctTiles++;
        state.peakOutstandingTiles=Math.max(state.peakOutstandingTiles,state.pipeline.outstanding());
    }
    /**
     * Advance at most ONE extra verified native chunk-lease step per tick,
     * while four independent tile CPU tasks can be in flight.
     */
    public static void tick(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Job parent,
                            EcologyTiles tiles,long current,int workers) {
        server(level);
        CandidateGuard.requireCandidate(level);
        if(current<0||current>=tiles.count())throw new IllegalArgumentException("Bad current tile");
        var area=WorldPrepRuntime.requireArea(data,parent.area());
        State state=getOrStart(level,data,parent,workers);
        state.pipeline.retireBefore(current);
        state.snapshots.keySet().removeIf(key->key<current);
        if(state.futureLease!=null && state.futureIndex<=current)state.releaseFuture();
        if(!state.pipeline.has(current)) {
            // Caller has already proved the current FULL chunk via ExistingChunkLoader.ensure.
            offer(state,level,data,area,tiles,current);
        }
        if(state.pipeline.outstanding()>=state.workers)return;
        long next=-1;
        for(long n=current+1;n<Math.min(tiles.count(),current+workers);n++) {
            if(!state.pipeline.has(n)) {next=n;break;}
        }
        if(next<0) {state.releaseFuture();return;}
        if(state.futureLease!=null && state.futureIndex!=next)state.releaseFuture();
        if(state.futureLease==null) {
            var tile=tiles.at(next);
            // Optional look-ahead uses the exact existing 3x3 FULL/halo
            // verification contract; at most one extra lease, never generation.
            state.futureLease=ExistingChunkLoader.openVerifiedLease(
                    level,tile.minX()>>4,tile.minZ()>>4);
            state.futureIndex=next;
        }
        try {
            if(state.futureLease.step()) {
                var validated=state.futureIndex;
                // The lease is FULL and pinned DURING capture only.
                offer(state,level,data,area,tiles,validated);
                state.releaseFuture();
            }
        }catch(RuntimeException failure) {
            state.close();STATES.remove(data);
            throw failure;
        }
    }
    /** Returns a ready, immutable result only for this exact parent/tile. */
    public static BoundedTilePipeline.TileResult result(
            ServerLevel level,WorldPrepSavedData data,UUID batch,long tile,int workers) {
        server(level);
        var state=STATES.get(data);
        if(state==null||state.closed||state.level!=level
                ||!state.batch.equals(batch)||state.workers!=workers
                ||!state.pipeline.has(tile))return null;
        return state.pipeline.poll(tile);
    }
    public static List<Column> snapshot(ServerLevel level,WorldPrepSavedData data,UUID batch,long tile) {
        server(level);
        var state=STATES.get(data);
        if(state==null||state.closed||state.level!=level||!state.batch.equals(batch))
            throw new IllegalStateException("No RUN41 snapshot bound to batch");
        var columns=state.snapshots.get(tile);
        if(columns==null)throw new IllegalStateException("No immutable snapshot for requested tile");
        return columns;
    }
    /** Drop a speculative result and recompute from current world next tick. */
    public static void invalidate(ServerLevel level,WorldPrepSavedData data,UUID batch,long tile) {
        server(level);
        var state=STATES.get(data);
        if(state==null||state.closed||state.level!=level||!state.batch.equals(batch))return;
        state.pipeline.invalidate(tile);
        state.snapshots.remove(tile);
    }
    /**
     * Commit the completed pre-computation for the current exact PREVIEW child.
     * Never publish while the worker is pending or its chunk snapshot stale.
     * BlockState revalidation is still EcologyMutationPlanner.change() on the
     * sole Minecraft server thread; changed tiles are recaptured on next tick.
     */
    public static boolean populate(ServerLevel level,WorldPrepSavedData data,UUID childId,
                                   WorldPrepSavedData.Area area,String pass,
                                   fr.proutlost.worldprep.persistence.BlockWorldPrepData.Plan plan,
                                   int workers) {
        server(level);
        if(!fr.proutlost.worldprep.ecology.BoundedEcologyCandidatePool.supports(pass)
                ||workers<1||workers>4||plan.planId()!=null||!plan.changes.isEmpty()
                ||!plan.pass.equals(pass)||!plan.area.equals(area.id())) {
            throw new IllegalStateException("RUN41 PREVIEW plan admission failed");
        }
        WorldPrepSavedData.Job parent=null;
        for(var job:data.jobs().values()) {
            if(job.operation()!=WorldPrepSavedData.Operation.BATCH_ECOLOGY
                    ||job.state()!=WorldPrepSavedData.JobState.RUNNING
                    ||!childId.equals(job.snapshotId()))continue;
            if(parent!=null)throw new IllegalStateException("Multiple batch parents own RUN41 preview");
            parent=job;
        }
        if(parent==null)throw new IllegalStateException("No exact running parent owns RUN41 child");
        if(parent.cursor()<0 || parent.cursor()%EcologyBatchRuntime.STAGES.size()!=
                EcologyBatchRuntime.STAGES.indexOf("PREVIEW_"+pass)) {
            throw new IllegalStateException("RUN41 pass/cursor/ownership mismatch");
        }
        long tile=parent.cursor()/EcologyBatchRuntime.STAGES.size();
        String expected="proutlost:batch_"+parent.id().toString().replace("-","")+"_"+tile;
        if(!area.id().equals(expected))throw new IllegalStateException("RUN41 tile area id mismatch");
        var result=result(level,data,parent.id(),tile,workers);
        if(result==null)return false;
        var snapshot=snapshot(level,data,parent.id(),tile);
        if(!EcologyParallelPreviewRuntime.verifyStillCurrent(level,data,area,snapshot)) {
            // Own prior stages (notably TREES) may have changed the heightmap.
            // Discard speculative work; the next tick must recapture rather
            // than silently omit newly-eligible or newly-protected columns.
            invalidate(level,data,parent.id(),tile);
            return false;
        }
        for(var candidate:result.forPass(pass)) {
            if(candidate.y()<level.getMinBuildHeight()
                    ||candidate.y()>=level.getMaxBuildHeight())continue;
            var change=EcologyMutationPlanner.change(level,data,area,pass,
                    candidate.x(),candidate.y(),candidate.z());
            if(change!=null)plan.add(change);
        }
        return true;
    }
    /** Read-only DEV probe of ACTUAL independent-tile worker execution. */
    public record Diagnostics(int distinctTilesOffered,int maximumPendingTiles,
                              int peakCpuWorkers,int currentlyPendingTiles) {}
    public static Diagnostics diagnostics(ServerLevel level,WorldPrepSavedData data,UUID batch) {
        server(level);
        var state=STATES.get(data);
        if(state==null||state.closed||state.level!=level||!state.batch.equals(batch))
            throw new IllegalStateException("RUN41 metrics owner missing");
        return new Diagnostics(state.offeredDistinctTiles,state.peakOutstandingTiles,
                state.pipeline.peakConcurrentWorkers(),state.pipeline.outstanding());
    }
    public static void cancel(WorldPrepSavedData data) {
        var state=STATES.remove(data);
        if(state!=null) {server(state.level);state.close();}
    }
    public static void cancelLevel(ServerLevel level) {
        server(level);
        STATES.entrySet().removeIf(entry->{
            if(entry.getValue().level!=level)return false;
            entry.getValue().close();return true;
        });
    }
    private MultiTileEcologyPrefetch() {}
}
