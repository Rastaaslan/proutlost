package fr.proutlost.worldprep.physical;

import com.google.gson.Gson;
import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.runtime.CandidateGuard;
import fr.proutlost.worldprep.runtime.ExistingChunkLoader;
import fr.proutlost.worldprep.world.ExistingChunkLease;
import fr.proutlost.worldprep.world.ExistingRegionEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Explicit, independently callable fauna population. No ecology or block pass
 * is invoked. Existing FULL leases, native predicates, bounded additions, forced
 * per-UUID intent and independent persisted-entity audit are separate gates. */
public final class WorldPrepFaunaModule {
    public record Position(int x,int y,int z) {
        public Position {
            if(Math.abs((long)x)>29999900||Math.abs((long)z)>29999900||Math.abs((long)y)>4096)
                throw new IllegalArgumentException("FAUNA_POSITION_OUT_OF_BOUNDS");
        }
        public BlockPos block(){return new BlockPos(x,y,z);}
    }
    /** Inclusive exclusion; applies to the entire initial animal AABB. */
    public record Exclusion(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        public Exclusion {
            new Position(minX,minY,minZ);new Position(maxX,maxY,maxZ);
            if(minX>maxX||minY>maxY||minZ>maxZ)throw new IllegalArgumentException("FAUNA_INVALID_EXCLUSION");
        }
    }
    public record Budgets(int maxNewAnimals,int maxNewPerSpecies,int maxNewPerRegion,
                          int maxNewPerSpeciesPerRegion,int maxNewPerChunk,int spawnPerTick) {
        public Budgets {
            if(maxNewAnimals<1||maxNewAnimals>8192||maxNewPerSpecies<1||maxNewPerSpecies>maxNewAnimals
                ||maxNewPerRegion<1||maxNewPerRegion>maxNewAnimals
                ||maxNewPerSpeciesPerRegion<1||maxNewPerSpeciesPerRegion>maxNewPerRegion
                ||maxNewPerChunk<1||maxNewPerChunk>64||maxNewPerChunk>maxNewPerRegion
                ||spawnPerTick<1||spawnPerTick>4)
                throw new IllegalArgumentException("FAUNA_INVALID_BUDGETS");
        }
    }
    public record Group(String id,String species,String expectedBiome,Position center,
                        int targetCount,List<Position> candidates) {
        public Group {
            if(id==null||!id.matches("[a-zA-Z0-9_-]{1,96}")||species==null
                ||ResourceLocation.tryParse(species)==null||expectedBiome==null
                ||ResourceLocation.tryParse(expectedBiome)==null||center==null
                ||targetCount<1||targetCount>64||candidates==null||candidates.isEmpty()||candidates.size()>64)
                throw new IllegalArgumentException("FAUNA_INVALID_GROUP");
            candidates=List.copyOf(candidates);
            if(new HashSet<>(candidates).size()!=candidates.size())
                throw new IllegalArgumentException("FAUNA_DUPLICATE_CANDIDATE");
        }
    }
    public record Request(int schema,UUID batch,String dimension,long worldSeed,String frozenConfigSha256,
                          Budgets budgets,List<NativeFaunaHabitatPolicy.Profile> profiles,
                          List<Group> groups,List<Exclusion> exclusions) {
        public Request {
            if(schema!=1||batch==null||dimension==null||ResourceLocation.tryParse(dimension)==null
                ||frozenConfigSha256==null||!frozenConfigSha256.matches("[0-9a-f]{64}")
                ||budgets==null||profiles==null||profiles.isEmpty()||profiles.size()>128
                ||groups==null||groups.isEmpty()||groups.size()>4096||exclusions==null||exclusions.size()>512)
                throw new IllegalArgumentException("FAUNA_INVALID_REQUEST");
            profiles=List.copyOf(profiles);groups=List.copyOf(groups);exclusions=List.copyOf(exclusions);
            Map<String,NativeFaunaHabitatPolicy.Profile> types=new HashMap<>();
            for(var p:profiles) {
                if(p.localRadius()>16||!Set.of("minecraft","reanimal").contains(ResourceLocation.parse(p.species()).getNamespace())
                    ||types.putIfAbsent(p.species(),p)!=null)
                    throw new IllegalArgumentException("FAUNA_PROFILE_DUPLICATE_OR_UNSUPPORTED_SCOPE");
            }
            Set<String> ids=new HashSet<>();int points=0,targets=0;
            for(var g:groups) {
                var p=types.get(g.species());
                if(!ids.add(g.id())||p==null||g.targetCount()<p.minGroup()||g.targetCount()>p.maxGroup())
                    throw new IllegalArgumentException("FAUNA_GROUP_PROFILE_OR_TARGET_MISMATCH");
                for(var c:g.candidates())
                    if(Math.abs((long)c.x()-g.center().x())>p.localRadius()
                        ||Math.abs((long)c.z()-g.center().z())>p.localRadius())
                        throw new IllegalArgumentException("FAUNA_CANDIDATE_OUTSIDE_LOCAL_CENSUS");
                points+=g.candidates().size();targets+=g.targetCount();
            }
            if(points>32768||targets>8192)throw new IllegalArgumentException("FAUNA_REQUEST_FINITE_LIMIT");
            for(int i=0;i<groups.size();i++)for(int j=0;j<i;j++) {
                var a=groups.get(i);var b=groups.get(j);
                if(!a.species().equals(b.species()))continue;
                int r=types.get(a.species()).localRadius()+types.get(b.species()).localRadius();
                if(Math.abs((long)a.center().x()-b.center().x())<=r
                    &&Math.abs((long)a.center().z()-b.center().z())<=r)
                    throw new IllegalArgumentException("FAUNA_OVERLAPPING_SAME_SPECIES_CENSUS_ZONES");
            }
        }
    }

    private static final Gson JSON=new Gson();
    private static final int MAX_REQUEST_BYTES=1024*1024;
    private static final int LEASE_STEPS_PER_TICK=16;
    private static final long LEASE_NANOS_PER_TICK=4_000_000L;
    private static final Map<ServerLevel,Session> ACTIVE=new WeakHashMap<>();
    private static final Map<ServerLevel,String> STATUS=new WeakHashMap<>();
    private WorldPrepFaunaModule() {}

    public static NativeFaunaHabitatPolicy.Profile profile(Request r,String species) {
        return r.profiles().stream().filter(p->p.species().equals(species)).findFirst().orElseThrow();
    }
    private static Path world(ServerLevel level){return level.getServer().getWorldPath(LevelResource.ROOT);}
    private static Path root(ServerLevel level,UUID batch){return world(level).resolve("worldprep-v2/fauna-module/"+batch);}
    private static Request request(ServerLevel level)throws IOException {
        Path file=world(level).resolve("worldprep-fauna-module.json");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file)
            ||Files.size(file)>MAX_REQUEST_BYTES)throw new IOException("Missing bounded worldprep-fauna-module.json");
        try {
            Request r=JSON.fromJson(Files.readString(file),Request.class);
            if(r==null)throw new IOException("Empty fauna request");
            return r;
        } catch(RuntimeException failure){throw new IOException("Malformed fauna request",failure);}
    }
    private static void guard(ServerLevel level,Request r) {
        NativePhysicalRuntime.requireThread(level);CandidateGuard.requireCandidate(level);
        if(!WorldPrepConfig.STRUCTURES_ONLY.get()||WorldPrepConfig.ENABLED.get()
            ||!WorldPrepConfig.PHYSICAL_PASSES.get()||WorldPrepConfig.MODE.get()!=WorldPrepConfig.Mode.TEST)
            throw new IllegalStateException("Fauna module requires structuresOnly=true, enabled=false, physicalPasses=true, TEST");
        if(!r.dimension().equals(level.dimension().location().toString())||r.worldSeed()!=level.getSeed())
            throw new IllegalStateException("FAUNA_WORLD_OR_DIMENSION_IDENTITY_MISMATCH");
        if(level.getServer().getPlayerCount()!=0)throw new IllegalStateException("FAUNA_PLAYERS_PRESENT");
    }
    private static void requireIdle(ServerLevel level)throws IOException {
        NativePhysicalRuntime.requireThread(level);
        if(active(level)||NativePhysicalRuntime.hasActive(level)||NativeQualificationRuntime.active(level)
            ||WorldPrepStructureModule.status(level).startsWith("RUNNING")
            ||WorldPrepFeatureModule.active(level)||NativeStructureReconciliation.active(level))
            throw new IOException("Another physical owner is active");
    }
    private record WindowBounds(int minCX,int minCZ,int maxCX,int maxCZ) {}
    private static WindowBounds bounds(Request r,Group group) {
        var profile=profile(r,group.species());var type=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(group.species()));
        int pad=(int)Math.ceil(type.getWidth()/2.0)+9,radius=profile.localRadius();
        int minX=group.center().x()-radius,maxX=group.center().x()+radius;
        int minZ=group.center().z()-radius,maxZ=group.center().z()+radius;
        for(var p:group.candidates()) {
            minX=Math.min(minX,p.x()-pad);maxX=Math.max(maxX,p.x()+pad);
            minZ=Math.min(minZ,p.z()-pad);maxZ=Math.max(maxZ,p.z()+pad);
        }
        var b=new WindowBounds(minX>>4,minZ>>4,maxX>>4,maxZ>>4);
        if(b.maxCX()-b.minCX()>6||b.maxCZ()-b.minCZ()>6)
            throw new IllegalArgumentException("FAUNA_LEASE_ENVELOPE_EXCEEDS_49_CENTERS");
        return b;
    }
    private static void validateExisting(ServerLevel level,Request r)throws IOException {
        for(var p:r.profiles())NativeFaunaHabitatPolicy.validateRegistry(level,p);
        Path region=DimensionType.getStorageFolder(level.dimension(),world(level)).resolve("region");
        Set<Long> chunks=new HashSet<>();
        for(var g:r.groups()) {
            for(var p:g.candidates())
                if(p.y()<=level.getMinBuildHeight()||p.y()>=level.getMaxBuildHeight())
                    throw new IOException("FAUNA_CANDIDATE_OUTSIDE_BUILD_HEIGHT");
            var b=bounds(r,g);
            for(int z=b.minCZ()-1;z<=b.maxCZ()+1;z++)for(int x=b.minCX()-1;x<=b.maxCX()+1;x++)
                chunks.add(ChunkPos.asLong(x,z));
        }
        if(chunks.size()>131072)throw new IOException("FAUNA_PREFLIGHT_CHUNK_LIMIT");
        for(long p:chunks)ExistingRegionEntry.requirePresent(region,ChunkPos.getX(p),ChunkPos.getZ(p));
    }
    /** No leases, terrain reads, additions or journal writes. */
    public static String preview(ServerLevel level)throws IOException {
        Request r=request(level);guard(level,r);validateExisting(level,r);
        return "FAUNA_REQUEST_REGISTRY_AND_DISK_ENVELOPES_VALIDATED_NOT_TERRAIN_VALIDATED_NOT_POPULATED"
            +" batch="+r.batch()+" groups="+r.groups().size()+" targetIncludingExisting="
            +r.groups().stream().mapToInt(Group::targetCount).sum()+" maxNew="+r.budgets().maxNewAnimals()
            +" spawnPerTick="+r.budgets().spawnPerTick()+" leaseStepsPerTick="+LEASE_STEPS_PER_TICK;
    }
    public static UUID start(ServerLevel level)throws IOException {
        requireIdle(level);Request r=request(level);guard(level,r);validateExisting(level,r);
        Path path=root(level,r.batch());
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("FAUNA_BATCH_EXISTS_USE_EXPLICIT_RESUME_OR_AUDIT");
        PhysicalStore store=new PhysicalStore(path);String sha=store.write("request",r);
        Session s=new Session(r,path,store,sha,false);ACTIVE.put(level,s);STATUS.put(level,s.status());return r.batch();
    }
    public static UUID resume(ServerLevel level)throws IOException{return resume(level,request(level).batch());}
    public static UUID resume(ServerLevel level,UUID batch)throws IOException{return reopen(level,batch,false);}
    public static UUID audit(ServerLevel level)throws IOException{return audit(level,request(level).batch());}
    public static UUID audit(ServerLevel level,UUID batch)throws IOException{return reopen(level,batch,true);}
    private static UUID reopen(ServerLevel level,UUID batch,boolean auditOnly)throws IOException {
        requireIdle(level);Path path=root(level,Objects.requireNonNull(batch));
        if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("FAUNA_BATCH_NOT_FOUND");
        PhysicalStore store=new PhysicalStore(path);Request r=store.read("request",Request.class);
        if(!r.batch().equals(batch))throw new IOException("FAUNA_BATCH_PATH_MISMATCH");
        guard(level,r);if(!auditOnly)validateExisting(level,r);
        Session s=new Session(r,path,store,PhysicalStore.sha(store.readText("request")),auditOnly);
        ACTIVE.put(level,s);STATUS.put(level,s.status());return batch;
    }
    public static boolean active(ServerLevel level){return ACTIVE.containsKey(level);}
    public static String status(ServerLevel level) {
        if(ACTIVE.containsKey(level))return ACTIVE.get(level).status();
        if(STATUS.containsKey(level))return STATUS.get(level);
        try {
            var r=request(level);Path path=root(level,r.batch());
            if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) {
                PhysicalStore store=new PhysicalStore(path);
                return (store.exists("complete")?"RECORDED_COMPLETE_REQUIRES_FRESH_AUDIT":"RETAINED_BATCH_EXPLICIT_RESUME_OR_AUDIT")
                    +" batch="+r.batch()+"; no automatic work";
            }
        } catch(Exception unavailable){return "FAUNA_IDLE_NO_VALID_REQUEST";}
        return "FAUNA_DISABLED_NOT_STARTED";
    }
    public static void pause(ServerLevel level) {
        NativePhysicalRuntime.requireThread(level);Session s=ACTIVE.remove(level);
        if(s!=null) {
            Exception stopped=null;
            try{s.flushOnStop(level);}catch(Exception failure){stopped=failure;}
            try{s.recordStop("PAUSED",stopped==null?"Explicit pause or level unload":"Pause; native save flush failed: "+stopped);}
            catch(IOException failure){if(stopped==null)stopped=failure;else stopped.addSuppressed(failure);}
            try{s.close();}catch(RuntimeException failure){if(stopped==null)stopped=failure;else stopped.addSuppressed(failure);}
            if(stopped!=null)org.slf4j.LoggerFactory.getLogger(WorldPrepFaunaModule.class).warn("Fauna pause requires audit",stopped);
            STATUS.put(level,"FAUNA_PAUSED_EXPLICIT_RESUME_OR_AUDIT "+s.request.batch());
        }
    }
    public static void tick(ServerTickEvent.Post event) {
        for(var level:event.getServer().getAllLevels()) {
            Session s=ACTIVE.get(level);if(s==null)continue;
            try {
                guard(level,s.request);
                if(s.step(level)) {ACTIVE.remove(level);s.close();STATUS.put(level,s.outcome);}
            } catch(Exception failure) {
                ACTIVE.remove(level);
                try{s.flushOnStop(level);}catch(Exception saveFailure){failure.addSuppressed(saveFailure);}
                try{s.recordStop("FAILED_CLOSED",String.valueOf(failure.getMessage()));}
                catch(IOException evidenceFailure){failure.addSuppressed(evidenceFailure);}
                try{s.close();}catch(RuntimeException cleanupFailure){failure.addSuppressed(cleanupFailure);}
                STATUS.put(level,"FAUNA_FAILED_CLOSED_EXPLICIT_RESUME_OR_AUDIT batch="+s.request.batch()
                    +" phase="+s.phase+" reason="+failure.getMessage());
                org.slf4j.LoggerFactory.getLogger(WorldPrepFaunaModule.class).error(
                    "Standalone fauna module stopped; no automatic recreation",failure);
            }
        }
    }
    public static void unload(LevelEvent.Unload event){if(event.getLevel() instanceof ServerLevel level)pause(level);}

    /** One invocation represents one server tick. A pending lease is polled at
     * most once in that tick; ready leases are retained without further polling. */
    static final class LeaseStepScheduler {
        private final boolean[] ready;
        private int next,completed;
        LeaseStepScheduler(int count) {
            if(count<1||count>49)throw new IllegalArgumentException("FAUNA_INVALID_LEASE_COUNT");
            ready=new boolean[count];
        }
        boolean advance(int maxSteps,long maxNanos,java.util.function.LongSupplier clock,
                        java.util.function.IntPredicate step) {
            if(maxSteps<1||maxNanos<1)throw new IllegalArgumentException("FAUNA_INVALID_LEASE_TICK_BUDGET");
            long started=clock.getAsLong();
            int scanned=0,steps=0;
            while(completed<ready.length&&scanned<ready.length&&steps<maxSteps
                &&clock.getAsLong()-started<maxNanos) {
                int index=next;next=(next+1)%ready.length;scanned++;
                if(ready[index])continue;
                steps++;
                if(step.test(index)) {ready[index]=true;completed++;}
            }
            return completed==ready.length;
        }
    }

    /** One bounded owned window, all centers verified existing FULL before use.
     * Up to sixteen DISTINCT lease steps per tick, admitting another step only
     * inside four milliseconds; no call to ensure()/generation API. */
    private static final class GroupWindow implements AutoCloseable {
        final ServerLevel level;final List<ExistingChunkLease.Position> positions=new ArrayList<>();
        final List<ExistingChunkLease> leases;
        final LeaseStepScheduler scheduler;int entityWaits;
        GroupWindow(ServerLevel level,WindowBounds b) {
            this.level=level;
            for(int z=b.minCZ();z<=b.maxCZ();z++)for(int x=b.minCX();x<=b.maxCX();x++)
                positions.add(new ExistingChunkLease.Position(x,z));
            leases=new ArrayList<>(Collections.nCopies(positions.size(),null));
            scheduler=new LeaseStepScheduler(positions.size());
        }
        boolean step() {
            boolean ready=scheduler.advance(LEASE_STEPS_PER_TICK,LEASE_NANOS_PER_TICK,System::nanoTime,index->{
                var p=positions.get(index);
                if(leases.get(index)==null)leases.set(index,ExistingChunkLoader.openVerifiedLease(level,p.x(),p.z()));
                return leases.get(index).step();
            });
            if(!ready)return false;
            for(var p:positions)
                if(!level.areEntitiesLoaded(ChunkPos.asLong(p.x(),p.z()))) {
                    if(++entityWaits>400)throw new IllegalStateException("FAUNA_ENTITY_SECTIONS_NOT_READY");
                    return false;
                }
            return true;
        }
        public void close() {
            RuntimeException problem=null;
            for(var lease:leases)if(lease!=null)try{lease.close();}catch(RuntimeException e){if(problem==null)problem=e;else problem.addSuppressed(e);}
            leases.clear();if(problem!=null)throw problem;
        }
    }

    static Map<String,Object> validateBudgets(Request request,Collection<NativeFaunaModulePopulation.Plan> plans)throws IOException {
        Map<String,Integer> species=new TreeMap<>(),regions=new TreeMap<>(),speciesRegions=new TreeMap<>(),chunks=new TreeMap<>();
        Set<UUID> unique=new HashSet<>();int total=0;
        for(var plan:plans)for(var animal:plan.animals()) {
            if(!unique.add(animal.uuid()))throw new IOException("FAUNA_DUPLICATE_PLANNED_UUID");
            var p=animal.position();String s=plan.profile().species();
            String region=Math.floorDiv(p.x(),512)+","+Math.floorDiv(p.z(),512);
            species.merge(s,1,Integer::sum);regions.merge(region,1,Integer::sum);
            speciesRegions.merge(s+"@"+region,1,Integer::sum);
            chunks.merge((p.x()>>4)+","+(p.z()>>4),1,Integer::sum);total++;
        }
        var b=request.budgets();
        if(total>b.maxNewAnimals()||exceeds(species,b.maxNewPerSpecies())||exceeds(regions,b.maxNewPerRegion())
            ||exceeds(speciesRegions,b.maxNewPerSpeciesPerRegion())||exceeds(chunks,b.maxNewPerChunk()))
            throw new IOException("FAUNA_PLANNED_GLOBAL_SPECIES_REGION_OR_CHUNK_BUDGET_EXCEEDED");
        return Map.of("newAnimals",total,"bySpecies",species,"byRegion512",regions,
            "bySpeciesAndRegion512",speciesRegions,"byChunk",chunks);
    }
    private static boolean exceeds(Map<String,Integer> counts,int limit){return counts.values().stream().anyMatch(n->n>limit);}

    private static final class Session {
        final Request request;final Path path;final PhysicalStore journal;final String requestSha;
        final boolean auditOnly;final UUID attempt=UUID.randomUUID();
        final Map<Integer,NativeFaunaModulePopulation.Plan> plans=new LinkedHashMap<>();
        final Map<Integer,String> rejected=new LinkedHashMap<>();
        int cursor,animalCursor,actualAdds;String phase="READING_RETAINED_PLANS",outcome;
        GroupWindow window;PhysicalStore groupJournal;
        NativeFaunaModulePopulation.Initialization initialized;
        NativeFaunaModuleAudit audit;
        List<NativeFaunaModuleAudit.Expected> expected=List.of();
        boolean prepared,saved,auditStarted,groupReadyToFlush,mayHaveAdded;
        Session(Request request,Path path,PhysicalStore journal,String requestSha,boolean auditOnly) {
            this.request=request;this.path=path;this.journal=journal;this.requestSha=requestSha;this.auditOnly=auditOnly;
        }
        String status(){return "RUNNING_FAUNA batch="+request.batch()+" attempt="+attempt+" auditOnly="+auditOnly
            +" phase="+phase+" groups="+cursor+"/"+request.groups().size()+" rejected="+rejected.size()+" newThisRun="+actualAdds;}
        PhysicalStore groupStore(int index)throws IOException {return new PhysicalStore(path.resolve("groups/group-"+index));}
        boolean hasPlan(int index){return Files.isRegularFile(path.resolve("groups/group-"+index+"/plan.json"),LinkOption.NOFOLLOW_LINKS);}
        boolean step(ServerLevel level)throws IOException {
            if(!prepared) {
                if(cursor<request.groups().size()) {
                    int index=cursor;var group=request.groups().get(index);
                    if(hasPlan(index)) {
                        var store=groupStore(index);var plan=store.read("plan",NativeFaunaModulePopulation.Plan.class);
                        NativeFaunaModulePopulation.validateRetained(plan,request,requestSha,index);
                        plans.put(index,plan);cursor++;return false;
                    }
                    if(auditOnly) {rejected.put(index,"NO_RETAINED_PLAN");cursor++;return false;}
                    phase="PREPARING_NATIVE_HABITAT_AND_EXISTING_POPULATION";
                    if(window==null)window=new GroupWindow(level,bounds(request,group));
                    if(!window.step())return false;
                    try {
                        var plan=NativeFaunaModulePopulation.prepare(level,request,requestSha,index,new ArrayList<>(plans.values()));
                        groupStore(index).write("plan",plan);plans.put(index,plan);
                    } catch(NativeFaunaModulePopulation.GroupNotReadyException notReady) {
                        rejected.put(index,notReady.getMessage());
                        journal.write("rejected-"+attempt+"-"+index,Map.of("group",group.id(),"index",index,
                            "status","NATIVE_GROUP_NOT_READY_NO_ANIMALS_EMITTED","reason",notReady.getMessage(),
                            "checkedAt",Instant.now().toString()));
                    }
                    window.close();window=null;cursor++;return false;
                }
                var budget=validateBudgets(request,plans.values());
                journal.write("prepared-"+attempt,Map.of("requestSha",requestSha,"readyGroups",plans.size(),
                    "requiredGroups",request.groups().size(),"rejected",rejected,"budget",budget,
                    "mode",auditOnly?"AUDIT_ONLY_NO_SPAWN":"POPULATION_PREPARED_BEFORE_NEW_ADDS"));
                prepared=true;cursor=0;phase=auditOnly?"BUILDING_PERSISTED_EXPECTATIONS":"ISSUING_NATIVE_GROUPS";
                return false;
            }
            if(!auditOnly&&cursor<request.groups().size()) {
                int index=cursor;var plan=plans.get(index);
                if(plan==null){cursor++;return false;}
                if(groupJournal==null)groupJournal=groupStore(index);
                if(groupReadyToFlush) {
                    phase="FLUSHING_COMPLETED_NATIVE_GROUP";
                    level.getDataStorage().save();level.save(null,true,false);
                    groupJournal.write("saved-"+attempt,Map.of("operation",plan.operation(),
                        "status","NATIVE_SAVE_FLUSHED_AWAITING_INDEPENDENT_ANVIL_AUDIT"));
                    nextGroup();return false;
                }
                if(NativeFaunaModulePopulation.allIssued(plan,groupJournal)) {nextGroup();return false;}
                if(window==null)window=new GroupWindow(level,bounds(request,plan.group()));
                if(!window.step())return false;
                if(initialized==null) {
                    phase="FINALIZING_NATIVE_GROUP_VARIANTS";
                    mayHaveAdded=true; // also covers an unexpected failure after a native add but before its receipt
                    initialized=NativeFaunaModulePopulation.initialize(level,plan,groupJournal);
                    phase="ISSUING_NATIVE_GROUPS";return false;
                }
                for(int work=0;work<request.budgets().spawnPerTick()&&animalCursor<plan.animals().size();work++,animalCursor++)
                    if(NativeFaunaModulePopulation.issue(level,request,plan,initialized,animalCursor,groupJournal))actualAdds++;
                if(animalCursor==plan.animals().size())groupReadyToFlush=true;
                return false;
            }
            if(!saved) {
                if(!auditOnly) {phase="FLUSHING_NATIVE_ENTITY_SAVE";level.getDataStorage().save();level.save(null,true,false);}
                saved=true;return false;
            }
            if(!auditStarted) {
                List<NativeFaunaModuleAudit.Expected> wanted=new ArrayList<>();
                for(var entry:plans.entrySet())
                    wanted.addAll(NativeFaunaModulePopulation.expected(entry.getValue(),groupStore(entry.getKey()),!auditOnly));
                expected=List.copyOf(wanted);
                Path entities=DimensionType.getStorageFolder(level.dimension(),world(level)).resolve("entities");
                audit=new NativeFaunaModuleAudit(entities,request.batch(),requestSha,expected);
                auditStarted=true;phase="VERIFYING_SAVED_UUIDS_ON_DISK_NO_CHUNK_LOADS";return false;
            }
            if(!audit.step())return false;
            publish(audit.result());return true;
        }
        void nextGroup() {
            if(window!=null){window.close();window=null;}
            groupJournal=null;initialized=null;animalCursor=0;groupReadyToFlush=false;cursor++;
            phase="ISSUING_NATIVE_GROUPS";
        }
        void publish(NativeFaunaModuleAudit.Result result)throws IOException {
            List<Map<String,String>> pieces=new ArrayList<>();
            for(int from=0;from<result.observations().size();from+=256) {
                String key="observed-"+attempt+"-"+(from/256);
                String sha=journal.write(key,result.observations().subList(from,Math.min(from+256,result.observations().size())));
                pieces.add(Map.of("receipt",key+".json","payloadSha256",sha));
            }
            // Publish disk evidence before reconciling an intent-only UUID as issued.
            String auditKey="audit-"+attempt;
            String auditSha=journal.write(auditKey,Map.ofEntries(
                Map.entry("batch",request.batch()),Map.entry("requestSha",requestSha),Map.entry("status",result.status()),
                Map.entry("auditOnly",auditOnly),Map.entry("observations",pieces),Map.entry("expectedCount",result.expectedCount()),
                Map.entry("scannedRegions",result.scannedRegions()),Map.entry("scannedChunks",result.scannedChunks()),
                Map.entry("emptyRegions",result.emptyRegions()),Map.entry("totalEntities",result.totalEntities()),
                Map.entry("startedAt",result.startedAt()),Map.entry("completedAt",result.completedAt())));
            Set<UUID> verified=new HashSet<>();for(var o:result.observations())verified.add(o.uuid());
            List<Map<String,String>> groupReceipts=new ArrayList<>();
            int verifiedGroups=0,owned=0,baseline=0;
            for(var entry:plans.entrySet()) {
                var plan=entry.getValue();var store=groupStore(entry.getKey());
                if(store.exists("native-finalized")) {
                    var init=store.read("native-finalized",NativeFaunaModulePopulation.Initialization.class);
                    NativeFaunaModulePopulation.validateInitialization(plan,init);
                    for(int i=0;i<plan.animals().size();i++) {
                        var a=plan.animals().get(i);var intent=NativeFaunaModulePopulation.intent(plan,a,init.animals().get(i));
                        var state=NativeFaunaModulePopulation.retained(store,intent);
                        if(state==NativeFaunaModulePopulation.Retained.INTENT_ONLY&&verified.contains(a.uuid())) {
                            store.write("reconciled-"+a.uuid(),Map.of("auditReceipt",auditKey+".json",
                                "auditPayloadSha256",auditSha,"uuid",a.uuid(),"rule","OWNED_UUID_VERIFIED_ON_DISK_NO_RESPAWN"));
                            store.write("issued-"+a.uuid(),intent);
                        }
                    }
                }
                var observations=result.observations().stream().filter(o->plan.group().id().equals(o.group())).toList();
                int newCount=(int)observations.stream().filter(NativeFaunaModuleAudit.Observation::owned).count();
                int existingCount=observations.size()-newCount;owned+=newCount;baseline+=existingCount;
                boolean groupComplete=NativeFaunaModulePopulation.allIssued(plan,store)
                    &&observations.size()>=plan.group().targetCount();
                if(groupComplete)verifiedGroups++;
                String key="qualified-"+attempt;
                String sha=store.write(key,Map.ofEntries(
                    Map.entry("group",plan.group().id()),Map.entry("species",plan.profile().species()),
                    Map.entry("biome",plan.group().expectedBiome()),Map.entry("medium",plan.profile().medium()),
                    Map.entry("tags",List.of("proutlost:fauna",
                        "proutlost:fauna/species/"+plan.profile().species().replace(':','/'),
                        "proutlost:fauna/biome/"+plan.group().expectedBiome().replace(':','/'),
                        "proutlost:fauna/medium/"+plan.profile().medium().toLowerCase(Locale.ROOT))),
                    Map.entry("center",plan.group().center()),Map.entry("localRadius",plan.profile().localRadius()),
                    Map.entry("targetIncludingExisting",plan.group().targetCount()),Map.entry("newOwned",newCount),
                    Map.entry("existingCounted",existingCount),Map.entry("observedAnimals",observations),
                    Map.entry("status",groupComplete?"SAVED_POPULATION_VERIFIED":"PARTIAL_SAVED_POPULATION_VERIFIED"),
                    Map.entry("auditReceipt","../../"+auditKey+".json"),Map.entry("auditPayloadSha256",auditSha),
                    Map.entry("directorImport","NOT_IMPLEMENTED_NO_ACK"),
                    Map.entry("placementPolicy","NATIVE_PHYSICAL_BIOME_AND_SPAWN_RULES;INITIAL_EXCLUSIONS_ONLY;FREE_ROAMING")));
                groupReceipts.add(Map.of("group",plan.group().id(),"receipt","groups/group-"+entry.getKey()+"/"+key+".json",
                    "payloadSha256",sha));
            }
            boolean complete=verifiedGroups==request.groups().size();
            String status=complete?"COMPLETE_FAUNA_SAVED_VERIFIED_NOT_DIRECTOR_IMPORTED":"PARTIAL_FAUNA_SAVED_VERIFIED_NOT_DIRECTOR_IMPORTED";
            Map<String,Object> summary=Map.ofEntries(Map.entry("batch",request.batch()),Map.entry("requestSha",requestSha),
                Map.entry("frozenConfigSha256",request.frozenConfigSha256()),Map.entry("status",status),
                Map.entry("requiredGroups",request.groups().size()),Map.entry("verifiedGroups",verifiedGroups),
                Map.entry("targetIncludingExisting",request.groups().stream().mapToInt(Group::targetCount).sum()),
                Map.entry("verifiedOwnedNew",owned),Map.entry("verifiedExisting",baseline),
                Map.entry("newThisRun",actualAdds),Map.entry("rejected",rejected),Map.entry("groups",groupReceipts),
                Map.entry("auditReceipt",auditKey+".json"),Map.entry("auditPayloadSha256",auditSha),
                Map.entry("verifiedAt",result.completedAt()),Map.entry("scope","PERSISTED_UUID_POPULATION_AT_AUDIT_TIME_NOT_FUTURE_DENSITY"));
            journal.write("result-"+attempt,summary);
            if(complete&&!journal.exists("complete"))journal.write("complete",summary);
            outcome=status+" batch="+request.batch()+" groups="+verifiedGroups+"/"+request.groups().size()
                +" savedNew="+owned+" savedExisting="+baseline+" rejected="+rejected.size()+" audit="+attempt;
        }
        void flushOnStop(ServerLevel level) {
            if(!auditOnly&&mayHaveAdded) {level.getDataStorage().save();level.save(null,true,false);}
        }
        void recordStop(String status,String reason)throws IOException {
            journal.write("stopped-"+attempt,Map.of("status",status,"phase",phase,"reason",reason,
                "batch",request.batch(),"requestSha",requestSha,"actualAddsThisRun",actualAdds,
                "groupCursor",cursor,"animalCursor",animalCursor,"at",Instant.now().toString()));
        }
        void close() {
            if(audit!=null)audit.close();
            if(window!=null){window.close();window=null;}
        }
    }
}
