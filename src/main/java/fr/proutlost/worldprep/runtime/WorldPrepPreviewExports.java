package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.persistence.*;
import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.plan.*;
import fr.proutlost.worldprep.preview.PreviewRenderer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.core.registries.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** One bounded, headless file-export worker per server. Only immutable snapshots cross this boundary. */
public final class WorldPrepPreviewExports {
    private static final Map<MinecraftServer,Worker> WORKERS=new IdentityHashMap<>();
    private static final Set<MinecraftServer> STOPPED=Collections.newSetFromMap(new WeakHashMap<>());
    private static final AtomicInteger THREADS=new AtomicInteger();
    private WorldPrepPreviewExports(){}
    public static CompletionStage<PreviewRenderer.Report> export(ServerLevel level,String passName,String areaId){
        var server=level.getServer();if(!server.isSameThread())throw new IllegalStateException("PNG capture requires server actor");
        if(!WorldPrepConfig.ENABLED.get()||STOPPED.contains(server))throw new IllegalStateException("WorldPrep PNG export unavailable");
        var worker=WORKERS.computeIfAbsent(server,key->new Worker());
        if(!worker.slots.tryAcquire())throw new IllegalStateException("PNG export busy: one running and one queued request maximum");
        PreviewRenderer.Request request;
        try{request=capture(level,PassId.valueOf(passName.toUpperCase(Locale.ROOT)),areaId);}
        catch(RuntimeException|Error failure){worker.slots.release();throw failure;}
        return worker.submit(request);
    }
    private static PreviewRenderer.Request capture(ServerLevel level,PassId pass,String areaId){
        if(pass!=PassId.BIOMES&&pass!=PassId.GEOLOGY&&pass!=PassId.ORES&&pass!=PassId.SOILS&&pass!=PassId.FLORA&&pass!=PassId.TREES&&pass!=PassId.AQUATIC)throw new IllegalArgumentException("No sealed spatial preview for this pass");
        var data=WorldPrepRuntime.data(level);var area=Objects.requireNonNull(data.areas().get(areaId),"Unknown preview area");
        if(!area.dimension().equals(level.dimension().location().toString()))throw new IllegalArgumentException("Preview dimension mismatch");
        var inputDetails=new TreeMap<String,String>();inputDetails.put("area_mask",area.mask().name());inputDetails.put("tree_mode",area.treeMode().name());UUID id;String profile,root,input;long seed,count;
        if(pass==PassId.BIOMES){
            var plan=Objects.requireNonNull(data.plans().get(areaId),"A completed BIOMES preview is required");
            if(plan.planId==null||plan.fingerprint.isBlank())throw new IllegalStateException("Only persisted sealed previews may be exported");
            PlanIdentity.requireCurrent(plan.input,PlanIdentity.capture(level,data,area));
            id=plan.planId;profile=plan.input.profile();root=plan.fingerprint;input=PlanIdentity.fingerprint(plan.input);seed=plan.input.seed();count=plan.entryCount;
            inputDetails.put("profile_digest",plan.input.profileDigest());inputDetails.put("config_digest",plan.input.configDigest());
            inputDetails.put("registry_digest",plan.input.registryDigest());inputDetails.put("override_digest",plan.input.overrideDigest());inputDetails.put("protection_digest",plan.input.protectionDigest());
        }else{
            var blocks=BlockMutationRuntime.data(level);if(!blocks.compatible())throw new IllegalStateException("Incompatible persisted block preview");
            var plan=Objects.requireNonNull(blocks.plans().get(BlockWorldPrepData.planKey(areaId,pass.name())),"A completed block preview is required");
            if(plan.planId()==null||plan.fingerprint().isBlank()||!plan.input.equals(BlockMutationRuntime.readOnlyInputIdentity(level,data,area,pass.name())))throw new IllegalStateException("Block preview is absent, unsealed or stale");
            if(pass==PassId.ORES){var upstream=blocks.plans().get(BlockWorldPrepData.planKey(areaId,"GEOLOGY"));
                if(upstream==null||!Objects.equals(plan.upstreamPlanId(),upstream.planId())||!plan.upstreamRoot().equals(upstream.fingerprint()))throw new IllegalStateException("Exact GEOLOGY upstream changed");
                inputDetails.put("upstream_plan_id",plan.upstreamPlanId().toString());inputDetails.put("upstream_root",plan.upstreamRoot());
            }
            id=plan.planId();profile=plan.profile;root=plan.fingerprint();input=PlanFingerprint.of(plan.input).value();seed=level.getSeed();count=plan.entryCount();
            inputDetails.put("planned_input",plan.input);inputDetails.put("eligible",Long.toString(plan.eligible()));inputDetails.put("skipped",Long.toString(plan.skipped()));
        }
        var registry=pass==PassId.BIOMES?level.registryAccess().registryOrThrow(Registries.BIOME).keySet():BuiltInRegistries.BLOCK.keySet();
        if(registry.size()>PreviewRenderer.MAX_REGISTRY_IDS)throw new IllegalStateException("PNG registry snapshot bound exceeded");
        var registryIds=registry.stream().map(Object::toString).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var protections=data.protections().values().stream().filter(p->p.dimension().equals(area.dimension())&&p.minX()<=area.maxX()&&p.maxX()>=area.minX()&&p.minZ()<=area.maxZ()&&p.maxZ()>=area.minZ()).limit(PreviewRenderer.MAX_POLICY_ENTRIES+1L).sorted(Comparator.comparing(WorldPrepSavedData.ProtectedZone::id)).toList();
        var overrides=data.overrides().values().stream().filter(o->(long)o.quartX()*4<=area.maxX()&&(long)o.quartX()*4+3>=area.minX()&&(long)o.quartZ()*4<=area.maxZ()&&(long)o.quartZ()*4+3>=area.minZ()).limit(PreviewRenderer.MAX_POLICY_ENTRIES+1L).sorted(Comparator.comparingInt(WorldPrepSavedData.OverrideCell::quartZ).thenComparingInt(WorldPrepSavedData.OverrideCell::quartX)).toList();
        // The registry authoring snapshot includes location policies/zones without creating or modifying one.
        var locations=data.locations().authoringJson();if(locations.length>4194304)throw new IllegalStateException("Location metadata byte bound");
        inputDetails.put("effective_protection_digest",data.protectionFingerprint());
        Path output=serverPath(level).resolve("worldprep-v2").resolve("previews");
        return new PreviewRenderer.Request(RuntimePlanStore.directory(level,id),output,id,pass,area.id(),area.dimension(),profile,seed,
            area.minX(),area.minZ(),area.maxX(),area.maxZ(),level.getMinBuildHeight(),level.getMaxBuildHeight(),root,input,count,inputDetails,
            registryIds,protections,overrides,new String(locations,StandardCharsets.UTF_8));
    }
    private static Path serverPath(ServerLevel level){return level.getServer().getWorldPath(LevelResource.ROOT);}
    public static void stopped(ServerStoppedEvent event){
        var server=event.getServer();if(!server.isSameThread())throw new IllegalStateException("PNG shutdown actor");
        STOPPED.add(server);var worker=WORKERS.remove(server);if(worker!=null)worker.close();
    }
    private static final class Worker {
        final Semaphore slots=new Semaphore(2);final AtomicBoolean closed=new AtomicBoolean();
        final Set<Job> jobs=ConcurrentHashMap.newKeySet();
        final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,1,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),task->{var thread=new Thread(task,"worldprep-png-"+THREADS.incrementAndGet());thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
        Worker(){executor.allowCoreThreadTimeOut(true);}
        CompletionStage<PreviewRenderer.Report> submit(PreviewRenderer.Request request){
            var job=new Job(request,this);jobs.add(job);
            try{if(closed.get())throw new RejectedExecutionException("PNG worker closed");executor.execute(job);}
            catch(RuntimeException|Error failure){jobs.remove(job);job.result.completeExceptionally(failure);job.release();throw failure;}
            return job.result;
        }
        void close(){closed.set(true);executor.shutdownNow();for(var job:jobs){job.result.cancel(false);job.release();}jobs.clear();}
    }
    private static final class Job implements Runnable {
        final PreviewRenderer.Request request;final Worker worker;final AtomicBoolean released=new AtomicBoolean();
        final CompletableFuture<PreviewRenderer.Report> result=new CompletableFuture<>();
        Job(PreviewRenderer.Request request,Worker worker){this.request=request;this.worker=worker;}
        void release(){if(released.compareAndSet(false,true))worker.slots.release();}
        public void run(){try{if(worker.closed.get())throw new CancellationException();result.complete(PreviewRenderer.render(request));}
            catch(Throwable failure){result.completeExceptionally(failure);if(failure instanceof Error error)throw error;}
            finally{worker.jobs.remove(this);release();}}
    }
}
