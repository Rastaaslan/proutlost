package fr.proutlost.worldprep.physical;

import com.google.gson.Gson;
import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.CandidateGuard;
import fr.proutlost.worldprep.world.ExistingRegionEntry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Explicit standalone feature import into an operator-marked candidate.
 * No ecology invocation, automatic start, implicit retry or fabricated Director ACK.
 * Native configured-feature capture finishes before any physical cell mutation.
 */
public final class WorldPrepFeatureModule {
    public record Request(int schema,UUID batch,String dimension,long worldSeed,
                          String frozenConfigSha256,List<NativeConfiguredFeaturePass.Site> placements) {
        public Request {
            if(schema!=1||batch==null||dimension==null
                ||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                ||frozenConfigSha256==null||!frozenConfigSha256.matches("[0-9a-f]{64}")
                ||placements==null||placements.isEmpty()||placements.size()>128)
                throw new IllegalArgumentException("Invalid bounded configured-feature request");
            placements=List.copyOf(placements);
            Set<String> sites=new HashSet<>();
            for(var site:placements) {
                if(site==null||!sites.add(site.featureId()+"/"+site.x()+"/"+site.y()+"/"+site.z()))
                    throw new IllegalArgumentException("Duplicate or missing feature site");
            }
        }
    }
    private static final Gson JSON=new Gson();
    private static final Map<ServerLevel,Session> ACTIVE=new WeakHashMap<>();
    private static final Map<ServerLevel,String> STATUS=new WeakHashMap<>();
    private WorldPrepFeatureModule() {}

    private static Request request(ServerLevel level)throws IOException {
        Path file=level.getServer().getWorldPath(LevelResource.ROOT).resolve("worldprep-feature-module.json");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file)
            ||Files.size(file)>262144)throw new IOException("Missing bounded worldprep-feature-module.json");
        Request raw;
        try{raw=JSON.fromJson(Files.readString(file),Request.class);}
        catch(RuntimeException e){throw new IOException("Malformed configured-feature request",e);}
        if(raw==null)throw new IOException("Empty configured-feature request");
        var rebuilt=new ArrayList<NativeConfiguredFeaturePass.Site>();
        for(var s:raw.placements()) {
            var b=s.reservation();
            rebuilt.add(new NativeConfiguredFeaturePass.Site(s.modelId(),s.featureId(),s.templateId(),
                s.x(),s.y(),s.z(),new NativeConfiguredFeaturePass.Bounds(b.minX(),b.minY(),b.minZ(),
                    b.maxX(),b.maxY(),b.maxZ()),s.tags(),s.sitePolicy()));
        }
        return new Request(raw.schema(),raw.batch(),raw.dimension(),raw.worldSeed(),
            raw.frozenConfigSha256(),rebuilt);
    }
    private static void guard(ServerLevel level,Request r) {
        NativePhysicalRuntime.requireThread(level);CandidateGuard.requireCandidate(level);
        if(!WorldPrepConfig.STRUCTURES_ONLY.get()||WorldPrepConfig.ENABLED.get()
            ||!WorldPrepConfig.PHYSICAL_PASSES.get()||WorldPrepConfig.MODE.get()!=WorldPrepConfig.Mode.TEST)
            throw new IllegalStateException("Feature module requires structuresOnly=true, enabled=false, physicalPasses=true, TEST");
        if(!r.dimension().equals(level.dimension().location().toString())||level.getSeed()!=r.worldSeed())
            throw new IllegalStateException("Feature request world/dimension identity mismatch");
        if(level.getServer().getPlayerCount()!=0)
            throw new IllegalStateException("Players present during configured-feature import");
    }
    private static void validateExisting(ServerLevel level,Request r)throws IOException {
        Path region=DimensionType.getStorageFolder(level.dimension(),
            level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
        for(var site:r.placements()) {
            NativeConfiguredFeaturePass.validateRegistry(level,site);
            var b=site.reservation();
            for(int z=b.minZ()>>4;z<=b.maxZ()>>4;z++)
                for(int x=b.minX()>>4;x<=b.maxX()>>4;x++)
                    ExistingRegionEntry.requirePresent(region,x,z);
        }
    }
    /** Registry/disk-entry preview only; START performs detached native capture before mutation. */
    public static String preview(ServerLevel level)throws IOException {
        NativePhysicalRuntime.requireThread(level);CandidateGuard.requireCandidate(level);
        Request r=request(level);guard(level,r);validateExisting(level,r);
        return "FEATURE_REQUEST_VALIDATED batch="+r.batch()+" count="+r.placements().size()
            +" nativeCapture=BEFORE_ANY_MUTATION policy="+NativeConfiguredFeaturePass.POLICY;
    }
    public static UUID start(ServerLevel level)throws IOException {
        NativePhysicalRuntime.requireThread(level);
        if(active(level)||NativePhysicalRuntime.hasActive(level)||NativeQualificationRuntime.active(level)
            ||WorldPrepStructureModule.status(level).startsWith("RUNNING")||NativeStructureReconciliation.active(level)||WorldPrepFaunaModule.active(level))
            throw new IOException("Another physical owner is active");
        Request r=request(level);guard(level,r);validateExisting(level,r);
        Path root=level.getServer().getWorldPath(LevelResource.ROOT)
            .resolve("worldprep-v2/feature-module/"+r.batch()).toAbsolutePath().normalize();
        if(Files.exists(root,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Feature batch was already attempted; no automatic replay");
        PhysicalStore journal=new PhysicalStore(root);journal.write("request",r);
        ACTIVE.put(level,new Session(r,root,journal));
        STATUS.put(level,"RUNNING_CONFIGURED_FEATURES "+r.batch()+" planned="+r.placements().size());
        return r.batch();
    }
    public static boolean active(ServerLevel level){return ACTIVE.containsKey(level);}
    public static String status(ServerLevel level){
        return STATUS.getOrDefault(level,"DISABLED_NOT_STARTED; configured features only");
    }
    public static void pause(ServerLevel level) {
        NativePhysicalRuntime.requireThread(level);Session s=ACTIVE.remove(level);
        if(s!=null){s.close();STATUS.put(level,"PAUSED_NO_AUTORETRY "+s.request.batch()+" placed="+s.cursor);}
    }
    public static void tick(ServerTickEvent.Post event) {
        for(ServerLevel level:event.getServer().getAllLevels()) {
            Session s=ACTIVE.get(level);if(s==null)continue;
            try {
                guard(level,s.request);
                STATUS.put(level,"RUNNING_CONFIGURED_FEATURES "+s.request.batch()+" placed="+s.cursor+"/"
                    +s.request.placements().size()+" phase="+s.phase);
                if(s.step(level)) {
                    ACTIVE.remove(level);s.close();
                    STATUS.put(level,"COMPLETE_FEATURES_PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED "
                        +s.request.batch()+" count="+s.cursor);
                }
            } catch(Exception error) {
                ACTIVE.remove(level);s.close();
                STATUS.put(level,"FAILED_CLOSED_NO_AUTORETRY "+s.request.batch()+": "+error.getMessage());
                org.slf4j.LoggerFactory.getLogger(WorldPrepFeatureModule.class)
                    .error("Configured-feature module stopped; retained evidence requires reconciliation",error);
            }
        }
    }
    public static void unload(LevelEvent.Unload event) {
        if(event.getLevel() instanceof ServerLevel level)pause(level);
    }
    private static final class Session {
        final Request request;final Path root;final PhysicalStore journal;
        int cursor;String phase="LOADING_EXISTING_CHUNKS";
        NativeChunkWindow window;NativeConfiguredFeaturePass.Plan plan;
        NativeConfiguredFeaturePass.Audit audit;PhysicalStore site;
        boolean applied,saved;
        Session(Request request,Path root,PhysicalStore journal) {
            this.request=request;this.root=root;this.journal=journal;
        }
        boolean step(ServerLevel level)throws IOException {
            if(cursor==request.placements().size()) {
                journal.write("complete",Map.of("batch",request.batch().toString(),"placed",cursor,
                    "status","PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED","route","CONFIGURED_FEATURE_ADAPTER"));
                return true;
            }
            var wanted=request.placements().get(cursor);var b=wanted.reservation();
            if(window==null)window=new NativeChunkWindow(level);
            if(!window.blocks(b.minX(),b.minZ(),b.maxX(),b.maxZ()))return false;
            if(plan==null) {
                phase="CAPTURING_NATIVE_FEATURE";
                // No overlapping native structure starts or references are accepted in the reservation.
                for(int z=b.minZ()>>4;z<=b.maxZ()>>4;z++)for(int x=b.minX()>>4;x<=b.maxX()>>4;x++) {
                    var chunk=level.getChunkSource().getChunkNow(x,z);
                    if(chunk==null||chunk.getPersistedStatus()!=net.minecraft.world.level.chunk.status.ChunkStatus.FULL)
                        throw new IOException("Feature reservation lease is not existing FULL");
                    if(chunk.getAllStarts().values().stream().anyMatch(s->s!=null&&s.isValid())
                        ||NativeRegistryStructurePass.hasReferences(chunk.getAllReferences().values()))
                        throw new IOException("Existing native structure in feature reservation");
                }
                var spawn=level.getSharedSpawnPos();
                if(b.minX()<=spawn.getX()+16&&b.maxX()>=spawn.getX()-16
                    &&b.minZ()<=spawn.getZ()+16&&b.maxZ()>=spawn.getZ()-16)
                    throw new IOException("Feature reservation intersects spawn access");
                plan=NativeConfiguredFeaturePass.capture(level,wanted,request.batch(),cursor);
                site=new PhysicalStore(root.resolve("placement-"+cursor));site.write("feature-plan",plan);
                var actual=plan.actualBounds();
                NativePhysicalRuntime.claim(level,"feature",
                    new WorldPrepSavedData.Area("feature/"+wanted.featureId()+"/"+wanted.x()+"/"+wanted.z(),
                        request.dimension(),actual.minX(),actual.minZ(),actual.maxX(),actual.maxZ()),plan.operation());
                phase="CAPTURED_BEFORE_MUTATION";return false;
            }
            if(!applied) {
                NativeConfiguredFeaturePass.apply(level,plan,site);
                applied=true;phase="MATERIALIZED_AWAITING_SAVE";return false;
            }
            if(!saved) {
                level.getDataStorage().save();level.save(null,true,false);
                saved=true;phase="VERIFYING_ALL_CHANGED_CELLS_ON_DISK";return false;
            }
            if(audit==null)audit=new NativeConfiguredFeaturePass.Audit(plan,site);
            if(!audit.step(level))return false;
            var location=NativeConfiguredFeaturePass.publish(plan,site);
            journal.write("verified-"+cursor,Map.of("operation",plan.operation().toString(),
                "locationId",location.locationId().toString(),"featureId",wanted.featureId(),
                "status","PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED"));
            org.slf4j.LoggerFactory.getLogger(WorldPrepFeatureModule.class).info(
                "WORLDPrep_FEATURE_PLACED feature={} location={} bounds={} changedBlocks={} tags={}",
                wanted.featureId(),location.locationId(),plan.actualBounds(),plan.cells().size(),location.tags());
            window.close();window=null;plan=null;audit=null;site=null;applied=false;saved=false;cursor++;
            phase="LOADING_EXISTING_CHUNKS";return false;
        }
        void close(){if(window!=null){window.close();window=null;}}
    }
}
