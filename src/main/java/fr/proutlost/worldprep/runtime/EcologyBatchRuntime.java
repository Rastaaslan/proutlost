package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.ecology.EcologyTiles;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData.Job;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData.JobState;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData.Operation;
import net.minecraft.server.level.ServerLevel;
import java.util.List;
import java.util.UUID;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import fr.proutlost.worldprep.storage.BatchManifestStore;
import fr.proutlost.worldprep.persistence.BlockWorldPrepData;

/** One tile/stage or child checkpoint per tick. The parent's snapshotId owns its active child, not a journal. */
public final class EcologyBatchRuntime {
    public static final List<String> STAGES = List.of("STRUCTURES", "PREVIEW_SOILS", "APPLY_SOILS",
            "PREVIEW_TREES", "APPLY_TREES", "PREVIEW_FLORA", "APPLY_FLORA", "PREVIEW_AQUATIC", "APPLY_AQUATIC", "HABITATS");

    // Scoped to the in-memory SavedData instance: metadata reload MUST restart recovery from zero.
    private static final java.util.Map<WorldPrepSavedData, java.util.Map<UUID, Recovery>> RECOVERY =
            new java.util.WeakHashMap<>();
    private static final class Recovery {
        long next;
        final int budget=checkedRecoveryBudget();
        BatchManifestStore.CompletionScan completion;
    }
    private static int checkedRecoveryBudget() {
        int value=Integer.getInteger("proutlost.persistence.recoveryBudget",1);
        if(value<1 || value>4)throw new IllegalStateException("Recovery budget must remain within 1..4");
        return value;
    }
    /** Read-only DEV diagnostics. This is the number of original S2 receipts
     * reconciled against live terrain in this JVM, not the SavedData cursor.
     * It confers no authorization and cannot advance a batch or its journal.
     */
    public static long recoveryVerifiedStages(WorldPrepSavedData data, UUID batch) {
        var owner=RECOVERY.get(data);
        if(owner==null)return 0L;
        var recovery=owner.get(batch);
        return recovery==null?0L:recovery.next;
    }
    public static void process(ServerLevel level, WorldPrepSavedData data, Job parent, int budget) {
        try { processChecked(level,data,parent,budget); }
        catch(IOException e){throw new IllegalStateException("Batch manifest recovery required at cursor "+parent.cursor()+": "+e.getMessage(),e);}
    }
    private static void processChecked(ServerLevel level,WorldPrepSavedData data,Job parent,int budget) throws IOException {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        CandidateGuard.requireCandidate(level);
        if(!BlockMutationRuntime.checkpointReady(level,data))return;
        var area = WorldPrepRuntime.requireArea(data, parent.area());
        var tiles = new EcologyTiles(area.minX(), area.minZ(), area.maxX(), area.maxZ());
        long total = Math.multiplyExact(tiles.count(), STAGES.size());
        Path root=level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("worldprep-v2");
        var header=new BatchManifestStore.Header(1,parent.id(),area.dimension(),area.id(),level.getSeed(),area.minX(),area.minZ(),area.maxX(),area.maxZ(),total,"ecology-batch-v3");
        BatchManifestStore.initialize(root,header);
        if(parent.cursor()<0 || parent.cursor()>total)throw new IOException("Batch cursor outside schedule");
        var recovery=RECOVERY.computeIfAbsent(data,k->new java.util.HashMap<>()).computeIfAbsent(parent.id(),k->new Recovery());
        // Never trust SavedData or a receipt as evidence that dirty terrain reached disk.
        // One old stage per tick bounds recovery; missing chunks pause through the normal runtime.
        if(recovery.next<parent.cursor()){
            // Historical reconciliation only: never advance the durable cursor.
            // Every receipt retains its independent SHA/provenance/terrain check.
            for(int i=0;i<recovery.budget && recovery.next<parent.cursor();i++) {
                // A reconciliation may repair terrain or metadata. Honor the
                // existing durable ACK barrier before touching another receipt.
                if(!BlockMutationRuntime.checkpointReady(level,data))return;
                var recoveryTile=tiles.at(recovery.next/STAGES.size());
                if(!ExistingChunkLoader.ensure(level,parent.id(),recoveryTile.minX()>>4,recoveryTile.minZ()>>4))return;
                reconcileReceipt(level,data,parent,tiles,BatchManifestStore.read(root,header,recovery.next));
                recovery.next++;
            }
            return;
        }
        if (parent.cursor() == total) {
            MultiTileEcologyPrefetch.cancel(data);
            ExistingChunkLoader.release(level);
            if(recovery.completion==null)recovery.completion=new BatchManifestStore.CompletionScan(root,header);
            if(recovery.completion.step()){
                data.jobs().put(parent.id(), parent.withState(JobState.COMPLETED)); data.changed();
                level.getDataStorage().save();
            }
            return;
        }
        long index = parent.cursor() / STAGES.size();
        var tile = tiles.at(index);
        if(!ExistingChunkLoader.ensure(level,parent.id(),tile.minX()>>4,tile.minZ()>>4))return;
        if(fr.proutlost.worldprep.WorldPrepConfig.MULTITILE_PREFETCH.get()) {
            if(fr.proutlost.worldprep.WorldPrepConfig.PARALLEL_ECOLOGY_PLANNING.get())
                throw new IllegalStateException("RUN41 multitile and RUN39 single-tile pools are mutually exclusive");
            MultiTileEcologyPrefetch.tick(level,data,parent,tiles,index,
                    fr.proutlost.worldprep.WorldPrepConfig.MULTITILE_WORKERS.get());
        }
        String tileId = "proutlost:batch_" + parent.id().toString().replace("-", "") + "_" + index;
        var tileArea = new WorldPrepSavedData.Area(tileId, area.dimension(), tile.minX(), tile.minZ(), tile.maxX(), tile.maxZ());
        var previous = data.areas().putIfAbsent(tileId, tileArea);
        if (previous != null && !previous.equals(tileArea)) throw new IllegalStateException("Persisted tile bounds changed");
        String stage = STAGES.get((int)(parent.cursor() % STAGES.size()));
        if(Files.exists(BatchManifestStore.stagePath(root,parent.id(),parent.cursor()))){
            var receipt=BatchManifestStore.read(root,header,parent.cursor());
            if(!receipt.tileId().equals(tileId)||receipt.tileIndex()!=index||!receipt.stage().equals(stage))throw new IOException("Stage schedule mismatch");
            reconcileReceipt(level,data,parent,tiles,receipt);
            advance(level,data,parent);return;
        }
        if (stage.equals("STRUCTURES") || stage.equals("HABITATS")) {
            try {
                Path export=DiscoveryExporter.export(level,tileId);
                String artifact="discovery/"+export.getFileName();
                BatchManifestStore.record(root,header,new BatchManifestStore.Stage(1,parent.id(),parent.cursor(),index,tileId,stage,
                        artifact,BatchManifestStore.artifactHash(root,artifact),"DISCOVERY_ONLY_NOT_DIRECTOR_READY","COMPLETED",null,null,null,0,0,
                        BatchManifestStore.previousHash(root,parent.id(),parent.cursor())));
            }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Discovery interrupted", e); }
            catch (java.io.IOException e) { throw new IllegalStateException("Discovery failed", e); }
            advance(level, data, parent);
            return;
        }
        UUID childId = parent.snapshotId();
        if (childId == null) {
            // Calling prepare here creates the exact journal before any mutation. The child remains PAUSED
            // so it can never independently run after parent cancellation, failure or a server restart.
            childId = UUID.randomUUID();
            var child = new Job(childId, Operation.valueOf(stage), tileId, JobState.PAUSED, 0, "", null);
            data.jobs().put(childId, child);
            data.jobs().put(parent.id(), parent.withSnapshot(childId));
            data.changed();
            try { BlockMutationRuntime.prepare(level, data, childId, child.operation(), tileArea); }
            catch (RuntimeException e) { data.jobs().put(childId, child.failed(e.toString())); throw e; }
            level.getDataStorage().save();
            return;
        }
        var child = data.jobs().get(childId);
        if (child == null || !child.area().equals(tileId) || !child.operation().name().equals(stage))
            throw new IllegalStateException("Batch child ownership mismatch");
        if (child.state() == JobState.COMPLETED) { reconcileChild(level,data,child); recordChild(root,header,parent,child,index,stage,level);advance(level, data, parent); return; }
        if (child.state() != JobState.PAUSED) throw new IllegalStateException("Batch child needs recovery: " + child.state());
        try {
            BlockMutationRuntime.process(level, data, childId, child, Math.min(256, budget));
            var updated = data.jobs().get(childId);
            if (updated.state() == JobState.COMPLETED) {recordChild(root,header,parent,updated,index,stage,level);advance(level, data, parent);}
            else { data.jobs().put(childId, updated.withState(JobState.PAUSED)); data.changed(); }
        } catch (fr.proutlost.worldprep.world.ServerExistingChunkAccess.Refused e) { throw e; }
        catch (RuntimeException e) { data.jobs().put(childId, data.jobs().get(childId).failed(e.toString())); data.changed(); throw e; }
    }
    private static void reconcileReceipt(ServerLevel level,WorldPrepSavedData data,Job parent,EcologyTiles tiles,
                                         BatchManifestStore.Stage receipt) throws IOException {
        long index=receipt.sequence()/STAGES.size();
        String tileId="proutlost:batch_"+parent.id().toString().replace("-","")+"_"+index;
        String stage=STAGES.get((int)(receipt.sequence()%STAGES.size()));
        var tile=tiles.at(index);
        var expected=new WorldPrepSavedData.Area(tileId,WorldPrepRuntime.requireArea(data,parent.area()).dimension(),
                tile.minX(),tile.minZ(),tile.maxX(),tile.maxZ());
        if(receipt.tileIndex()!=index||!receipt.tileId().equals(tileId)||!receipt.stage().equals(stage)
                ||!expected.equals(data.areas().get(tileId)))throw new IOException("Recovery schedule/bounds mismatch");
        if(stage.startsWith("PREVIEW_")||stage.startsWith("APPLY_")){
            var child=data.jobs().get(receipt.childId());
            var plan=BlockMutationRuntime.data(level).plans().get(BlockWorldPrepData.planKey(tileId,stage.substring(stage.indexOf('_')+1)));
            if(child==null||!child.area().equals(tileId)||!child.operation().name().equals(stage)
                    ||plan==null||!java.util.Objects.equals(plan.planId(),receipt.planId())
                    ||!java.util.Objects.equals(child.snapshotId(),receipt.snapshotId())
                    ||plan.entryCount()!=receipt.plannedChanges())throw new IOException("Recovery child/plan/journal ownership mismatch at receipt "+receipt.sequence()+" child="+receipt.childId()+"; preserve files, offline exact-state repair required");
            var recovered=receiptChildCandidate(parent,child,receipt);
            // Do not publish COMPLETED merely because a receipt exists. This validates
            // exact plan/journal identity, full journal count and terrain ownership first.
            reconcileChild(level,data,recovered);
            if(!recovered.equals(child)) {
                data.jobs().put(child.id(),recovered);data.changed();level.getDataStorage().save();
            }
        }
    }
    public static Job receiptChildCandidate(Job parent,Job child,BatchManifestStore.Stage receipt) throws IOException {
        if(child==null)throw new IOException("Missing child ownership metadata; offline exact-state repair required");
        if(!child.id().equals(receipt.childId())||!child.area().equals(receipt.tileId())
                ||!child.operation().name().equals(receipt.stage())
                ||!java.util.Objects.equals(child.snapshotId(),receipt.snapshotId()))
            throw new IOException("Receipt child identity mismatch");
        if(child.state()==JobState.COMPLETED)return child;
        if(child.state()!=JobState.PAUSED||!receipt.stage().startsWith("APPLY_")
                ||parent.cursor()!=receipt.sequence()||!child.id().equals(parent.snapshotId())
                ||child.cursor()<0||child.cursor()>receipt.plannedChanges()
                ||receipt.appliedPlanEntries()!=receipt.plannedChanges())
            throw new IOException("Receipt ahead of SavedData without exact active APPLY ownership; offline repair required");
        return child.withCursor(receipt.plannedChanges()).withState(JobState.COMPLETED);
    }
    private static void reconcileChild(ServerLevel level,WorldPrepSavedData data,Job child) {
        if(child.operation().name().startsWith("APPLY_"))
            BlockMutationRuntime.reconcileCompleted(level,data,child);
    }
    private static void recordChild(Path root,BatchManifestStore.Header header,Job parent,Job child,long tile,String stage,ServerLevel level) throws IOException {
        String pass=stage.substring(stage.indexOf('_')+1);
        var plan=BlockMutationRuntime.data(level).plans().get(BlockWorldPrepData.planKey(child.area(),pass));
        if(plan==null||plan.planId()==null)throw new IOException("Completed child has no sealed plan");
        String artifact="plans/"+plan.planId()+"/manifest.wpm";
        BatchManifestStore.record(root,header,new BatchManifestStore.Stage(1,parent.id(),parent.cursor(),tile,child.area(),stage,
                artifact,BatchManifestStore.artifactHash(root,artifact),"VANILLA_DEVELOPMENT_PALETTE","COMPLETED",child.id(),plan.planId(),child.snapshotId(),plan.entryCount(),stage.startsWith("APPLY_")?plan.entryCount():0,
                BatchManifestStore.previousHash(root,parent.id(),parent.cursor())));
    }
    private static void advance(ServerLevel level, WorldPrepSavedData data, Job parent) {
        data.jobs().put(parent.id(), parent.withCursor(parent.cursor() + 1).withSnapshot(null));
        data.changed();
        level.getDataStorage().save();
    }
    private EcologyBatchRuntime() {}
}
