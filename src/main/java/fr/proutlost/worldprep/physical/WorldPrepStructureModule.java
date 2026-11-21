package fr.proutlost.worldprep.physical;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import fr.proutlost.worldprep.biome.v2.TerrainSemantic;
import fr.proutlost.worldprep.orchestration.QualificationBinding;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.CandidateGuard;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import fr.proutlost.worldprep.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * WorldPrep Structures standalone, opt-in module.
 * No ecology invocation, no V5 qualification, no implicit startup or replay.
 * PREVIEW is read-only; START writes only to an operator-marked TEST candidate.
 * Director export receipts are NOT Director import ACKs.
 */
public final class WorldPrepStructureModule {
    public record Request(int schema, UUID batch, String area, long worldSeed,
                          String frozenConfigSha256, int globalLimit, int perRegionLimit,
                          List<StructureCapability> catalog,
                          List<AutomaticStructurePlanner.Candidate> candidates,
                          Map<String,Set<String>> directorTagsByStructure,
                          Map<String,NativeRegistryStructurePass.Conditions> nativeConditionsByStructure,
                          UUID directorWorldId,long directorExportRevision,
                          WorldPrepSavedData.Area standaloneArea) {
        /** Backwards-compatible template-only request constructor. */
        public Request(int schema,UUID batch,String area,long worldSeed,String frozenConfigSha256,
                       int globalLimit,int perRegionLimit,List<StructureCapability> catalog,
                       List<AutomaticStructurePlanner.Candidate> candidates,
                       Map<String,Set<String>> directorTagsByStructure) {
            this(schema,batch,area,worldSeed,frozenConfigSha256,globalLimit,perRegionLimit,
                 catalog,candidates,directorTagsByStructure,Map.of(),null,0L,null);
        }
        public Request(int schema,UUID batch,String area,long worldSeed,String frozenConfigSha256,
                       int globalLimit,int perRegionLimit,List<StructureCapability> catalog,
                       List<AutomaticStructurePlanner.Candidate> candidates,
                       Map<String,Set<String>> directorTagsByStructure,
                       Map<String,NativeRegistryStructurePass.Conditions> nativeConditionsByStructure) {
            this(schema,batch,area,worldSeed,frozenConfigSha256,globalLimit,perRegionLimit,
                 catalog,candidates,directorTagsByStructure,nativeConditionsByStructure,null,0L,null);
        }
        public Request(int schema,UUID batch,String area,long worldSeed,String frozenConfigSha256,
                       int globalLimit,int perRegionLimit,List<StructureCapability> catalog,
                       List<AutomaticStructurePlanner.Candidate> candidates,
                       Map<String,Set<String>> directorTagsByStructure,
                       Map<String,NativeRegistryStructurePass.Conditions> nativeConditionsByStructure,
                       UUID directorWorldId,long directorExportRevision) {
            this(schema,batch,area,worldSeed,frozenConfigSha256,globalLimit,perRegionLimit,
                catalog,candidates,directorTagsByStructure,nativeConditionsByStructure,
                directorWorldId,directorExportRevision,null);
        }
        public Request {
            if(schema!=1||batch==null||area==null||area.isBlank()
                    ||frozenConfigSha256==null||!frozenConfigSha256.matches("[0-9a-f]{64}")
                    ||globalLimit<1||globalLimit>4096||perRegionLimit<1||perRegionLimit>64
                    ||catalog==null||catalog.isEmpty()||catalog.size()>256
                    ||candidates==null||candidates.isEmpty()||candidates.size()>100000
                    ||directorTagsByStructure==null||directorTagsByStructure.size()>256)
                throw new IllegalArgumentException("Invalid bounded standalone structures request");
            catalog=List.copyOf(catalog);candidates=List.copyOf(candidates);
            var tags=new TreeMap<String,Set<String>>();
            for(var entry:directorTagsByStructure.entrySet()) {
                if(entry.getValue()==null||entry.getValue().isEmpty()||entry.getValue().size()>24
                    ||!entry.getValue().contains("proutlost:structure"))
                    throw new IllegalArgumentException("Missing structure Director tag: "+entry.getKey());
                for(var tag:entry.getValue())
                    if(!tag.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                        throw new IllegalArgumentException("Invalid Director tag");
                tags.put(entry.getKey(),Set.copyOf(entry.getValue()));
            }
            for(var capability:catalog)
                if(!tags.containsKey(capability.structureId()))
                    throw new IllegalArgumentException("Every catalog capability requires Director tags");
            directorTagsByStructure=Map.copyOf(tags);
            if(nativeConditionsByStructure==null)nativeConditionsByStructure=Map.of();
            if(nativeConditionsByStructure.size()>256)throw new IllegalArgumentException("Native conditions limit");
            var modes=new TreeMap<String,NativeRegistryStructurePass.Conditions>();
            for(var entry:nativeConditionsByStructure.entrySet()) {
                if(entry.getKey()==null||entry.getValue()==null||!tags.containsKey(entry.getKey()))
                    throw new IllegalArgumentException("Native conditions refer to missing structure");
                modes.put(entry.getKey(),entry.getValue());
            }
            nativeConditionsByStructure=Map.copyOf(modes);
            if(directorWorldId==null&&directorExportRevision!=0L
                 ||directorWorldId!=null&&directorExportRevision<1L)
                throw new IllegalArgumentException("Director G0 export requires explicit WorldId and positive revision");
            if(standaloneArea!=null) {
                if(!area.equals(standaloneArea.id())||standaloneArea.dimension()==null
                        ||!standaloneArea.dimension().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                        ||standaloneArea.minX()>standaloneArea.maxX()
                        ||standaloneArea.minZ()>standaloneArea.maxZ()
                        ||(long)standaloneArea.maxX()-standaloneArea.minX()>32768
                        ||(long)standaloneArea.maxZ()-standaloneArea.minZ()>32768
                        ||Math.max(Math.abs((long)standaloneArea.minX()),Math.abs((long)standaloneArea.maxX()))>29999984
                        ||Math.max(Math.abs((long)standaloneArea.minZ()),Math.abs((long)standaloneArea.maxZ()))>29999984)
                    throw new IllegalArgumentException("Invalid independent structure-only area");
            }
        }
    }
    private static final Gson JSON=new Gson();
    private static final Map<ServerLevel,Session> ACTIVE=new WeakHashMap<>();
    private static final Map<ServerLevel,String> STATUS=new WeakHashMap<>();
    private WorldPrepStructureModule(){}

    private static Request request(ServerLevel level)throws IOException {
        Path world=level.getServer().getWorldPath(LevelResource.ROOT);
        Path file=world.resolve("worldprep-structure-module.json");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file)
                ||Files.size(file)>262144)
            throw new IOException("Missing bounded independent structure-module request");
        return parseRequest(Files.readString(file));
    }
    static Request parseRequest(String text)throws IOException {
        try {
            var object=JsonParser.parseString(text).getAsJsonObject();
            var schema=object.get("schema");
            if(schema==null||!schema.isJsonPrimitive()||!schema.getAsJsonPrimitive().isNumber()
                    ||!"1".equals(schema.getAsString()))
                throw new IOException("Unsupported structure-module request schema");
            var area=object.get("standaloneArea");
            if(area!=null&&!area.isJsonNull()) {
                var fields=area.getAsJsonObject();
                // Schema 1 predates these Area fields. Explicit null/invalid values remain invalid.
                if(!fields.has("mask"))fields.addProperty("mask","FULL_AREA");
                if(!fields.has("treeMode"))fields.addProperty("treeMode","NORMAL");
            }
            var input=JSON.fromJson(object,Request.class);
            if(input==null)throw new IOException("Missing module request");
            return new Request(input.schema(),input.batch(),input.area(),input.worldSeed(),
                    input.frozenConfigSha256(),input.globalLimit(),input.perRegionLimit(),
                    input.catalog(),input.candidates(),input.directorTagsByStructure(),input.nativeConditionsByStructure(),
                    input.directorWorldId(),input.directorExportRevision(),input.standaloneArea());
        } catch(RuntimeException failure) {
            throw new IOException("Invalid structure-module request",failure);
        }
    }
    static void requireSupportedAreaMask(WorldPrepSavedData.Area area)throws IOException {
        if(area.mask()!=WorldPrepSavedData.AreaMask.FULL_AREA)
            throw new IOException("Standalone structures support only FULL_AREA; terrain masks are not implemented");
    }
    private static WorldPrepSavedData.Area parentArea(ServerLevel level,Request request){
        if(fr.proutlost.worldprep.WorldPrepConfig.STRUCTURES_ONLY.get()&&request.standaloneArea()==null)
            throw new IllegalStateException("Structures-only mode requires standaloneArea; legacy ecology is never loaded");
        return request.standaloneArea()!=null ? request.standaloneArea()
            : WorldPrepRuntime.requireArea(WorldPrepRuntime.data(level),request.area());
    }
    private static void guard(ServerLevel level,Request request,WorldPrepSavedData.Area parent){
        if(request.standaloneArea()==null){NativePhysicalRuntime.guard(level,parent);return;}
        NativePhysicalRuntime.requireThread(level);
        CandidateGuard.requireCandidate(level);
        if(!fr.proutlost.worldprep.WorldPrepConfig.STRUCTURES_ONLY.get()
                ||fr.proutlost.worldprep.WorldPrepConfig.ENABLED.get()
                ||!fr.proutlost.worldprep.WorldPrepConfig.PHYSICAL_PASSES.get()
                ||fr.proutlost.worldprep.WorldPrepConfig.MODE.get()!=
                    fr.proutlost.worldprep.WorldPrepConfig.Mode.TEST)
            throw new IllegalStateException("Independent module requires structuresOnly=true, enabled=false, physicalPasses=true, TEST");
        if(level.getServer().getPlayerCount()!=0)
            throw new IllegalStateException("Players present during native structure placement");
        if(level.getServer().getAverageTickTimeNanos()>
                fr.proutlost.worldprep.WorldPrepConfig.MAX_TICK_MILLIS.get()*1_000_000L)
            throw new IllegalStateException("WorldPrep structure LAB tick pressure");
        if(!parent.dimension().equals(level.dimension().location().toString()))
            throw new IllegalStateException("Independent structure dimension mismatch");
    }
    public static List<StructureInstancePlan> preview(ServerLevel level)throws IOException {
        NativePhysicalRuntime.requireThread(level);
        CandidateGuard.requireCandidate(level);
        Request request=request(level);
        var parent=parentArea(level,request);
        requireSupportedAreaMask(parent);
        if(!parent.dimension().equals(level.dimension().location().toString())
                ||level.getSeed()!=request.worldSeed())throw new IOException("Contra world/dimension identity changed");
        var catalog=new StructureCatalog(request.catalog());
        for(var candidate:request.candidates()) {
            if(candidate.x()<parent.minX()||candidate.x()>parent.maxX()
                    ||candidate.z()<parent.minZ()||candidate.z()>parent.maxZ()
                    ||candidate.y()<level.getMinBuildHeight()||candidate.y()>=level.getMaxBuildHeight())
                throw new IOException("Structure candidate outside current parent area");
            var capability=catalog.requireProductionSupported(candidate.structureId());
            boolean template=capability.status()==StructureSupportStatus.EXACT_TEMPLATE
                    &&NativeStructurePass.ADAPTER.equals(capability.adapterFingerprint());
            boolean nativeRegistry=(capability.status()==StructureSupportStatus.EXACT_JIGSAW
                    ||capability.status()==StructureSupportStatus.CUSTOM_SAFE_ADAPTER)
                    &&NativeRegistryStructurePass.ADAPTER.equals(capability.adapterFingerprint());
            if(!template&&!nativeRegistry)
                throw new IOException("Structure adapter unsupported/unpinned: "+candidate.structureId());
            if(template&&request.nativeConditionsByStructure().containsKey(candidate.structureId()))
                throw new IOException("Virtual native conditions not applicable to exact template");
        }
        var result=new AutomaticStructurePlanner().plan(request.candidates(),catalog,
                request.worldSeed(),request.globalLimit(),request.perRegionLimit(),request.frozenConfigSha256());
        if(result.isEmpty())throw new IOException("Planner selected zero eligible structures");
        for(var location:result) {
            var b=location.reservation();
            if(b.minX()<parent.minX()||b.maxX()>parent.maxX()
                    ||b.minZ()<parent.minZ()||b.maxZ()>parent.maxZ())
                throw new IOException("Structure reservation outside parent area");
        }
        return result;
    }
    public static UUID start(ServerLevel level)throws Exception {
        NativePhysicalRuntime.requireThread(level);
        if(ACTIVE.containsKey(level)||NativePhysicalRuntime.hasActive(level)
                ||NativeQualificationRuntime.active(level)||WorldPrepFeatureModule.active(level)||NativeStructureReconciliation.active(level)||WorldPrepFaunaModule.active(level))throw new IOException("Another physical owner active");
        Request input=request(level);
        var placements=preview(level);
        var parent=parentArea(level,input);
        requireSupportedAreaMask(parent); // input and preview are separate bounded file reads
        guard(level,input,parent); // TEST COW only, no ecology replay
        Path root=level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("worldprep-v2/structure-module/"+input.batch()).toAbsolutePath().normalize();
        if(Files.exists(root,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Module batch already attempted; no automatic retry");
        var store=new PhysicalStore(root);
        store.write("request",input);
        store.write("allocations",placements);
        ACTIVE.put(level,new Session(input,parent,new StructureCatalog(input.catalog()),placements,root,store));
        STATUS.put(level,"RUNNING_STANDALONE_STRUCTURES "+input.batch()+" planned="+placements.size());
        return input.batch();
    }
    public static String status(ServerLevel level) {
        return STATUS.getOrDefault(level,"DISABLED_NOT_STARTED; no ecology/V5 auto-start");
    }
    public static void pause(ServerLevel level) {
        NativePhysicalRuntime.requireThread(level);
        var session=ACTIVE.remove(level);
        if(session!=null) {
            session.close();
            STATUS.put(level,"PAUSED_NO_AUTORETRY "+session.input.batch()+" placed="+session.cursor);
        }
    }
    public static void tick(ServerTickEvent.Post event) {
        for(var level:event.getServer().getAllLevels()) {
            var session=ACTIVE.get(level);if(session==null)continue;
            try {
                guard(level,session.input,session.parent);
                STATUS.put(level,"RUNNING "+session.input.batch()+" placed="+session.cursor+"/"+session.placements.size()
                    +(session.nativePass==null?" preparing":" "+session.nativePass.progress()));
                if(session.step(level)) {
                    ACTIVE.remove(level); session.close();
                    STATUS.put(level,"COMPLETE_PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED "+session.input.batch()
                            +" count="+session.placements.size());
                }
            } catch(Exception failure) {
                ACTIVE.remove(level);session.close();
                STATUS.put(level,"FAILED_CLOSED_NO_AUTORETRY "+session.input.batch()+": "+failure.getMessage());
                org.slf4j.LoggerFactory.getLogger(WorldPrepStructureModule.class)
                    .error("Standalone structure module paused; manual reconciliation required",failure);
            }
        }
    }
    public static void unload(LevelEvent.Unload event) {
        if(event.getLevel() instanceof ServerLevel level)pause(level);
    }

    private static final class Session {
        final Request input;final WorldPrepSavedData.Area parent;
        final StructureCatalog catalog;final List<StructureInstancePlan> placements;
        final Path root;final PhysicalStore journal;
        NativeChunkWindow window;
        NativeStructurePass pass;NativeStructureAudit audit;PhysicalStore site;
        NativeRegistryStructurePass.Prepared nativePrepared;
        NativeRegistryStructurePass.Runner nativePass;
        NativeRegistryStructureAudit nativeAudit;
        final List<NativeVerifiedStructureCatalog.NativeLocationRecord> verifiedNative=new ArrayList<>();
        boolean saved;int cursor;
        Session(Request input,WorldPrepSavedData.Area parent,StructureCatalog catalog,
                List<StructureInstancePlan> placements,Path root,PhysicalStore journal){
            this.input=input;this.parent=parent;this.catalog=catalog;
            this.placements=placements;this.root=root;this.journal=journal;
        }
        boolean step(ServerLevel level)throws Exception {
            if(cursor==placements.size()){
                if(input.directorWorldId()!=null) {
                    if(verifiedNative.size()!=placements.size())
                        throw new IOException("Mixed native/template G0 export not yet supported");
                    var g0=DirectorG0LocationExport.build(input.directorWorldId(),
                            input.directorExportRevision(),verifiedNative);
                    String sha=DirectorG0LocationExport.writeOnce(root,g0);
                    journal.write("director-g0-export-ready",Map.of("sha256",sha,
                        "status","EXPORT_ONLY_NO_DIRECTOR_ACK"));
                }
                journal.write("complete",Map.of("batch",input.batch().toString(),
                    "placed",cursor,"status","PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED"));
                return true;
            }
            var allocation=placements.get(cursor);
            var cap=catalog.requireProductionSupported(allocation.structureId());
            if(cap.status()!=StructureSupportStatus.EXACT_TEMPLATE)
                return stepNative(level,allocation,cap);
            if(pass==null){
                if(window==null)window=new NativeChunkWindow(level);
                var b=allocation.envelope();
                if(!window.blocks(b.minX()-16,b.minZ()-16,b.maxX()+16,b.maxZ()+16))return false;
                var footprint=allocation.footprint();
                var rotation=Rotation.valueOf(allocation.rotation().name());
                int minimumX=0,minimumZ=0;
                for(int x:new int[]{0,cap.width()-1})for(int z:new int[]{0,cap.depth()-1}){
                    var offset=StructureTemplate.transform(new BlockPos(x,0,z),Mirror.NONE,rotation,BlockPos.ZERO);
                    minimumX=Math.min(minimumX,offset.getX());
                    minimumZ=Math.min(minimumZ,offset.getZ());
                }
                var origin=new BlockPos(footprint.minX()-minimumX,allocation.y(),footprint.minZ()-minimumZ);
                var boundedArea=new WorldPrepSavedData.Area(parent.id()+"/structure/"+allocation.instanceId().substring(0,16),
                    parent.dimension(),footprint.minX(),footprint.minZ(),footprint.maxX(),footprint.maxZ());
                var plan=NativeStructurePass.planCatalogTemplate(level,boundedArea,cap,
                    origin,rotation,Mirror.NONE,input.frozenConfigSha256());
                site=new PhysicalStore(root.resolve("placement-"+cursor));
                NativePhysicalRuntime.claim(level,"structure",boundedArea,plan.operation());
                pass=new NativeStructurePass(plan,site);
                return false;
            }
            if(!pass.done()){pass.step(level,32);return false;}
            if(!saved){
                if(!fr.proutlost.worldprep.runtime.BlockMutationRuntime.checkpointReady(
                        level,WorldPrepRuntime.data(level)))return false;
                level.save(null,true,false);saved=true;return false;
            }
            if(audit==null)audit=new NativeStructureAudit(pass.plan(),site);
            if(!audit.step(level))return false;
            var location=NativeVerifiedStructureCatalog.publish(site,
                input.directorTagsByStructure().get(allocation.structureId()));
            journal.write("verified-"+cursor,Map.of("operation",pass.plan().operation().toString(),
                "locationId",location.locationId().toString(),
                "status","PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED"));
            window.close();window=null;pass=null;audit=null;site=null;saved=false;cursor++;
            return false;
        }
        /** True mod registry generation: real pieces, existing FULL chunks, followed by disk audit. */
        boolean stepNative(ServerLevel level,StructureInstancePlan allocation,
                           StructureCapability cap)throws Exception {
            if(nativePass==null) {
                var mode=input.nativeConditionsByStructure().getOrDefault(allocation.structureId(),
                    NativeRegistryStructurePass.Conditions.NATIVE);
                nativePrepared=NativeRegistryStructurePass.prepare(level,allocation,cap,mode);
                var b=nativePrepared.actualBounds();
                if(b.minX()<parent.minX()||b.maxX()>parent.maxX()
                        ||b.minZ()<parent.minZ()||b.maxZ()>parent.maxZ())
                    throw new IOException("Native structure exceeds parent area");
                site=new PhysicalStore(root.resolve("placement-"+cursor));
                var bounded=new WorldPrepSavedData.Area(parent.id()+"/native/"
                    +allocation.instanceId().substring(0,16),parent.dimension(),
                    b.minX(),b.minZ(),b.maxX(),b.maxZ());
                NativePhysicalRuntime.claim(level,"structure",bounded,nativePrepared.operation());
                nativePass=new NativeRegistryStructurePass.Runner(nativePrepared,site);
                return false;
            }
            if(!nativePass.done()){nativePass.step(level);return false;}
            if(!saved) {
                if(input.standaloneArea()==null
                        &&!fr.proutlost.worldprep.runtime.BlockMutationRuntime.checkpointReady(
                        level,WorldPrepRuntime.data(level)))return false;
                level.getDataStorage().save();
                level.save(null,true,false);saved=true;return false;
            }
            if(nativeAudit==null)nativeAudit=new NativeRegistryStructureAudit(nativePrepared,site);
            if(!nativeAudit.step(level))return false;
            var location=NativeVerifiedStructureCatalog.publishNative(site,
                    input.directorTagsByStructure().get(allocation.structureId()));
            journal.write("verified-"+cursor,Map.of("operation",nativePrepared.operation().toString(),
                    "locationId",location.locationId().toString(),"registry",location.structureRegistryId(),
                    "status","PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED"));
            verifiedNative.add(location);
            org.slf4j.LoggerFactory.getLogger(WorldPrepStructureModule.class).info(
                "WORLDPrep_NATIVE_PLACED registry={} location={} bounds={} changedBlocks={} tags={}",
                location.structureRegistryId(),location.locationId(),nativePrepared.actualBounds(),
                nativePass.receipt().changedBlocks(),location.tags());
            nativePass.close();nativePass=null;nativePrepared=null;nativeAudit=null;
            site=null;saved=false;cursor++;
            return false;
        }
        void close(){
            if(window!=null){window.close();window=null;}
            if(nativePass!=null){nativePass.close();nativePass=null;}
            if(nativeAudit!=null){nativeAudit.close();nativeAudit=null;}
        }
    }
}
