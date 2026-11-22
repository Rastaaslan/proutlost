package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.geology.GeologyPlanner;
import fr.proutlost.worldprep.persistence.BlockWorldPrepData;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import fr.proutlost.worldprep.storage.PagedPlanStore;
import fr.proutlost.worldprep.storage.PlanSelectionTrace;
import fr.proutlost.worldprep.storage.PlanManifest;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Shared incremental executor for deterministic BlockState plans and owned rollback journals. */
public final class BlockMutationRuntime {
    private static final java.util.Map<ServerLevel,java.util.Map<UUID,BlockJournalRecovery>> RECOVERIES=new java.util.WeakHashMap<>();
    private static final java.util.Map<ServerLevel,java.util.LinkedHashSet<UUID>> STANDALONE_RECOVERY=new java.util.WeakHashMap<>();
    private static final org.slf4j.Logger RECOVERY_LOG=org.slf4j.LoggerFactory.getLogger(BlockMutationRuntime.class);
    private static final class BlockJournalRecovery {
        final WorldPrepSavedData owner;
        final fr.proutlost.worldprep.storage.PagedJournalStore.Manifest manifest;
        final long firstPage;
        final boolean completedRecovery;
        final BiomeApplyRecoveryCursor cursor;
        BlockJournalRecovery(WorldPrepSavedData owner,fr.proutlost.worldprep.storage.PagedJournalStore.Manifest manifest,long firstPage,boolean completedRecovery){
            this.owner=owner;this.manifest=manifest;this.firstPage=firstPage;this.completedRecovery=completedRecovery;
            if(firstPage<0||firstPage>manifest.pages().size())throw new IllegalArgumentException("Journal suffix outside manifest");
            cursor=new BiomeApplyRecoveryCursor(manifest.pages().size()-firstPage);
        }
    }
    public record JournalRecoveryProgress(long firstPage,long prefixPages,long prefixCells,BiomeApplyRecoveryCursor.Progress cursor){}
    /** Read-only diagnostics; this volatile cursor never replaces the durable journal. */
    public static java.util.Optional<JournalRecoveryProgress> blockRecoveryProgress(ServerLevel level,UUID job){
        var states=RECOVERIES.get(level);var r=states==null?null:states.get(job);
        return r==null?java.util.Optional.empty():java.util.Optional.of(new JournalRecoveryProgress(r.firstPage,r.manifest.pages().size(),r.manifest.entryCount(),r.cursor.progress()));
    }
    public static boolean standaloneRecoveryPending(ServerLevel level,UUID job){
        var requests=STANDALONE_RECOVERY.get(level);return requests!=null&&requests.contains(job);
    }
    static void discardRecovery(ServerLevel level,UUID id){
        var states=RECOVERIES.get(level);
        if(states!=null){
            states.remove(id);
            var job=WorldPrepRuntime.data(level).jobs().get(id);
            if(job!=null&&job.operation()==WorldPrepSavedData.Operation.BATCH_ECOLOGY&&job.snapshotId()!=null)states.remove(job.snapshotId());
            if(states.isEmpty())RECOVERIES.remove(level);
        }
        var requests=STANDALONE_RECOVERY.get(level);
        if(requests!=null){requests.remove(id);if(requests.isEmpty())STANDALONE_RECOVERY.remove(level);}
    }
    static void discardLevelRecovery(ServerLevel level){RECOVERIES.remove(level);STANDALONE_RECOVERY.remove(level);}
    private static void pruneRecoveries(ServerLevel level,WorldPrepSavedData data){
        var states=RECOVERIES.get(level);if(states==null)return;
        states.entrySet().removeIf(e->{
            var j=data.jobs().get(e.getKey());if(j==null||e.getValue().owner!=data)return true;
            if(j.state()==WorldPrepSavedData.JobState.RUNNING||j.state()==WorldPrepSavedData.JobState.QUEUED)return false;
            if(e.getValue().completedRecovery)return !standaloneRecoveryPending(level,j.id())
                &&data.jobs().values().stream().noneMatch(p->p.operation()==WorldPrepSavedData.Operation.BATCH_ECOLOGY
                    &&(p.state()==WorldPrepSavedData.JobState.RUNNING||p.state()==WorldPrepSavedData.JobState.QUEUED)
                    &&WorldPrepRuntime.requireArea(data,p.area()).overlaps(WorldPrepRuntime.requireArea(data,j.area())));
            return data.jobs().values().stream().noneMatch(p->p.operation()==WorldPrepSavedData.Operation.BATCH_ECOLOGY
                &&(p.state()==WorldPrepSavedData.JobState.RUNNING||p.state()==WorldPrepSavedData.JobState.QUEUED)
                &&j.id().equals(p.snapshotId()));
        });
        if(states.isEmpty())RECOVERIES.remove(level);
    }
    private static BlockJournalRecovery recovery(ServerLevel level,WorldPrepSavedData data,UUID id,
            fr.proutlost.worldprep.storage.PagedJournalStore.Manifest manifest,boolean completed){
        var states=RECOVERIES.computeIfAbsent(level,k->new java.util.HashMap<>());var r=states.get(id);
        if(r==null||r.owner!=data||r.completedRecovery!=completed){r=new BlockJournalRecovery(data,manifest,0,completed);states.put(id,r);}
        if(!r.manifest.equals(manifest))throw new IllegalStateException("Block journal changed outside the active recovery slice");
        return r;
    }

    private static final java.util.Map<ServerLevel,java.util.Map<UUID,BlockPreview>> PREVIEWS=new java.util.WeakHashMap<>();
    private static final class BlockPreview {
        final BlockWorldPrepData.Plan plan;final PagedPlanStore.Writer<BlockWorldPrepData.Change> writer;long scanned;int maskX=Integer.MIN_VALUE,maskZ;boolean maskAccepted;int columnX=Integer.MIN_VALUE,columnZ;EcologyMutationPlanner.Column column;TreeMutationPlanner.Candidate tree;
        BlockPreview(BlockWorldPrepData.Plan plan,PagedPlanStore.Writer<BlockWorldPrepData.Change> writer){this.plan=plan;this.writer=writer;}
    }
    static void prunePreviews(ServerLevel level,WorldPrepSavedData data){
        pruneRecoveries(level,data);
        var previews=PREVIEWS.get(level);if(previews!=null){previews.keySet().removeIf(id->{var job=data.jobs().get(id);return job==null||!(job.state()==WorldPrepSavedData.JobState.RUNNING||job.state()==WorldPrepSavedData.JobState.QUEUED||job.state()==WorldPrepSavedData.JobState.PAUSED);});if(previews.isEmpty())PREVIEWS.remove(level);}
    }
    static void forgetPreview(ServerLevel level,UUID id){var previews=PREVIEWS.get(level);if(previews!=null)previews.remove(id);}
    private static final TagKey<Block> GEOLOGY=tag("geology_replaceable"),STONE=tag("ore_replaceable/stone"),DEEPSLATE=tag("ore_replaceable/deepslate");
    private record Ore(String name,String stone,String deep,int min,int max,int rarity,long salt){}
    private static final Ore[] ORES={new Ore("coal","coal_ore","deepslate_coal_ore",0,192,95,11),new Ore("iron","iron_ore","deepslate_iron_ore",-64,128,120,17),new Ore("copper","copper_ore","deepslate_copper_ore",-16,112,150,23),new Ore("gold","gold_ore","deepslate_gold_ore",-64,32,260,29),new Ore("redstone","redstone_ore","deepslate_redstone_ore",-64,16,180,31),new Ore("lapis","lapis_ore","deepslate_lapis_ore",-64,48,300,37),new Ore("diamond","diamond_ore","deepslate_diamond_ore",-64,16,650,41),new Ore("emerald","emerald_ore","deepslate_emerald_ore",32,256,900,43)};

    public static BlockWorldPrepData data(ServerLevel level){return fr.proutlost.worldprep.persistence.PagedSavedDataRuntime.withServerLevel(level,BlockWorldPrepData.FILE_ID,()->level.getDataStorage().computeIfAbsent(BlockWorldPrepData.FACTORY,BlockWorldPrepData.FILE_ID));}
    /** Poll on the server thread; autosaves use the same per-store admission gate. */
    public static boolean checkpointReady(ServerLevel level, WorldPrepSavedData jobs) {
        var blocks=data(level);
        boolean jobsReady=jobs.checkpointReady(), blocksReady=blocks.checkpointReady();
        if(jobsReady && blocksReady)return true;
        level.getDataStorage().save();
        return false;
    }

    public static boolean isBlockOperation(WorldPrepSavedData.Operation operation){return switch(operation){case PREVIEW_GEOLOGY,APPLY_GEOLOGY,ROLLBACK_GEOLOGY,PREVIEW_ORES,APPLY_ORES,ROLLBACK_ORES,PREVIEW_SOILS,APPLY_SOILS,ROLLBACK_SOILS,PREVIEW_FLORA,APPLY_FLORA,ROLLBACK_FLORA,PREVIEW_AQUATIC,APPLY_AQUATIC,ROLLBACK_AQUATIC,PREVIEW_TREES,APPLY_TREES,ROLLBACK_TREES->true;default->false;};}

    public static void prepare(ServerLevel level,WorldPrepSavedData data,UUID id,WorldPrepSavedData.Operation operation,WorldPrepSavedData.Area area){
        if(!level.getServer().isSameThread())throw new IllegalStateException("WorldPrep requires the server thread");
        String pass=pass(operation);if(EcologyMutationPlanner.supports(pass))EcologyMutationPlanner.requireBounded(area);var blocks=data(level);requireCompatible(blocks);
        if(isApply(operation)){
            var plan=requirePlan(level,data,area,pass);var snapshotId=UUID.randomUUID();
            var journal=new BlockWorldPrepData.Journal(snapshotId,id,plan.planId(),area.id(),pass,plan.fingerprint());RuntimePageJournal.initialize(level,journal);blocks.journals().put(snapshotId,journal);
            data.jobs().put(id,data.jobs().get(id).withSnapshot(snapshotId));blocks.changed();data.changed();level.getDataStorage().save();
        }else if(isRollback(operation)){
            var requested=data.jobs().get(id).snapshotId();var journal=blocks.journals().get(requested);
            if(journal==null||!journal.area.equals(area.id())||!journal.pass.equals(pass))throw new IllegalStateException("Exact rollback journal ownership mismatch");
        }
    }

    public static void process(ServerLevel level,WorldPrepSavedData data,UUID id,WorldPrepSavedData.Job job,int budget){
        if(!level.getServer().isSameThread())throw new IllegalStateException("WorldPrep requires the server thread");
        if(!checkpointReady(level,data))return;
        var area=WorldPrepRuntime.requireArea(data,job.area());String pass=pass(job.operation());if(EcologyMutationPlanner.supports(pass))EcologyMutationPlanner.requireBounded(area);var blocks=data(level);requireCompatible(blocks);
        if(isRollback(job.operation())){rollback(level,data,blocks,id,job,area,pass,budget);return;}
        String key=BlockWorldPrepData.planKey(area.id(),pass);var plan=blocks.plans().get(key);
        if(job.operation().name().startsWith("PREVIEW")){
            if(job.cursor()==0&&fr.proutlost.worldprep.ecology.BoundedEcologyCandidatePool.supports(pass)
                    &&(fr.proutlost.worldprep.WorldPrepConfig.MULTITILE_PREFETCH.get()&&area.id().startsWith("proutlost:batch_")
                       ||fr.proutlost.worldprep.WorldPrepConfig.PARALLEL_ECOLOGY_PLANNING.get())){
                var parallelPlan=new BlockWorldPrepData.Plan(area.id(),pass,"development",input(level,data,area,pass),"");
                boolean ready=fr.proutlost.worldprep.WorldPrepConfig.MULTITILE_PREFETCH.get()&&area.id().startsWith("proutlost:batch_")
                    ? MultiTileEcologyPrefetch.populate(level,data,id,area,pass,parallelPlan,fr.proutlost.worldprep.WorldPrepConfig.MULTITILE_WORKERS.get())
                    : EcologyParallelPreviewRuntime.populate(level,data,id,area,pass,parallelPlan,fr.proutlost.worldprep.WorldPrepConfig.PARALLEL_ECOLOGY_WORKERS.get());
                if(!ready)return;
                publish(level,data,area,parallelPlan,true);blocks.plans().put(key,parallelPlan);
                if(pass.equals("GEOLOGY"))blocks.plans().remove(BlockWorldPrepData.planKey(area.id(),"ORES"));
                blocks.changed();data.jobs().put(id,job.withCursor(parallelPlan.entryCount()).withState(WorldPrepSavedData.JobState.COMPLETED));data.changed();return;
            }
            previewSlice(level,data,blocks,id,job,area,pass,budget);return;
        }
        plan=requirePlan(level,data,area,pass);
        applyBatch(level,data,blocks,id,job,area,plan,budget);
    }

    /** Explicit request only. The original completed job and journal remain unchanged;
     * the server tick performs bounded recovery and a new JVM requires another explicit request. */
    public static void recoverStandalone(ServerLevel level, WorldPrepSavedData data, UUID id) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("Server thread required");
        if(!fr.proutlost.worldprep.WorldPrepConfig.ENABLED.get())throw new IllegalStateException("WorldPrep disabled");
        if(fr.proutlost.worldprep.ecology.ExecutionSafety.check(level.getServer().getPlayerCount(),
                level.getServer().getAverageTickTimeNanos(),fr.proutlost.worldprep.WorldPrepConfig.MAX_TICK_MILLIS.get())
                !=fr.proutlost.worldprep.ecology.ExecutionSafety.Stop.NONE)throw new IllegalStateException("Recovery safety stop");
        if(!checkpointReady(level,data))throw new IllegalStateException("Checkpoint pending; retry recovery after acknowledgement");
        requireStandaloneRecovery(data,id);
        discardRecovery(level,id);
        STANDALONE_RECOVERY.computeIfAbsent(level,k->new java.util.LinkedHashSet<>()).add(id);
        RECOVERY_LOG.info("WorldPrep standalone recovery queued for {}",id);
    }

    /** Returns true when this tick belongs to an explicit, finite recovery request. */
    static boolean tickStandaloneRecovery(ServerLevel level,WorldPrepSavedData data,int budget){
        var requests=STANDALONE_RECOVERY.get(level);if(requests==null||requests.isEmpty())return false;
        UUID id=requests.iterator().next();
        try{
            requireStandaloneRecovery(data,id);
            if(!checkpointReady(level,data))return true;
            if(reconcileCompleted(level,data,data.jobs().get(id),budget)){
                discardRecovery(level,id);RECOVERY_LOG.info("WorldPrep standalone recovery completed for {}",id);
            }
        }catch(RuntimeException failure){
            discardRecovery(level,id);RECOVERY_LOG.warn("WorldPrep standalone recovery refused for "+id,failure);
        }
        return true;
    }

    public static void requireStandaloneRecovery(WorldPrepSavedData data, UUID id) {
        var job = data.jobs().get(id);
        if (job == null || job.state() != WorldPrepSavedData.JobState.COMPLETED
                || !java.util.Set.of("APPLY_SOILS", "APPLY_TREES", "APPLY_FLORA", "APPLY_AQUATIC").contains(job.operation().name())
                || job.snapshotId() == null) throw new IllegalStateException("Completed ecology APPLY with exact journal required");
        if (job.area().startsWith("proutlost:batch_")) throw new IllegalStateException("Recover batch through parent UUID");
        var area = WorldPrepRuntime.requireArea(data, job.area());
        boolean later = false;
        for (var other : data.jobs().values()) {
            if (other.id().equals(id)) { later = true; continue; }
            if (other.operation().name().startsWith("ROLLBACK_") && job.snapshotId().equals(other.snapshotId()))
                throw new IllegalStateException("Journal has rollback history; apply recovery refused");
            boolean destructive = other.operation().name().startsWith("APPLY_") || other.operation().name().startsWith("ROLLBACK_")
                    || other.operation() == WorldPrepSavedData.Operation.BATCH_ECOLOGY;
            if (destructive && (later || other.operation() == WorldPrepSavedData.Operation.BATCH_ECOLOGY
                    || other.state() == WorldPrepSavedData.JobState.RUNNING || other.state() == WorldPrepSavedData.JobState.QUEUED
                    || other.state() == WorldPrepSavedData.JobState.PAUSED)
                    && area.overlaps(WorldPrepRuntime.requireArea(data, other.area())))
                throw new IllegalStateException("Overlapping later operation or batch; recovery refused");
        }
    }

    /** A completed receipt is reconciled in bounded slices before its caller may advance. */
    public static boolean reconcileCompleted(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Job job){
        return reconcileCompleted(level,data,job,fr.proutlost.worldprep.WorldPrepConfig.CELLS_PER_TICK.get());
    }
    public static boolean reconcileCompleted(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Job job,int budget){
        return reconcileCompleted(level,data,job,budget,EcologyRecoveryHistory.EMPTY);
    }
    static boolean reconcileCompleted(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Job job,int budget,EcologyRecoveryHistory history){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Server thread required");
        if(job.state()!=WorldPrepSavedData.JobState.COMPLETED||!job.operation().name().startsWith("APPLY_"))
            throw new IllegalStateException("Completed apply required");
        var area=WorldPrepRuntime.requireArea(data,job.area());String pass=pass(job.operation());
        if(!EcologyMutationPlanner.supports(pass))throw new IllegalStateException("Ecology recovery only");
        EcologyMutationPlanner.requireBounded(area);
        var blocks=data(level);var plan=requirePlan(level,data,area,pass,history);var journal=requireJournal(blocks,job,area,plan);
        long previousPages=journal.pageCount(),previousEntries=journal.entryCount();String previousRoot=journal.journalRoot();
        var manifest=RuntimePageJournal.requireMetadata(level,journal);
        if(manifest.entryCount()!=plan.entryCount())throw new IllegalStateException("Completed child has incomplete journal");
        var r=recovery(level,data,job.id(),manifest,true);
        boolean[] replayed={false};
        advanceApplyRecovery(level,data,area,plan,journal,r,budget,replayed);
        if(recoveryPersistenceRequired(replayed[0],previousPages,journal.pageCount(),previousEntries,journal.entryCount(),previousRoot,journal.journalRoot())){
            blocks.changed();level.getDataStorage().save();
        }
        boolean ready=r.cursor.progress().phase()==BiomeApplyRecoveryCursor.Phase.READY;
        if(ready&&!checkpointReady(level,data))return false;
        if(ready){var states=RECOVERIES.get(level);states.remove(job.id());if(states.isEmpty())RECOVERIES.remove(level);}
        return ready;
    }

    /** Exact persistence decision for recovery, without changing admission rules. */
    static boolean recoveryPersistenceRequired(boolean terrainReplayed,
            long oldPages,long newPages,long oldEntries,long newEntries,
            String oldRoot,String newRoot) {
        return terrainReplayed||oldPages!=newPages||oldEntries!=newEntries
            ||!java.util.Objects.equals(oldRoot,newRoot);
    }

    /** Scans at most budget source positions. Unsealed previews restart safely after process loss. */
    private static void previewSlice(ServerLevel level,WorldPrepSavedData data,BlockWorldPrepData blocks,UUID id,
            WorldPrepSavedData.Job job,WorldPrepSavedData.Area area,String pass,int budget){
        try{
            var previews=PREVIEWS.computeIfAbsent(level,k->new java.util.HashMap<>());var preview=previews.get(id);
            if(preview==null||preview.scanned!=job.cursor()){
                if(job.cursor()!=0)throw new WorldPrepRuntime.PreviewResumeRequired();
                BlockWorldPrepData.Plan upstream=null;
                if(pass.equals("ORES")){
                    upstream=blocks.plans().get(BlockWorldPrepData.planKey(area.id(),"GEOLOGY"));
                    if(upstream==null)throw new IllegalStateException("ORES requires exact GEOLOGY plan");
                    if(upstream.planId()==null)publish(level,data,area,upstream);
                }
                var plan=new BlockWorldPrepData.Plan(area.id(),pass,"development",input(level,data,area,pass),"");plan.planId(UUID.randomUUID());
                if(upstream!=null){plan.upstreamPlanId(upstream.planId());plan.upstreamRoot(upstream.fingerprint());}
                var writer=PagedPlanStore.begin(RuntimePlanStore.root(level),plan.planId(),RuntimePlanStore.pass(pass),area.id(),area.dimension(),
                    plan.profile,PlanFingerprint.of(plan.input).value(),RuntimePlanStore.ENGINE_VERSION,PlanSelectionTrace.PLANNER_VERSION,
                    RuntimePlanStore.ENTRIES_PER_PAGE,RuntimePlanStore.BLOCK_CODEC);
                preview=new BlockPreview(plan,writer);previews.put(id,preview);job=job.withCursor(0);data.jobs().put(id,job);data.changed();
            }
            if(!preview.plan.input.equals(input(level,data,area,pass)))throw new IllegalStateException("Stale incremental "+pass+" preview");
            if(pass.equals("ORES")){
                var upstream=blocks.plans().get(BlockWorldPrepData.planKey(area.id(),"GEOLOGY"));
                if(upstream==null||!preview.plan.upstreamPlanId().equals(upstream.planId())||!preview.plan.upstreamRoot().equals(upstream.fingerprint()))
                    throw new IllegalStateException("ORES exact GEOLOGY plan identity changed");
            }
            long height=level.getMaxBuildHeight()-level.getMinBuildHeight(),width=(long)area.maxX()-area.minX()+1;
            long total=width*((long)area.maxZ()-area.minZ()+1)*height;int remaining=Math.max(1,budget);
            while(remaining-->0&&preview.scanned<total){
                long cursor=preview.scanned;int y=level.getMinBuildHeight()+(int)(cursor%height);long column=cursor/height;
                int x=area.minX()+(int)(column%width),z=area.minZ()+(int)(column/width);
                if(preview.maskX!=x||preview.maskZ!=z){preview.maskX=x;preview.maskZ=z;preview.maskAccepted=MinecraftTerrainSampler.matchesMask(level,area.mask(),x,z,1,1);}
                var change=preview.maskAccepted?plannedChange(level,data,preview.plan,area,x,y,z,preview):null;if(change!=null)preview.writer.appendSelected(change,PlanSelectionTrace.Selection.DIRECT);
                preview.scanned++;
            }
            data.jobs().put(id,job.withCursor(preview.scanned));data.changed();if(preview.scanned<total)return;
            var completed=preview.writer.finish(Math.max(1,budget/RuntimePlanStore.ENTRIES_PER_PAGE));if(completed.isEmpty())return;
            var manifest=completed.orElseThrow().manifest();preview.plan.entryCount(manifest.pages().stream().mapToLong(PlanManifest.PageReference::entryCount).sum());
            preview.plan.fingerprint(manifest.rootFingerprint());blocks.plans().put(BlockWorldPrepData.planKey(area.id(),pass),preview.plan);
            if(pass.equals("GEOLOGY"))blocks.plans().remove(BlockWorldPrepData.planKey(area.id(),"ORES"));blocks.changed();
            data.jobs().put(id,data.jobs().get(id).withCursor(preview.plan.entryCount()).withState(WorldPrepSavedData.JobState.COMPLETED));data.changed();previews.remove(id);
        }catch(IOException e){throw new IllegalStateException("Incremental block preview failed",e);}
    }

    /** Publish intent before writes; recover each immutable prefix once, and each own suffix once. */
    private static void applyBatch(ServerLevel level,WorldPrepSavedData data,BlockWorldPrepData blocks,UUID id,WorldPrepSavedData.Job job,WorldPrepSavedData.Area area,BlockWorldPrepData.Plan plan,int budget){
        var journal=requireJournal(blocks,job,area,plan);
        long previousPages=journal.pageCount(),previousEntries=journal.entryCount();String previousRoot=journal.journalRoot();
        var manifest=RuntimePageJournal.requireMetadata(level,journal);
        if(manifest.entryCount()>plan.entryCount())throw new IllegalStateException("Journal exceeds exact plan");
        var r=recovery(level,data,id,manifest,false);boolean[] replayed={false};
        int used=advanceApplyRecovery(level,data,area,plan,journal,r,budget,replayed);
        if(recoveryPersistenceRequired(replayed[0],previousPages,journal.pageCount(),previousEntries,journal.entryCount(),previousRoot,journal.journalRoot())){
            blocks.changed();level.getDataStorage().save();
        }
        if(r.cursor.progress().phase()!=BiomeApplyRecoveryCursor.Phase.READY)return;
        long durable=manifest.entryCount();
        // The durable cursor follows actual reconciliation, never merely published intent.
        if(job.cursor()!=durable){data.jobs().put(id,job.withCursor(durable));data.changed();level.getDataStorage().save();return;}
        if(used>=budget)return;
        int count=BlockCheckpointPolicy.batchLength(durable,Math.toIntExact(plan.entryCount()),budget-used);
        if(count==0){data.jobs().put(id,job.withCursor(durable).withState(WorldPrepSavedData.JobState.COMPLETED));data.changed();level.getDataStorage().save();return;}
        List<BlockWorldPrepData.Change> changes=new ArrayList<>();
        try{PagedPlanStore.readRange(RuntimePlanStore.directory(level,plan.planId()),manifest(level,plan),RuntimePlanStore.BLOCK_CODEC,durable,count,changes::add);}
        catch(IOException e){throw new IllegalStateException("Paged plan validation failed",e);}
        if(changes.size()!=count)throw new IllegalStateException("Paged plan ended before sealed entry count");
        for(var change:changes){
            validateBounds(level,area,change);var pos=new BlockPos(change.x(),change.y(),change.z());
            var loaded=ServerExistingChunkAccess.requireBlock(level,area,change.x(),change.y(),change.z());
            if(!loaded.getBlockState(pos).equals(decode(change.before()))||!safeAtBoundary(level,data,area,plan,change,pos))throw ownership(pos);
        }
        var advanced=RuntimePageJournal.publish(level,journal,List.copyOf(changes));
        if(advanced.pages().size()<=manifest.pages().size()
                ||!advanced.pages().subList(0,manifest.pages().size()).equals(manifest.pages())
                ||advanced.entryCount()-manifest.entryCount()!=changes.size()
                ||!advanced.journalId().equals(manifest.journalId())||!advanced.planId().equals(manifest.planId())
                ||!advanced.planRoot().equals(manifest.planRoot())||advanced.pass()!=manifest.pass()
                ||!advanced.dimension().equals(manifest.dimension())||!advanced.area().equals(manifest.area()))
            throw new IllegalStateException("Own block journal append changed immutable prefix or identity");
        // Only the newly published suffix is pending. A cold start or pause discards this cache
        // and starts a full-prefix validation barrier again.
        RECOVERIES.get(level).put(id,new BlockJournalRecovery(data,advanced,manifest.pages().size(),false));
        blocks.changed();data.changed();level.getDataStorage().save();
        // checkpointReady on the next invocation must acknowledge the exact published journal.
    }

    /** Completion metadata is not ownership: later writers can recreate identical values. */
    public static void requireRollbackRecoveryOwnership(WorldPrepSavedData data, WorldPrepSavedData.Job job) {
        if (!EcologyMutationPlanner.supports(pass(job.operation()))) return;
        var area = WorldPrepRuntime.requireArea(data, job.area());
        boolean afterFirstRollback = false;
        boolean found = false;
        for (var other : data.jobs().values()) {
            if (other.id().equals(job.id())) found = true;
            // Use the first attempt, so enqueueing another rollback cannot bypass supersession.
            if (other.operation().name().startsWith("ROLLBACK_") && job.snapshotId() != null
                    && job.snapshotId().equals(other.snapshotId())) {
                afterFirstRollback = true;
                continue;
            }
            if (!afterFirstRollback) continue;
            boolean mutation = other.operation().name().startsWith("APPLY_")
                    || other.operation().name().startsWith("ROLLBACK_")
                    || other.operation() == WorldPrepSavedData.Operation.BATCH_ECOLOGY;
            if (mutation && area.overlaps(WorldPrepRuntime.requireArea(data, other.area())))
                throw new IllegalStateException("Rollback superseded by overlapping mutation " + other.id());
        }
        if (!found || !afterFirstRollback) throw new IllegalStateException("Rollback ownership history missing");
    }

    private static void rollback(ServerLevel level,WorldPrepSavedData data,BlockWorldPrepData blocks,UUID id,WorldPrepSavedData.Job job,WorldPrepSavedData.Area area,String pass,int budget){
        var journal=blocks.journals().get(job.snapshotId());
        if(journal==null||!journal.area.equals(area.id())||!journal.pass.equals(pass))throw new IllegalStateException("Snapshot ownership mismatch");
        requireRollbackRecoveryOwnership(data,job);
        var manifest=RuntimePageJournal.requireMetadata(level,journal);var r=recovery(level,data,id,manifest,false);
        r.cursor.advance(budget,page->manifest.pages().get(Math.toIntExact(page)).entryCount(),
            (page,offset,count,phase)->RuntimePageJournal.visitRecoverySlice(level,journal,manifest,page,offset,count,change->{
                validateBounds(level,area,change);
                if(data.protectedBlock(area.dimension(),change.x(),change.z()))throw new IllegalStateException("Rollback location is protected");
                var pos=new BlockPos(change.x(),change.y(),change.z());
                var loaded=ServerExistingChunkAccess.requireBlock(level,area,change.x(),change.y(),change.z());
                BlockState current=loaded.getBlockState(pos),before=decode(change.before()),applied=decode(change.applied());
                if(!current.equals(applied)&&!current.equals(before))throw ownership(pos);
                if(phase==BiomeApplyRecoveryCursor.Phase.REPLAY&&!current.equals(before)){
                    writeVerified(level,pos,before,mutationFlags(pass));loaded.setUnsaved(true);
                }
            }));
        var progress=r.cursor.progress();
        if(progress.phase()==BiomeApplyRecoveryCursor.Phase.VALIDATE)return;
        var advanced=job.withCursor(Math.max(0,progress.visitedCells()-manifest.entryCount()));
        if(progress.phase()==BiomeApplyRecoveryCursor.Phase.READY)advanced=advanced.withState(WorldPrepSavedData.JobState.COMPLETED);
        data.jobs().put(id,advanced);data.changed();blocks.changed();level.getDataStorage().save();
    }

    /** All recovered cells pass ownership before the first replay, with the same cell budget. */
    private static int advanceApplyRecovery(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,
            BlockWorldPrepData.Plan plan,BlockWorldPrepData.Journal journal,BlockJournalRecovery r,int budget,boolean[] replayed){
        return r.cursor.advance(budget,page->r.manifest.pages().get(Math.toIntExact(r.firstPage+page)).entryCount(),
            (page,offset,count,phase)->RuntimePageJournal.visitRecoverySlice(level,journal,r.manifest,r.firstPage+page,offset,count,change->{
                validateBounds(level,area,change);var pos=new BlockPos(change.x(),change.y(),change.z());
                var loaded=ServerExistingChunkAccess.requireBlock(level,area,change.x(),change.y(),change.z());
                BlockState current=loaded.getBlockState(pos),before=decode(change.before()),applied=decode(change.applied());
                if(current.equals(before)){
                    if(!safeAtBoundary(level,data,area,plan,change,pos))throw ownership(pos);
                }else if(!current.equals(applied))throw ownership(pos);
                if(phase==BiomeApplyRecoveryCursor.Phase.REPLAY&&current.equals(before)){
                    writeVerified(level,pos,applied,mutationFlags(plan.pass));loaded.setUnsaved(true);replayed[0]=true;
                }
            }));
    }

    private static boolean safeAtBoundary(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,BlockWorldPrepData.Plan plan,BlockWorldPrepData.Change change,BlockPos pos){
        if(!MinecraftTerrainSampler.matchesMask(level,area.mask(),change.x(),change.z(),1,1))return false;if(data.protectedBlock(area.dimension(),change.x(),change.z())||level.getBlockEntity(pos)!=null)return false;
        var current=level.getBlockState(pos);if(plan.pass.equals("TREES"))return TreeMutationPlanner.safe(level,data,area,change);if(EcologyMutationPlanner.supports(plan.pass))return EcologyMutationPlanner.safe(level,area,plan.pass,change,decode(change.applied()));return plan.pass.equals("GEOLOGY")?current.is(GEOLOGY):(current.is(STONE)||current.is(DEEPSLATE))&&validOreTarget(change.applied(),change.y());
    }
    private static void writeVerified(ServerLevel level,BlockPos pos,BlockState target,int flags){
        level.setBlock(pos,target,flags);
        if(!level.getBlockState(pos).equals(target))throw new IllegalStateException("Block write did not reach journaled target at "+pos.toShortString());
    }
    private static int mutationFlags(String pass){return Block.UPDATE_CLIENTS | (EcologyMutationPlanner.supports(pass)?Block.UPDATE_KNOWN_SHAPE:0);}
    private static void validateBounds(ServerLevel level,WorldPrepSavedData.Area area,BlockWorldPrepData.Change change){if(change.x()<area.minX()||change.x()>area.maxX()||change.z()<area.minZ()||change.z()>area.maxZ()||change.y()<level.getMinBuildHeight()||change.y()>=level.getMaxBuildHeight())throw new IllegalStateException("Persisted change outside area/build bounds");}
    private static IllegalStateException ownership(BlockPos pos){return new IllegalStateException("Block ownership ambiguous at "+pos.toShortString());}
    private static BlockWorldPrepData.Journal requireJournal(BlockWorldPrepData blocks,WorldPrepSavedData.Job job,WorldPrepSavedData.Area area,BlockWorldPrepData.Plan plan){var journal=blocks.journals().get(job.snapshotId());if(journal==null||!journal.job.equals(job.id())||!journal.area.equals(area.id())||!journal.pass.equals(plan.pass)||!journal.fingerprint().equals(plan.fingerprint())||!journal.planId().equals(plan.planId()))throw new IllegalStateException("Snapshot ownership mismatch");return journal;}
    private static List<BlockWorldPrepData.Change> ordered(java.util.Collection<BlockWorldPrepData.Change> values){var result=new ArrayList<>(values);result.sort(Comparator.comparingInt(BlockWorldPrepData.Change::z).thenComparingInt(BlockWorldPrepData.Change::x).thenComparingInt(BlockWorldPrepData.Change::y));return result;}
    private static void requireCompatible(BlockWorldPrepData data){if(!data.compatible())throw new IllegalStateException("Legacy block persistence format is incompatible; block job refused");}
    private static boolean validOreTarget(String target,int y){for(var ore:ORES)if((target.equals("minecraft:"+ore.stone)||target.equals("minecraft:"+ore.deep))&&y>=ore.min&&y<=ore.max)return true;return false;}
    private static BlockWorldPrepData.Plan requirePlan(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,String pass){
        return requirePlan(level,data,area,pass,EcologyRecoveryHistory.EMPTY);
    }
    private static BlockWorldPrepData.Plan requirePlan(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,String pass,EcologyRecoveryHistory history){var blocks=data(level);requireCompatible(blocks);var plan=blocks.plans().get(BlockWorldPrepData.planKey(area.id(),pass));if(plan==null||plan.fingerprint().isEmpty())throw new IllegalStateException("No persisted "+pass+" preview");if(plan.planId()==null)publish(level,data,area,plan);if(!plan.input.equals(EcologyMutationPlanner.supports(pass)?ecologyInput(level,data,area,pass,history):input(level,data,area,pass)))throw new IllegalStateException("Stale "+pass+" preview");if(pass.equals("ORES")){var geology=blocks.plans().get(BlockWorldPrepData.planKey(area.id(),"GEOLOGY"));if(geology==null||plan.upstreamPlanId()==null||!plan.upstreamPlanId().equals(geology.planId())||!plan.upstreamRoot().equals(geology.fingerprint()))throw new IllegalStateException("ORES exact GEOLOGY plan identity is stale");if(!hasAppliedGeology(blocks,area.id()))throw new IllegalStateException("ORES requires applied GEOLOGY");}manifest(level,plan);return plan;}
    private static boolean hasAppliedGeology(BlockWorldPrepData data,String area){return data.journals().values().stream().anyMatch(j->j.area.equals(area)&&j.pass.equals("GEOLOGY"));}
    /** Read-only identity check for exports; unlike legacy authoring it never publishes a plan. */
    public static String readOnlyInputIdentity(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,String pass){return input(level,data,area,pass);}
    public static String inputIdentity(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,String pass){if(pass.equals("ORES")){var geology=BlockMutationRuntime.data(level).plans().get(BlockWorldPrepData.planKey(area.id(),"GEOLOGY"));if(geology!=null&&geology.planId()==null)publish(level,data,area,geology);}return input(level,data,area,pass);}
    public static void seal(BlockWorldPrepData.Plan plan){plan.fingerprint(fingerprint(plan));}
    /** Publishes hand-authored acceptance-test plans through the same production sealing path. */
    public static void publishSealed(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,BlockWorldPrepData.Plan plan){seal(plan);publish(level,data,area,plan);}
    public static String stateString(BlockState state){return encode(state);}
    private static String input(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,String pass){if(EcologyMutationPlanner.supports(pass))return ecologyInput(level,data,area,pass);String protections=data.locations().isEmpty()?Long.toString(data.protections().values().stream().filter(p->p.dimension().equals(area.dimension())).mapToLong(Object::hashCode).sum()):data.protectionFingerprint();String geology=pass.equals("ORES")?java.util.Optional.ofNullable(data(level).plans().get(BlockWorldPrepData.planKey(area.id(),"GEOLOGY"))).map(p->p.fingerprint()).orElse(""):"";return area.dimension()+":"+area.minX()+":"+area.minZ()+":"+area.maxX()+":"+area.maxZ()+":"+level.getSeed()+":"+protections+":"+geology+(area.mask()==WorldPrepSavedData.AreaMask.FULL_AREA?"":":area-mask-v1:"+area.mask().name());}

    private static String ecologyInput(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,String pass){
        return ecologyInput(level,data,area,pass,EcologyRecoveryHistory.EMPTY);
    }
    private static String ecologyInput(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,String pass,EcologyRecoveryHistory history){
        var blocks=data(level);String biome=java.util.Optional.ofNullable(data.plans().get(area.id())).map(p->p.planId+":"+p.fingerprint).orElse("");
        String[] journals=blocks.journals().values().stream().filter(j->!history.journals().contains(j.id)&&!j.pass.equals(pass)&&data.areas().containsKey(j.area)&&data.areas().get(j.area).overlaps(area)).map(j->j.id+":"+j.pass+":"+j.pageCount()+":"+j.entryCount()+":"+j.journalRoot()).sorted().toArray(String[]::new);
        // A rollback changes real upstream terrain without changing the immutable journal root.
        String[] jobs=data.jobs().values().stream().filter(j->!history.jobs().contains(j.id())).filter(j->(j.operation().name().startsWith("APPLY_")||j.operation().name().startsWith("ROLLBACK_"))&&!j.operation().name().equals("APPLY_"+pass)&&data.areas().containsKey(j.area())&&data.areas().get(j.area()).overlaps(area)).map(j->j.id()+":"+j.operation()+":"+j.cursor()+":"+j.snapshotId()).sorted().toArray(String[]::new);
        return PlanFingerprint.of(EcologyMutationPlanner.VERSION,EcologyMutationPlanner.validateProfile().toString(),EcologyMutationPlanner.tagFingerprint(level),Long.toString(level.getSeed()),area.treeMode().name(),fr.proutlost.worldprep.plan.PlanIdentity.fingerprint(fr.proutlost.worldprep.plan.PlanIdentity.capture(level,data,area)),biome,PlanFingerprint.of(journals).value(),PlanFingerprint.of(jobs).value()).value();
    }
    private static String fingerprint(BlockWorldPrepData.Plan plan){String changes=PlanFingerprint.of(plan.changes.values().stream().map(c->c.x()+","+c.y()+","+c.z()+","+c.before()+","+c.applied()).sorted().toArray(String[]::new)).value();return PlanFingerprint.of(plan.input,changes).value();}

    private static void publish(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,BlockWorldPrepData.Plan plan){
        publish(level,data,area,plan,false);
    }
    private static void publish(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,BlockWorldPrepData.Plan plan,boolean parallelComplete){
        try{
            if(plan.planId()!=null){manifest(level,plan);return;}
            Iterable<BlockWorldPrepData.Change> entries=parallelComplete?ordered(plan.changes.values().stream().filter(change->MinecraftTerrainSampler.matchesMask(level,area.mask(),change.x(),change.z(),1,1)).toList()):plan.changes.isEmpty()?plannedEntries(level,data,area,plan):ordered(plan.changes.values().stream().filter(change->MinecraftTerrainSampler.matchesMask(level,area.mask(),change.x(),change.z(),1,1)).toList());
            plan.planId(UUID.randomUUID());
            var publication=PagedPlanStore.publish(RuntimePlanStore.root(level),plan.planId(),RuntimePlanStore.pass(plan.pass),plan.area,area.dimension(),plan.profile,PlanFingerprint.of(plan.input).value(),RuntimePlanStore.ENGINE_VERSION,RuntimePlanStore.PLANNER_VERSION,RuntimePlanStore.ENTRIES_PER_PAGE,entries,RuntimePlanStore.BLOCK_CODEC);
            plan.entryCount(publication.manifest().pages().stream().mapToLong(PlanManifest.PageReference::entryCount).sum());plan.fingerprint(publication.manifest().rootFingerprint());
            if(plan.pass.equals("ORES")){var geology=BlockMutationRuntime.data(level).plans().get(BlockWorldPrepData.planKey(area.id(),"GEOLOGY"));if(geology==null)throw new IllegalStateException("ORES requires exact GEOLOGY plan");if(geology.planId()==null)publish(level,data,area,geology);plan.upstreamPlanId(geology.planId());plan.upstreamRoot(geology.fingerprint());}
            plan.changes.clear();
        }catch(IOException e){throw new IllegalStateException("Plan publication failed",e);}
    }
    private static Iterable<BlockWorldPrepData.Change> plannedEntries(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,BlockWorldPrepData.Plan plan){return()->new Iterator<>(){
        final long height=level.getMaxBuildHeight()-level.getMinBuildHeight(),width=(long)area.maxX()-area.minX()+1,total=width*((long)area.maxZ()-area.minZ()+1)*height;long cursor;BlockWorldPrepData.Change next;boolean ready;int maskX=Integer.MIN_VALUE,maskZ;boolean maskAccepted;
        private void advance(){while(!ready&&cursor<total){int y=level.getMinBuildHeight()+(int)(cursor%height);long column=cursor++/height;int x=area.minX()+(int)(column%width),z=area.minZ()+(int)(column/width);if(maskX!=x||maskZ!=z){maskX=x;maskZ=z;maskAccepted=MinecraftTerrainSampler.matchesMask(level,area.mask(),x,z,1,1);}next=maskAccepted?plannedChange(level,data,plan,area,x,y,z):null;ready=next!=null;}}
        public boolean hasNext(){advance();return ready;}public BlockWorldPrepData.Change next(){advance();if(!ready)throw new NoSuchElementException();ready=false;return next;}
    };}
    private static BlockWorldPrepData.Change plannedChange(ServerLevel level,WorldPrepSavedData data,BlockWorldPrepData.Plan plan,WorldPrepSavedData.Area area,int x,int y,int z){
        return plannedChange(level,data,plan,area,x,y,z,null);
    }
    private static BlockWorldPrepData.Change plannedChange(ServerLevel level,WorldPrepSavedData data,BlockWorldPrepData.Plan plan,WorldPrepSavedData.Area area,int x,int y,int z,BlockPreview preview){
        if(plan.pass.equals("TREES")){
            var candidate=preview==null?null:preview.tree;
            if(candidate==null||candidate.chunkX()!=(x>>4)||candidate.chunkZ()!=(z>>4)){candidate=TreeMutationPlanner.planChunk(level,data,area,x>>4,z>>4);if(preview!=null)preview.tree=candidate;}
            return candidate.changes().get(BlockWorldPrepData.posKey(x,y,z));
        }
        if(EcologyMutationPlanner.supports(plan.pass)){
            var column=preview==null?null:preview.column;
            if(column==null||preview.columnX!=x||preview.columnZ!=z){column=EcologyMutationPlanner.observe(level,area,x,z);if(preview!=null){preview.column=column;preview.columnX=x;preview.columnZ=z;}}
            return EcologyMutationPlanner.change(level,data,area,plan.pass,x,y,z,column);
        }
        ServerExistingChunkAccess.requireBlock(level,area,x,y,z);var pos=new BlockPos(x,y,z);var state=level.getBlockState(pos);boolean protectedAt=data.protectedBlock(area.dimension(),x,z)||level.getBlockEntity(pos)!=null;
        if(plan.pass.equals("GEOLOGY")){if(!state.is(GEOLOGY)){if(protectedAt)plan.skipped(plan.skipped()+1);return null;}plan.eligible(plan.eligible()+1);if(protectedAt){plan.skipped(plan.skipped()+1);return null;}var province=GeologyPlanner.province(level.getSeed(),x,y,z);String target=GeologyPlanner.replacement(level.getSeed(),x,y,z,province);return id(state).equals(target)?null:new BlockWorldPrepData.Change(x,y,z,encode(state),target,province.name());}
        boolean stone=state.is(STONE),deep=state.is(DEEPSLATE);if(!stone&&!deep){if(protectedAt)plan.skipped(plan.skipped()+1);return null;}plan.eligible(plan.eligible()+1);if(protectedAt){plan.skipped(plan.skipped()+1);return null;}for(var ore:ORES)if(y>=ore.min&&y<=ore.max&&Math.floorMod(GeologyPlanner.mix(level.getSeed()^ore.salt^x*31L^y*131L^z*8191L),ore.rarity)==0)return new BlockWorldPrepData.Change(x,y,z,encode(state),"minecraft:"+(deep?ore.deep:ore.stone),ore.name);return null;
    }
    private static PlanManifest manifest(ServerLevel level,BlockWorldPrepData.Plan plan){try{var m=PagedPlanStore.readManifest(RuntimePlanStore.directory(level,plan.planId()));if(!m.planId().equals(plan.planId())||m.passId()!=RuntimePlanStore.pass(plan.pass)||!m.rootFingerprint().equals(plan.fingerprint()))throw new IOException("Exact plan metadata mismatch");m.requireApplicable();fr.proutlost.worldprep.storage.DurablePageStore.forceDirectory(RuntimePlanStore.directory(level,plan.planId()));return m;}catch(IOException e){throw new IllegalStateException("Sealed plan manifest unavailable",e);}}
    private static String pass(WorldPrepSavedData.Operation operation){return operation.name().substring(operation.name().indexOf('_')+1);}private static boolean isApply(WorldPrepSavedData.Operation operation){return operation.name().startsWith("APPLY");}private static boolean isRollback(WorldPrepSavedData.Operation operation){return operation.name().startsWith("ROLLBACK");}
    private static TagKey<Block> tag(String path){return BlockTags.create(ResourceLocation.fromNamespaceAndPath("proutlost",path));}private static String id(BlockState state){return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();}
    private static String encode(BlockState state){var result=new StringBuilder(id(state));if(!state.getValues().isEmpty()){result.append('[');state.getValues().entrySet().stream().sorted(Comparator.comparing(e->e.getKey().getName())).forEach(e->result.append(e.getKey().getName()).append('=').append(valueName(e.getKey(),e.getValue())).append(','));result.setCharAt(result.length()-1,']');}return result.toString();}
    @SuppressWarnings({"rawtypes","unchecked"}) private static String valueName(Property property,Comparable value){return property.getName(value);}
    @SuppressWarnings({"rawtypes","unchecked"}) public static BlockState decode(String text){int bracket=text.indexOf('[');String name=bracket<0?text:text.substring(0,bracket);var key=ResourceLocation.parse(name);if(!BuiltInRegistries.BLOCK.containsKey(key))throw new IllegalStateException("Missing persisted block registry ID: "+name);BlockState state=BuiltInRegistries.BLOCK.get(key).defaultBlockState();if(bracket>=0){for(String pair:text.substring(bracket+1,text.length()-1).split(",")){String[] parts=pair.split("=",2);Property property=state.getBlock().getStateDefinition().getProperty(parts[0]);if(property==null||property.getValue(parts[1]).isEmpty())throw new IllegalStateException("Invalid persisted BlockState "+text);state=state.setValue(property,(Comparable)property.getValue(parts[1]).get());}}return state;}
    private BlockMutationRuntime(){}
}
