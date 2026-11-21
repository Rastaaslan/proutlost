package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.plan.BiomeBounds;
import fr.proutlost.worldprep.plan.BiomePlanGuard;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import fr.proutlost.worldprep.plan.PlanIdentity;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.io.IOException;
import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.storage.PagedPlanStore;
import fr.proutlost.worldprep.storage.PlanSelectionTrace;
import fr.proutlost.worldprep.storage.PlanManifest;

/** Server-thread, cell-budgeted and restart-safe job executor. It creates no force-load tickets. */
public final class WorldPrepRuntime {
    static final class PreviewResumeRequired extends IllegalStateException{PreviewResumeRequired(){super("Unsealed preview scan lost after restart; cancel and create a fresh preview. Existing sealed plans remain unchanged.");}}
    private static final java.util.Map<ServerLevel,java.util.Map<UUID,BiomePreview>> PREVIEWS=new java.util.WeakHashMap<>();
    private static final class BiomePreview {
        final WorldPrepSavedData.Plan plan;final PagedPlanStore.Writer<WorldPrepSavedData.BiomeCell> writer;long scanned;
        BiomePreview(WorldPrepSavedData.Plan plan,PagedPlanStore.Writer<WorldPrepSavedData.BiomeCell> writer){this.plan=plan;this.writer=writer;}
    }
    private static void prunePreviews(ServerLevel level,WorldPrepSavedData data){
        var previews=PREVIEWS.get(level);if(previews!=null){previews.keySet().removeIf(id->{var job=data.jobs().get(id);return job==null||!(job.state()==WorldPrepSavedData.JobState.RUNNING||job.state()==WorldPrepSavedData.JobState.QUEUED||job.state()==WorldPrepSavedData.JobState.PAUSED);});if(previews.isEmpty())PREVIEWS.remove(level);}
        BlockMutationRuntime.prunePreviews(level,data);
    }
    private static void forgetPreview(ServerLevel level,UUID id){var previews=PREVIEWS.get(level);if(previews!=null)previews.remove(id);BlockMutationRuntime.forgetPreview(level,id);}
    public static WorldPrepSavedData data(ServerLevel level){return fr.proutlost.worldprep.persistence.PagedSavedDataRuntime.withServerLevel(level,WorldPrepSavedData.FILE_ID,()->level.getDataStorage().computeIfAbsent(WorldPrepSavedData.FACTORY,WorldPrepSavedData.FILE_ID));}
    public static UUID enqueue(ServerLevel level,WorldPrepSavedData.Operation op,String areaId){
        if(op.name().startsWith("ROLLBACK"))throw new IllegalArgumentException("Rollback requires an exact snapshot id");
        return enqueue(level,op,areaId,null);
    }
    public static UUID enqueueRollback(ServerLevel level,WorldPrepSavedData.Operation op,String areaId,UUID snapshotId){
        if(!op.name().startsWith("ROLLBACK"))throw new IllegalArgumentException("Operation is not rollback");
        return enqueue(level,op,areaId,java.util.Objects.requireNonNull(snapshotId,"snapshotId"));
    }
    private static UUID enqueue(ServerLevel level,WorldPrepSavedData.Operation op,String areaId,UUID snapshot){requireEnabled();var d=data(level);var area=requireArea(d,areaId);if(!area.dimension().equals(level.dimension().location().toString()))throw new IllegalStateException("Area belongs to another dimension");if(op.name().startsWith("APPLY")||op.name().startsWith("ROLLBACK")||op==WorldPrepSavedData.Operation.BATCH_ECOLOGY){fr.proutlost.worldprep.physical.NativePhysicalRuntime.requireNoPhysicalHistory(level);assertUnlocked(d,area);}UUID id=UUID.randomUUID();d.jobs().put(id,new WorldPrepSavedData.Job(id,op,areaId,WorldPrepSavedData.JobState.QUEUED,0,"",snapshot));d.changed();if(BlockMutationRuntime.isBlockOperation(op)){try{BlockMutationRuntime.prepare(level,d,id,op,area);}catch(RuntimeException failure){d.jobs().put(id,d.jobs().get(id).failed(failure.getMessage()));d.changed();throw failure;}}return id;}
    public static void tick(ServerTickEvent.Post event){
        if(!WorldPrepConfig.ENABLED.get()||WorldPrepConfig.STRUCTURES_ONLY.get()){for(ServerLevel level:event.getServer().getAllLevels()){MultiTileEcologyPrefetch.cancelLevel(level);ExistingChunkLoader.release(level);}return;}
        var stop=fr.proutlost.worldprep.ecology.ExecutionSafety.check(event.getServer().getPlayerCount(),event.getServer().getAverageTickTimeNanos(),WorldPrepConfig.MAX_TICK_MILLIS.get());
        for(ServerLevel level:event.getServer().getAllLevels()){
            if(stop!=fr.proutlost.worldprep.ecology.ExecutionSafety.Stop.NONE){ExistingChunkLoader.release(level);var d=data(level);EcologyParallelPreviewRuntime.cancel(d);MultiTileEcologyPrefetch.cancel(d);d.jobs().replaceAll((id,j)->j.state()==WorldPrepSavedData.JobState.RUNNING||j.state()==WorldPrepSavedData.JobState.QUEUED?new WorldPrepSavedData.Job(j.id(),j.operation(),j.area(),WorldPrepSavedData.JobState.PAUSED,j.cursor(),"SAFETY_STOP: "+stop,j.snapshotId()):j);d.changed();}
            else tickLevel(level,WorldPrepConfig.CELLS_PER_TICK.get());
        }
    }
    /** Runs one persisted job slice; exposed so runtime acceptance tests use the real executor. */
    public static void tickLevel(ServerLevel level,int budget){
        requireEnabled();
        var d=data(level);
        prunePreviews(level,d);
        ExistingChunkLoader.cleanup(level,d);
        var entry=d.jobs().entrySet().stream().filter(e->e.getValue().state()==WorldPrepSavedData.JobState.RUNNING||e.getValue().state()==WorldPrepSavedData.JobState.QUEUED).findFirst().orElse(null);
        if(entry==null)return;
        var j=entry.getValue();
        if(j.state()==WorldPrepSavedData.JobState.QUEUED){j=j.withState(WorldPrepSavedData.JobState.RUNNING);d.jobs().put(entry.getKey(),j);d.changed();}
        try{process(level,d,entry.getKey(),j,budget);
        }catch(PreviewResumeRequired ex){
            forgetPreview(level,entry.getKey());
            var current=d.jobs().get(entry.getKey());
            d.jobs().put(entry.getKey(),current.failed(ex.getMessage()).withState(WorldPrepSavedData.JobState.PAUSED));d.changed();
        }catch(ServerExistingChunkAccess.Refused ex){
            var current=d.jobs().get(entry.getKey());
            if(ex.refusal()==ServerExistingChunkAccess.Refusal.MISSING_OR_INCOMPLETE_CHUNK)
                d.jobs().put(entry.getKey(),current.failed(ex.getMessage()).withState(WorldPrepSavedData.JobState.PAUSED));
            else{forgetPreview(level,entry.getKey());d.jobs().put(entry.getKey(),current.failed(ex.getMessage()));}
            d.changed();
        }catch(Exception ex){
            forgetPreview(level,entry.getKey());
            var current=d.jobs().get(entry.getKey());
            d.jobs().put(entry.getKey(),current.failed(ex.getClass().getSimpleName()+": "+ex.getMessage()));d.changed();
        }finally{ExistingChunkLoader.cleanup(level,d);}
    }
    private static void process(ServerLevel level,WorldPrepSavedData d,UUID id,WorldPrepSavedData.Job job,int budget){if(!d.checkpointReady()){level.getDataStorage().save();return;}if(job.operation()==WorldPrepSavedData.Operation.BATCH_ECOLOGY){EcologyBatchRuntime.process(level,d,job,budget);return;}if(job.operation()==WorldPrepSavedData.Operation.ANALYZE){analyzeSlice(level,d,id,job,budget);return;}if(job.operation()==WorldPrepSavedData.Operation.PREVIEW_HABITATS){try{WorldPrepHabitatRuntime.process(level,d,id,job,budget);}catch(IOException ex){throw new IllegalStateException("Habitat publication failed: "+ex.getMessage(),ex);}return;}if(BlockMutationRuntime.isBlockOperation(job.operation())){var target=rollbackLoadingTarget(job.operation(),requireArea(d,job.area()),WorldPrepConfig.LOAD_EXISTING_CHUNKS.get());if(target!=null&&!ExistingChunkLoader.ensure(level,id,target.x,target.z))return;BlockMutationRuntime.process(level,d,id,job,budget);return;}var area=requireArea(d,job.area());var bounds=BiomeBounds.inside(area);if(job.operation()==WorldPrepSavedData.Operation.ROLLBACK_BIOMES){rollback(level,d,id,job,budget,area,bounds);return;}var plan=d.plans().get(area.id());if(job.operation()==WorldPrepSavedData.Operation.PREVIEW_BIOMES){previewBiomeSlice(level,d,id,job,area,bounds,budget);return;}if(job.operation()==WorldPrepSavedData.Operation.APPLY_BIOMES){if(plan==null||plan.fingerprint.isEmpty())throw new IllegalStateException("No persisted preview");if(plan.planId==null)publishBiome(level,d,area,bounds,plan);PlanIdentity.requireCurrent(plan.input,PlanIdentity.capture(level,d,area));var manifest=biomeManifest(level,plan);var activePlan=plan;if(job.snapshotId()==null){UUID snapshotId=UUID.randomUUID();var snapshot=new WorldPrepSavedData.Snapshot(snapshotId,id,plan.planId,area.dimension(),area.id(),"BIOMES",plan.fingerprint);RuntimeBiomeJournal.initialize(level,snapshot);d.snapshots().put(snapshotId,snapshot);job=job.withSnapshot(snapshotId);d.jobs().put(id,job);d.changed();level.getDataStorage().save();return;}var activeSnapshot=validateSnapshot(d,job,area,plan);reconcileBiomeApply(level,d,area,activeSnapshot);long durable=activeSnapshot.pageCount;if(durable>plan.entryCount)throw new IllegalStateException("Biome journal exceeds exact plan");job=job.withCursor(durable);d.jobs().put(id,job);var touched=new LinkedHashSet<LevelChunk>();long start=durable;long[] sequence={start};try{var active=job;PagedPlanStore.readRange(RuntimePlanStore.directory(level,plan.planId),manifest,RuntimePlanStore.BIOME_CODEC,start,budget,cell->{if(applyPlannedCell(level,d,area,activePlan,d.snapshots().get(active.snapshotId()),cell,sequence[0]++))touched.add(ServerExistingChunkAccess.requireChunk(level,QuartPos.toBlock(cell.quartX())>>4,QuartPos.toBlock(cell.quartZ())>>4));var current=d.jobs().get(id);d.jobs().put(id,current.withCursor(current.cursor()+1));d.changed();});}catch(IOException e){throw new IllegalStateException("Paged biome plan validation failed",e);}BiomeRuntime.refresh(level,touched);job=d.jobs().get(id);if(job.cursor()>=plan.entryCount)d.jobs().put(id,job.withState(WorldPrepSavedData.JobState.COMPLETED));d.changed();}}
    /** An unsealed preview has no world authority. After process restart its scan safely starts from zero. */
    private static void previewBiomeSlice(ServerLevel level,WorldPrepSavedData data,UUID id,WorldPrepSavedData.Job job,
            WorldPrepSavedData.Area area,BiomeBounds bounds,int budget){
        try{
            var previews=PREVIEWS.computeIfAbsent(level,k->new java.util.HashMap<>());var preview=previews.get(id);
            if(preview==null||preview.scanned!=job.cursor()){
                if(job.cursor()!=0)throw new PreviewResumeRequired();
                // Never adopt an incomplete prefix from SavedData as a sealed plan.
                var plan=new WorldPrepSavedData.Plan(area.id(),PlanIdentity.capture(level,data,area),"");plan.planId=UUID.randomUUID();
                var writer=PagedPlanStore.begin(RuntimePlanStore.root(level),plan.planId,PassId.BIOMES,area.id(),area.dimension(),
                    plan.input.profile(),PlanIdentity.fingerprint(plan.input),RuntimePlanStore.ENGINE_VERSION,PlanSelectionTrace.PLANNER_VERSION,
                    RuntimePlanStore.ENTRIES_PER_PAGE,RuntimePlanStore.BIOME_CODEC);
                preview=new BiomePreview(plan,writer);previews.put(id,preview);job=job.withCursor(0);data.jobs().put(id,job);data.changed();
            }
            PlanIdentity.requireCurrent(preview.plan.input,PlanIdentity.capture(level,data,area));
            int remaining=area.mask()==WorldPrepSavedData.AreaMask.FULL_AREA?Math.max(1,budget):Math.max(1,budget/16);
            while(remaining-->0&&preview.scanned<bounds.cells()){
                long cursor=preview.scanned;
                int qx=bounds.minQuartX()+(int)(cursor%bounds.width()),qz=bounds.minQuartZ()+(int)(cursor/bounds.width());
                if(!bounds.footprintInside(area,qx,qz))throw new IllegalStateException("Bounds invariant failed");
                var sample=MinecraftTerrainSampler.sample(level,QuartPos.toBlock(qx)+2,QuartPos.toBlock(qz)+2);
                if(!data.protectedAt(area.dimension(),qx,qz)&&MinecraftTerrainSampler.matchesMask(level,area.mask(),QuartPos.toBlock(qx),QuartPos.toBlock(qz),4,4))preview.writer.appendSelected(new WorldPrepSavedData.BiomeCell(qx,qz,chooseBiome(data,sample,preview.plan.input.seed(),qx,qz),sample.signature()),PlanSelectionTrace.Selection.DIRECT);
                preview.scanned++;
            }
            data.jobs().put(id,job.withCursor(preview.scanned));data.changed();
            if(preview.scanned<bounds.cells())return;
            var completed=preview.writer.finish(Math.max(1,budget/RuntimePlanStore.ENTRIES_PER_PAGE));if(completed.isEmpty())return;
            var manifest=completed.orElseThrow().manifest();preview.plan.entryCount=manifest.pages().stream().mapToLong(PlanManifest.PageReference::entryCount).sum();
            preview.plan.fingerprint=manifest.rootFingerprint();data.plans().put(area.id(),preview.plan);
            data.jobs().put(id,data.jobs().get(id).withCursor(preview.plan.entryCount).withState(WorldPrepSavedData.JobState.COMPLETED));data.changed();previews.remove(id);
        }catch(IOException e){throw new IllegalStateException("Incremental biome preview failed",e);}
    }
    /** Read-only incremental observations, independent of the persisted preview/apply plans. */
    private static void analyzeSlice(ServerLevel level,WorldPrepSavedData data,UUID id,WorldPrepSavedData.Job job,int budget){
        var area=requireArea(data,job.area());if(!area.dimension().equals(level.dimension().location().toString()))throw new IllegalStateException("Analysis dimension changed");var input=analysisInput(level,data,area);
        var bounds=new BiomeBounds(Math.floorDiv(area.minX(),4),Math.floorDiv(area.minZ(),4),Math.floorDiv(area.maxX(),4),Math.floorDiv(area.maxZ(),4));
        var analysis=data.analyses().get(id);
        if(analysis==null){
            if(job.cursor()!=0)throw new IllegalStateException("Analysis cursor has no persisted aggregates");
            int zones=(int)data.protections().values().stream().filter(p->p.dimension().equals(area.dimension())&&p.minX()<=area.maxX()&&p.maxX()>=area.minX()&&p.minZ()<=area.maxZ()&&p.maxZ()>=area.minZ()).count();
            analysis=new WorldPrepSavedData.Analysis(input,bounds.cells(),level.getMinBuildHeight(),level.getMaxBuildHeight(),level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME).keySet().size(),zones,PlanFingerprint.of("terrain-analysis-v2-inland4-noise256",PlanIdentity.fingerprint(input)).value());
            data.analyses().put(id,analysis);data.changed();
        }
        PlanIdentity.requireCurrent(analysis.input,input);
        if(analysis.samples!=job.cursor()||analysis.totalCells!=bounds.cells())throw new IllegalStateException("Analysis prefix/cursor mismatch");
        if(analysis.terrainSamples!=analysis.samples)throw new IllegalStateException("Legacy analysis lacks local terrain measures; create a new analysis");
        var noise=new net.minecraft.world.level.levelgen.synth.SimplexNoise(net.minecraft.util.RandomSource.create(input.seed()));
        // At most 335 existing terrain probes + 81 inland probes per atomic observation.
        // Retain a one-cell minimum for positive budgets; cap larger slices by actual worst-case read work.
        int remaining=budget>0?Math.max(1,budget/416):0;
        while(remaining-->0&&analysis.samples<analysis.totalCells){
            long cursor=analysis.samples;int qx=bounds.minQuartX()+(int)(cursor%bounds.width()),qz=bounds.minQuartZ()+(int)(cursor/bounds.width());
            int x=Math.max(area.minX(),Math.min(area.maxX(),QuartPos.toBlock(qx)+2)),z=Math.max(area.minZ(),Math.min(area.maxZ(),QuartPos.toBlock(qz)+2));
            var sample=MinecraftTerrainSampler.sample(level,x,z);var biome=chooseBiome(data,sample,input.seed(),qx,qz);
            var inland=MinecraftTerrainSampler.inlandWater(level,x,z);double noiseValue=noise.getValue(x/256.0,z/256.0);
            boolean present=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME).getHolder(net.minecraft.resources.ResourceLocation.parse(biome)).isPresent();
            String altitudeBand=Integer.toString(Math.floorDiv(sample.surfaceY(),16)*16),slopeBand=Integer.toString((int)sample.slope());
            analysisCountAllowed(analysis.altitudeBands,altitudeBand);analysisCountAllowed(analysis.slopeBands,slopeBand);analysisCountAllowed(analysis.biomes,biome);if(!present)analysisCountAllowed(analysis.missingBiomes,biome);
            analysis.altitudeBands.merge(altitudeBand,1L,Math::addExact);analysis.slopeBands.merge(slopeBand,1L,Math::addExact);analysis.biomes.merge(biome,1L,Math::addExact);if(!present)analysis.missingBiomes.merge(biome,1L,Math::addExact);
            analysis.minSurface=Math.min(analysis.minSurface,sample.surfaceY());analysis.maxSurface=Math.max(analysis.maxSurface,sample.surfaceY());analysis.maxWaterDepth=Math.max(analysis.maxWaterDepth,sample.waterDepth());
            analysis.surfaceSum+=sample.surfaceY();analysis.slopeSum+=sample.slope();analysis.roughnessSum+=sample.roughness();analysis.waterDepthSum+=sample.waterDepth();analysis.coastSum+=sample.coastDistance();
            if(sample.water())analysis.waterSamples++;if(data.protectedAt(area.dimension(),qx,qz))analysis.protectedSamples++;
            if(inland.observedDistance()>=0){analysis.inlandDistanceSamples++;analysis.inlandDistanceSum+=inland.observedDistance();}if(inland.unresolvedBoundary())analysis.inlandUnresolvedSamples++;if(inland.noWater())analysis.inlandNoWaterSamples++;
            analysis.noiseSum+=noiseValue;analysis.noiseMin=analysis.terrainSamples==0?noiseValue:Math.min(analysis.noiseMin,noiseValue);analysis.noiseMax=analysis.terrainSamples==0?noiseValue:Math.max(analysis.noiseMax,noiseValue);analysis.terrainSamples++;
            analysis.terrainDigest=PlanFingerprint.of(analysis.terrainDigest,Integer.toString(x),Integer.toString(z),Long.toString(sample.signature()),biome,Boolean.toString(present),inland.toString(),Double.toString(noiseValue)).value();analysis.samples++;
            data.jobs().put(id,job.withCursor(analysis.samples));data.changed();
        }
        if(analysis.samples==analysis.totalCells){analysis.complete=true;data.jobs().put(id,data.jobs().get(id).withState(WorldPrepSavedData.JobState.COMPLETED));data.changed();}
    }
    private static WorldPrepSavedData.PlanInput analysisInput(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area){
        var i=PlanIdentity.capture(level,data,area);String keys=PlanFingerprint.of(level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME).keySet().stream().map(Object::toString).sorted().toArray(String[]::new)).value();
        return new WorldPrepSavedData.PlanInput(i.dimension(),i.minX(),i.minZ(),i.maxX(),i.maxZ(),i.seed(),i.profile(),i.profileDigest(),i.configDigest(),PlanFingerprint.of(i.registryDigest(),keys,Integer.toString(level.getMinBuildHeight()),Integer.toString(level.getMaxBuildHeight()),Integer.toString(level.getSeaLevel()),"terrain-metrics-v2-inland4-noise256").value(),i.overrideDigest(),i.protectionDigest());
    }
    private static void analysisCountAllowed(java.util.Map<String,Long> values,String key){if(!values.containsKey(key)&&values.size()>=4096)throw new IllegalStateException("Analysis histogram bound exceeded");Math.addExact(values.getOrDefault(key,0L),1L);}
    /** Pure routing policy: OFF retains manual loaded-chunk behavior; no multi-chunk lease. */
    public static net.minecraft.world.level.ChunkPos rollbackLoadingTarget(WorldPrepSavedData.Operation operation,
            WorldPrepSavedData.Area area, boolean enabled) {
        if (!enabled) return null;
        boolean ecologyRollback=switch(operation) {
            case ROLLBACK_SOILS, ROLLBACK_TREES, ROLLBACK_FLORA, ROLLBACK_AQUATIC -> true;
            default -> false;
        };
        if (!ecologyRollback) return null;
        if (area.minX()>area.maxX() || area.minZ()>area.maxZ()
                || (area.minX()>>4)!=(area.maxX()>>4) || (area.minZ()>>4)!=(area.maxZ()>>4))
            throw new IllegalStateException("Automatic ecology rollback loading requires a single-chunk area");
        return new net.minecraft.world.level.ChunkPos(area.minX()>>4,area.minZ()>>4);
    }

    public static boolean applyPlannedCell(ServerLevel level,WorldPrepSavedData d,WorldPrepSavedData.Area area,WorldPrepSavedData.Plan plan,WorldPrepSavedData.Snapshot snapshot,WorldPrepSavedData.BiomeCell cell){return applyPlannedCell(level,d,area,plan,snapshot,cell,0);}
    private static boolean applyPlannedCell(ServerLevel level,WorldPrepSavedData d,WorldPrepSavedData.Area area,WorldPrepSavedData.Plan plan,WorldPrepSavedData.Snapshot snapshot,WorldPrepSavedData.BiomeCell cell,long sequence){var bounds=BiomeBounds.inside(area);if(!bounds.contains(cell.quartX(),cell.quartZ())||!bounds.footprintInside(area,cell.quartX(),cell.quartZ()))throw new IllegalStateException("Planned cell outside area");PlanIdentity.requireCurrent(plan.input,PlanIdentity.capture(level,d,area));int bx=QuartPos.toBlock(cell.quartX())+2,bz=QuartPos.toBlock(cell.quartZ())+2;ServerExistingChunkAccess.requireBlock(level,area,bx,level.getMinBuildHeight(),bz);BiomePlanGuard.requireCurrent(cell.terrainSignature(),MinecraftTerrainSampler.sample(level,bx,bz).signature());if(!MinecraftTerrainSampler.matchesMask(level,area.mask(),QuartPos.toBlock(cell.quartX()),QuartPos.toBlock(cell.quartZ()),4,4))throw new IllegalStateException("Sealed biome mutation is outside area mask");if(d.protectedAt(area.dimension(),cell.quartX(),cell.quartZ()))throw new IllegalStateException("Sealed biome mutation is protected");requireSnapshotOwnership(snapshot,area,plan.fingerprint);var proposed=BiomeRuntime.inspectColumn(level,cell.quartX(),cell.quartZ(),cell.biome());var durable=RuntimeBiomeJournal.publishOrRead(level,snapshot,proposed);d.changed();level.getDataStorage().save();for(var owned:durable)BiomeRuntime.applyOwnedCell(level,owned);return true;}
    private static void rollback(ServerLevel level,WorldPrepSavedData d,UUID id,WorldPrepSavedData.Job job,int budget,WorldPrepSavedData.Area area,BiomeBounds bounds){
        var snapshot=validateRollbackSnapshot(d,job,area);
        RuntimeBiomeJournal.requireManifest(level,snapshot);
        preflightBiomeRollback(level,d,area,bounds,snapshot);
        var slice=new BiomeRollbackSlice(budget);
        var touched=new LinkedHashSet<LevelChunk>();
        RuntimeBiomeJournal.visit(level,snapshot,(sequence,cells)->{
            for(var cell:cells){
                String current=BiomeRuntime.biomeId(level,cell.quartX(),cell.quartY(),cell.quartZ());
                slice.reconcileCell(cell.beforeBiome(),cell.appliedBiome(),current,()->{
                    BiomeRuntime.restoreOwnedCell(level,cell);
                    touched.add(ServerExistingChunkAccess.requireChunk(level,QuartPos.toBlock(cell.quartX())>>4,QuartPos.toBlock(cell.quartZ())>>4));
                });
            }
        });
        BiomeRuntime.refresh(level,touched);
        // The authenticated journal and actual palette reconcile a legacy inflated cursor too.
        var advanced=job.withCursor(slice.reconciledCells());
        if(!slice.pending())advanced=advanced.withState(WorldPrepSavedData.JobState.COMPLETED);
        d.jobs().put(id,advanced);d.changed();level.getDataStorage().save();
    }
    private static void preflightBiomeRollback(ServerLevel level,WorldPrepSavedData d,WorldPrepSavedData.Area area,BiomeBounds bounds,WorldPrepSavedData.Snapshot snapshot){RuntimeBiomeJournal.visit(level,snapshot,(sequence,cells)->{for(var cell:cells){if(!bounds.contains(cell.quartX(),cell.quartZ())||!bounds.footprintInside(area,cell.quartX(),cell.quartZ()))throw new IllegalStateException("Snapshot cell outside owned area");if(d.protectedAt(area.dimension(),cell.quartX(),cell.quartZ()))throw new IllegalStateException("Rollback cell is now protected");String current=BiomeRuntime.biomeId(level,cell.quartX(),cell.quartY(),cell.quartZ());if(!current.equals(cell.beforeBiome())&&!current.equals(cell.appliedBiome()))throw new IllegalStateException("Biome ownership conflict during rollback");}});}
    private static void reconcileBiomeApply(ServerLevel level,WorldPrepSavedData d,WorldPrepSavedData.Area area,WorldPrepSavedData.Snapshot snapshot){if(snapshot.pageCount==0&&!java.nio.file.Files.exists(fr.proutlost.worldprep.storage.PagedJournalStore.manifestPath(RuntimeBiomeJournal.directory(level,snapshot.id))))return;RuntimeBiomeJournal.requireManifest(level,snapshot);RuntimeBiomeJournal.visit(level,snapshot,(sequence,cells)->{for(var cell:cells){if(d.protectedAt(area.dimension(),cell.quartX(),cell.quartZ()))throw new IllegalStateException("Journaled biome mutation is protected");String current=BiomeRuntime.biomeId(level,cell.quartX(),cell.quartY(),cell.quartZ());if(!current.equals(cell.beforeBiome())&&!current.equals(cell.appliedBiome()))throw new IllegalStateException("Biome ownership conflict during apply recovery");}});RuntimeBiomeJournal.visit(level,snapshot,(sequence,cells)->{for(var cell:cells)BiomeRuntime.applyOwnedCell(level,cell);});d.changed();}
    /** Rollback is owned by its exact durable snapshot, independently of newer previews. */
    private static WorldPrepSavedData.Snapshot validateRollbackSnapshot(WorldPrepSavedData d,WorldPrepSavedData.Job job,WorldPrepSavedData.Area area){
        if(job.snapshotId()==null)throw new IllegalStateException("Job has no snapshot ownership");
        var snapshot=d.snapshots().get(job.snapshotId());
        if(snapshot==null||snapshot.planId==null||!snapshot.dimension.equals(area.dimension())||!snapshot.area.equals(area.id())||!snapshot.pass.equals("BIOMES"))throw new IllegalStateException("Snapshot ownership mismatch");
        // requireManifest immediately afterwards authenticates plan UUID/root and every journal page.
        return snapshot;
    }
    private static WorldPrepSavedData.Snapshot validateSnapshot(WorldPrepSavedData d,WorldPrepSavedData.Job job,WorldPrepSavedData.Area area,WorldPrepSavedData.Plan plan){if(job.snapshotId()==null)throw new IllegalStateException("Job has no snapshot ownership");var s=d.snapshots().get(job.snapshotId());if(s==null||plan==null)throw new IllegalStateException("Snapshot ownership mismatch");requireSnapshotOwnership(s,area,plan.fingerprint);if(job.operation()==WorldPrepSavedData.Operation.APPLY_BIOMES&&!s.jobId.equals(job.id()))throw new IllegalStateException("Snapshot apply-job ownership mismatch");return s;}
    public static void requireSnapshotOwnership(WorldPrepSavedData.Snapshot s,WorldPrepSavedData.Area area,String fingerprint){if(!s.dimension.equals(area.dimension())||!s.area.equals(area.id())||!s.pass.equals("BIOMES")||!s.planFingerprint.equals(fingerprint))throw new IllegalStateException("Snapshot ownership mismatch");}
    private static String chooseBiome(WorldPrepSavedData d,MinecraftTerrainSampler.Sample s,long seed,int x,int z){var o=d.overrides().get(WorldPrepSavedData.cellKey(x,z));if(o!=null&&o.exactBiome()!=null)return o.exactBiome();if(o!=null&&o.palette()!=null)return palette(o.palette(),s);if(s.waterDepth()>20)return"minecraft:deep_ocean";if(s.waterDepth()>6)return"minecraft:ocean";if(s.waterDepth()>0)return"minecraft:lukewarm_ocean";if(s.surfaceY()>=133)return"minecraft:jagged_peaks";if(s.slope()>=8)return"minecraft:windswept_hills";if(s.coastDistance()<=4)return s.slope()>3?"minecraft:stony_shore":"minecraft:beach";long v=seed^((long)x*341873128712L)^((long)z*132897987541L);return(v&3)==0?"minecraft:forest":"minecraft:plains";}
    private static String palette(String p,MinecraftTerrainSampler.Sample s){return switch(p){case"coastal"->"minecraft:beach";case"lowland_forest"->"minecraft:forest";case"open_lowland"->"minecraft:plains";case"marine_deep"->"minecraft:deep_ocean";case"marine"->"minecraft:ocean";default->throw new IllegalStateException("Unknown palette override "+p);};}
    private static String fingerprint(WorldPrepSavedData.Plan p){var parts=p.cells.values().stream().sorted(Comparator.comparingInt(WorldPrepSavedData.BiomeCell::quartZ).thenComparingInt(WorldPrepSavedData.BiomeCell::quartX)).map(c->c.quartX()+","+c.quartZ()+","+c.biome()+","+c.terrainSignature()).toArray(String[]::new);String cells=PlanFingerprint.of(parts).value();return PlanFingerprint.of(PlanIdentity.fingerprint(p.input),cells).value();}
    private static void publishBiome(ServerLevel level,WorldPrepSavedData d,WorldPrepSavedData.Area area,BiomeBounds bounds,WorldPrepSavedData.Plan plan){try{Iterable<WorldPrepSavedData.BiomeCell> entries=plan.cells.isEmpty()?()->new Iterator<>(){long cursor;public boolean hasNext(){return cursor<bounds.cells();}public WorldPrepSavedData.BiomeCell next(){if(!hasNext())throw new NoSuchElementException();int qx=bounds.minQuartX()+(int)(cursor%bounds.width()),qz=bounds.minQuartZ()+(int)(cursor++/bounds.width());if(!bounds.footprintInside(area,qx,qz))throw new IllegalStateException("Bounds invariant failed");var sample=MinecraftTerrainSampler.sample(level,QuartPos.toBlock(qx)+2,QuartPos.toBlock(qz)+2);return new WorldPrepSavedData.BiomeCell(qx,qz,chooseBiome(d,sample,plan.input.seed(),qx,qz),sample.signature());}}:plan.cells.values().stream().filter(cell->MinecraftTerrainSampler.matchesMask(level,area.mask(),QuartPos.toBlock(cell.quartX()),QuartPos.toBlock(cell.quartZ()),4,4)).toList();plan.planId=UUID.randomUUID();var publication=PagedPlanStore.publish(RuntimePlanStore.root(level),plan.planId,PassId.BIOMES,area.id(),area.dimension(),plan.input.profile(),PlanIdentity.fingerprint(plan.input),RuntimePlanStore.ENGINE_VERSION,RuntimePlanStore.PLANNER_VERSION,RuntimePlanStore.ENTRIES_PER_PAGE,unprotectedBiomeEntries(entries,level,d,area),RuntimePlanStore.BIOME_CODEC);plan.entryCount=publication.manifest().pages().stream().mapToLong(PlanManifest.PageReference::entryCount).sum();plan.fingerprint=publication.manifest().rootFingerprint();plan.cells.clear();}catch(IOException e){throw new IllegalStateException("Biome plan publication failed",e);}}
    private static Iterable<WorldPrepSavedData.BiomeCell> unprotectedBiomeEntries(Iterable<WorldPrepSavedData.BiomeCell> source,ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area){return()->new Iterator<>(){
        final Iterator<WorldPrepSavedData.BiomeCell> iterator=source.iterator();WorldPrepSavedData.BiomeCell next;boolean ready;
        private void advance(){while(!ready&&iterator.hasNext()){var cell=iterator.next();if(!data.protectedAt(area.dimension(),cell.quartX(),cell.quartZ())&&MinecraftTerrainSampler.matchesMask(level,area.mask(),QuartPos.toBlock(cell.quartX()),QuartPos.toBlock(cell.quartZ()),4,4)){next=cell;ready=true;}}}
        public boolean hasNext(){advance();return ready;}public WorldPrepSavedData.BiomeCell next(){advance();if(!ready)throw new NoSuchElementException();ready=false;return next;}
    };}
    private static PlanManifest biomeManifest(ServerLevel level,WorldPrepSavedData.Plan plan){try{var m=PagedPlanStore.readManifest(RuntimePlanStore.directory(level,plan.planId));if(!m.planId().equals(plan.planId)||m.passId()!=PassId.BIOMES||!m.rootFingerprint().equals(plan.fingerprint))throw new IOException("Exact biome manifest mismatch");m.requireApplicable();fr.proutlost.worldprep.storage.DurablePageStore.forceDirectory(RuntimePlanStore.directory(level,plan.planId));return m;}catch(IOException e){throw new IllegalStateException("Sealed biome manifest unavailable",e);}}
    public static WorldPrepSavedData.Area requireArea(WorldPrepSavedData d,String id){var a=d.areas().get(id);if(a==null)throw new IllegalArgumentException("Unknown area "+id);return a;}public static void assertUnlocked(WorldPrepSavedData d,WorldPrepSavedData.Area requested){for(var j:d.jobs().values())if((j.operation().name().startsWith("APPLY")||j.operation().name().startsWith("ROLLBACK")||j.operation()==WorldPrepSavedData.Operation.BATCH_ECOLOGY)&&(j.state()==WorldPrepSavedData.JobState.QUEUED||j.state()==WorldPrepSavedData.JobState.RUNNING||j.state()==WorldPrepSavedData.JobState.PAUSED)&&requireArea(d,j.area()).overlaps(requested))throw new IllegalStateException("Overlapping destructive job "+j.id());}private static void requireEnabled(){if(!WorldPrepConfig.ENABLED.get())throw new IllegalStateException("WorldPrep is disabled by configuration");}private WorldPrepRuntime(){}
}
