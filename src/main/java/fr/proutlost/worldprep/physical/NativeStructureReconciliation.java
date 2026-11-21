package fr.proutlost.worldprep.physical;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.runtime.CandidateGuard;
import fr.proutlost.worldprep.structure.StructureInstancePlan;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Explicit audit-only recovery for one fully placed native site.
 * Reconstructs the ORIGINAL StructureStart from persisted NBT and original
 * immutable allocation/plan/63-or-other-bounded-chunk receipts. Never calls
 * Structure.generate, NativeRegistryStructurePass.prepare or placeInChunk.
 * The existing failed/incomplete batch remains historical; completion is a new
 * reconciliation record whose imported location binds the complete new audit.
 */
public final class NativeStructureReconciliation {
    private static final Gson JSON=new Gson();
    private static final Map<ServerLevel,Session> ACTIVE=new WeakHashMap<>();
    private static final Map<ServerLevel,String> STATUS=new WeakHashMap<>();
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{
        var t=new Thread(r,"worldprep-native-reconciliation-readonly");t.setDaemon(true);return t;
    });
    public static boolean active(ServerLevel level){return ACTIVE.containsKey(level);}
    public static String status(ServerLevel level){return STATUS.getOrDefault(level,"IDLE_AUDIT_ONLY_NO_GENERATION_REPLAY");}
    private static void guard(ServerLevel level) {
        NativePhysicalRuntime.requireThread(level);CandidateGuard.requireCandidate(level);
        if(!WorldPrepConfig.STRUCTURES_ONLY.get()||WorldPrepConfig.ENABLED.get()
                ||!WorldPrepConfig.PHYSICAL_PASSES.get()||WorldPrepConfig.MODE.get()!=WorldPrepConfig.Mode.TEST)
            throw new IllegalStateException("Audit-only reconciliation requires the standalone TEST candidate profile");
        if(level.getServer().getPlayerCount()!=0)throw new IllegalStateException("Players present during native reconciliation");
    }
    public static UUID start(ServerLevel level,UUID batch)throws IOException {
        guard(level);
        if(batch==null||ACTIVE.containsKey(level)||NativePhysicalRuntime.hasActive(level)
                ||NativeQualificationRuntime.active(level)||WorldPrepFeatureModule.active(level)||WorldPrepStructureModule.status(level).startsWith("RUNNING")||WorldPrepFaunaModule.active(level))
            throw new IOException("Another physical/audit owner is active");
        Path root=level.getServer().getWorldPath(LevelResource.ROOT).resolve("worldprep-v2/structure-module/"+batch);
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(root))
            throw new IOException("Original native batch directory missing");
        if(Files.exists(root.resolve("placement-1"),LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Bounded audit recovery expects a single-site batch");
        Path sitePath=root.resolve("placement-0");
        if(!Files.isDirectory(sitePath,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(sitePath))
            throw new IOException("Original placement directory missing");
        var journal=new PhysicalStore(root);var site=new PhysicalStore(sitePath);
        if(journal.exists("native-reconciliation-complete")) {
            STATUS.put(level,"ALREADY_RECONCILED_NATIVE_AUDIT "+batch);return batch;
        }
        if(site.exists("native-disk-verified")||site.exists("rollback-intent"))
            throw new IOException("Existing native certificate/rollback requires explicit separate review");
        var request=journal.read("request",WorldPrepStructureModule.Request.class);
        var allocations=journal.read("allocations",StructureInstancePlan[].class);
        var declared=site.read("native-all-chunks-placed",NativeRegistryStructurePass.NativeReceipt.class);
        JsonObject plan=JsonParser.parseString(site.readText("native-plan")).getAsJsonObject();
        if(!request.batch().equals(batch)||request.worldSeed()!=level.getSeed()||request.standaloneArea()==null
                ||!request.standaloneArea().dimension().equals(level.dimension().location().toString())
                ||allocations.length!=1||declared.totalChunks()<1||declared.totalChunks()>192
                ||declared.completedChunks()!=declared.totalChunks()||declared.changedBlocks()<1
                ||!"PLACED_UNVERIFIED_DISK".equals(declared.state())
                ||!declared.dimension().equals(level.dimension().location().toString()))
            throw new IOException("Native reconciliation world/batch/completion identity mismatch");
        var allocation=allocations[0];
        if(!allocation.structureId().equals(declared.registryId())
                ||!request.directorTagsByStructure().containsKey(declared.registryId())
                ||!declared.operation().toString().equals(plan.get("operation").getAsString())
                ||!declared.registryId().equals(plan.get("registry").getAsString())
                ||!declared.dimension().equals(plan.get("dimension").getAsString())
                ||!declared.conditions().name().equals(plan.get("conditions").getAsString())
                ||declared.totalChunks()!=plan.get("totalChunks").getAsInt()
                ||!declared.actualBounds().equals(JSON.fromJson(plan.get("bounds"),NativeRegistryStructurePass.Bounds.class)))
            throw new IOException("Original native plan/receipt mismatch");
        UUID expected=UUID.nameUUIDFromBytes(("worldprep-native-registry-v1/"+declared.dimension()+"/"
            +allocation.instanceId()+"/"+declared.registryId()).getBytes(StandardCharsets.UTF_8));
        if(!expected.equals(declared.operation()))throw new IOException("Native allocation operation binding changed");
        if(request.nativeConditionsByStructure().getOrDefault(declared.registryId(),NativeRegistryStructurePass.Conditions.NATIVE)
                !=declared.conditions())throw new IOException("Original native import policy mismatch");
        var session=new Session(batch,request,allocation,declared,plan,root,journal,site);
        session.journal.write("native-reconciliation-start-"+session.attempt,Map.of(
            "batch",batch.toString(),"operation",declared.operation().toString(),"attempt",session.attempt.toString(),
            "mode","AUDIT_ONLY_NO_GENERATION_REPLAY","startedAt",Instant.now().toString(),
            "originalPlanSha256",PhysicalStore.sha(site.readText("native-plan")),
            "originalAllChunksSha256",PhysicalStore.sha(site.readText("native-all-chunks-placed"))));
        ACTIVE.put(level,session);STATUS.put(level,"RECOVERING_PERSISTED_NATIVE_START "+batch);return batch;
    }
    public static void tick(ServerTickEvent.Post event){
        for(var level:event.getServer().getAllLevels()) {
            var session=ACTIVE.get(level);if(session==null)continue;
            try {
                guard(level);
                if(session.step(level)) {
                    ACTIVE.remove(level);session.close();
                    STATUS.put(level,"COMPLETE_RECONCILED_NATIVE_AUDIT "+session.batch+" location="+session.location
                        +" settlements="+session.settlements);
                } else STATUS.put(level,"RUNNING_AUDIT_ONLY "+session.batch+" checked="
                    +(session.audit==null?0:session.audit.checkedChunks())+"/"+session.declared.totalChunks());
            }catch(Exception e) {
                ACTIVE.remove(level);session.close();
                STATUS.put(level,"FAILED_NATIVE_AUDIT_ONLY "+session.batch+": "+e.getMessage());
                try{session.journal.write("native-reconciliation-failed-"+session.attempt,Map.of(
                    "batch",session.batch.toString(),"attempt",session.attempt.toString(),"failedAt",Instant.now().toString(),
                    "mode","AUDIT_ONLY_NO_GENERATION_REPLAY","reason",String.valueOf(e.getMessage())));}catch(IOException ignored){}
                org.slf4j.LoggerFactory.getLogger(NativeStructureReconciliation.class).warn("Native audit-only reconciliation failed {}",session.batch,e);
            }
        }
    }
    public static void pause(ServerLevel level){
        NativePhysicalRuntime.requireThread(level);var s=ACTIVE.remove(level);
        if(s!=null){s.close();STATUS.put(level,"PAUSED_NATIVE_AUDIT_ONLY "+s.batch);}
    }
    public static void unload(LevelEvent.Unload event){if(event.getLevel() instanceof ServerLevel level)pause(level);}

    private static final class Session implements AutoCloseable {
        final UUID batch,attempt=UUID.randomUUID();
        final WorldPrepStructureModule.Request request;
        final StructureInstancePlan allocation;
        final NativeRegistryStructurePass.NativeReceipt declared;
        final JsonObject plan;
        final Path root;
        final PhysicalStore journal,site;
        NativeChunkWindow originLease;
        CompletableFuture<CompoundTag> originRead;
        NativeRegistryStructureAudit audit;
        UUID location;
        int settlements;
        Session(UUID batch,WorldPrepStructureModule.Request request,StructureInstancePlan allocation,
                NativeRegistryStructurePass.NativeReceipt declared,JsonObject plan,Path root,
                PhysicalStore journal,PhysicalStore site) {
            this.batch=batch;this.request=request;this.allocation=allocation;this.declared=declared;
            this.plan=plan;this.root=root;this.journal=journal;this.site=site;
        }
        boolean step(ServerLevel level)throws IOException {
            if(audit==null) {
                var origin=new ChunkPos(allocation.x()>>4,allocation.z()>>4);
                if(originLease==null)originLease=new NativeChunkWindow(level);
                if(!originLease.blocks(origin.getMinBlockX(),origin.getMinBlockZ(),origin.getMaxBlockX(),origin.getMaxBlockZ()))return false;
                if(originRead==null) {
                    var region=DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
                    originRead=CompletableFuture.supplyAsync(()->{
                        try{return ReadOnlyChunkNbt.read(region,origin.x,origin.z);}
                        catch(IOException e){throw new CompletionException(e);}
                    },IO);return false;
                }
                if(!originRead.isDone())return false;
                CompoundTag nbt;
                try{nbt=originRead.join();}catch(CompletionException e){throw new IOException("Persisted native start recovery read failed",e.getCause());}
                var starts=nbt.getCompound("structures").getCompound("starts");
                var tag=starts.getCompound(declared.registryId());
                if(!declared.registryId().equals(tag.getString("id"))||tag.getInt("ChunkX")!=origin.x||tag.getInt("ChunkZ")!=origin.z)
                    throw new IOException("Persisted native start identity mismatch");
                var nativeStart=StructureStart.loadStaticStart(StructurePieceSerializationContext.fromLevel(level),tag,level.getSeed());
                if(nativeStart==null||!nativeStart.isValid()||!nativeStart.getChunkPos().equals(origin)
                        ||nativeStart.getPieces().size()!=plan.get("nativePieces").getAsInt())
                    throw new IOException("Persisted native pieces did not reopen exactly");
                var registry=level.registryAccess().registryOrThrow(Registries.STRUCTURE);
                var structure=registry.get(ResourceLocation.parse(declared.registryId()));
                if(structure==null||nativeStart.getStructure()!=structure)
                    throw new IOException("Installed native structure registry binding changed");
                var writable=NativeRegistryStructurePass.writableBounds(NativeRegistryStructurePass.Bounds.from(nativeStart.getBoundingBox()),
                    level.getMinBuildHeight(),level.getMaxBuildHeight());
                if(!writable.equals(declared.actualBounds()))throw new IOException("Persisted native geometry differs from original placed bounds");
                var b=declared.actualBounds();var chunks=new ArrayList<ChunkPos>();long changes=0;
                long rectangle=((long)(b.maxX()>>4)-(b.minX()>>4)+1)*((long)(b.maxZ()>>4)-(b.minZ()>>4)+1);
                if(rectangle!=declared.totalChunks()||rectangle<1||rectangle>192)
                    throw new IOException("Recovered footprint exceeds original bounded chunk count");
                for(int z=b.minZ()>>4;z<=(b.maxZ()>>4);z++)for(int x=b.minX()>>4;x<=(b.maxX()>>4);x++) {
                    int i=chunks.size();var delta=site.read("native-chunk-placed-"+i,NativeRegistryStructurePass.ChunkDelta.class);
                    if(delta.chunkX()!=x||delta.chunkZ()!=z||delta.changedBlocks()<0||!site.exists("native-chunk-intent-"+i))
                        throw new IOException("Original native chunk intent/completion coverage changed");
                    chunks.add(new ChunkPos(x,z));changes+=delta.changedBlocks();
                }
                if(chunks.size()!=declared.totalChunks()||changes!=declared.changedBlocks())
                    throw new IOException("Native chunk totals do not match original completed placement");
                var adjustment=plan.has("terrainAdjustment")
                    ?JSON.fromJson(plan.get("terrainAdjustment"),NativeStructureTerrainImport.Adjustment.class)
                    :NativeStructureTerrainImport.Adjustment.unchanged(JSON.fromJson(plan.get("nativeGeneratorBounds"),NativeRegistryStructurePass.Bounds.class));
                var prepared=new NativeRegistryStructurePass.Prepared(declared.operation(),declared.registryId(),declared.dimension(),
                    declared.conditions(),structure,nativeStart,declared.actualBounds(),chunks,adjustment);
                originLease.close();originLease=null;originRead=null;
                audit=new NativeRegistryStructureAudit(prepared,site);return false;
            }
            if(!audit.step(level))return false;
            settlements=audit.settledRailCount();
            var published=NativeVerifiedStructureCatalog.publishNative(site,request.directorTagsByStructure().get(declared.registryId()));
            location=published.locationId();
            journal.write("native-reconciliation-complete",Map.of(
                "batch",batch.toString(),"attempt",attempt.toString(),"operation",declared.operation().toString(),
                "locationId",location.toString(),"mode","AUDIT_ONLY_NO_GENERATION_REPLAY",
                "status","RECONCILED_PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED","completedAt",Instant.now().toString(),
                "nativeDiskReceiptSha256",PhysicalStore.sha(site.readText("native-disk-verified")),
                "nativeLocationRecordSha256",PhysicalStore.sha(site.readText("director-native-location-candidate")),
                "settledRailCount",settlements));
            return true;
        }
        public void close(){
            if(originLease!=null){originLease.close();originLease=null;}
            if(originRead!=null){originRead.cancel(false);originRead=null;}
            if(audit!=null)audit.close();
        }
    }
    private NativeStructureReconciliation(){}
}
