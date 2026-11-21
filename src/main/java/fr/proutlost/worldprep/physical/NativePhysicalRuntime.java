package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Explicit, disabled-by-default candidate runner. One physical engine per dimension.
 * Pauses retain immutable intents. Restart requires explicit resume with the same UUID.
 * No world generation, ticket loading, deployment, automatic replacement or autosave calls.
 */
public final class NativePhysicalRuntime {
    private record Active(NativeStructurePass structure,NativeFaunaPass fauna,NativeStructureAudit structureAudit,NativePopulationAudit faunaAudit) {
        boolean done(){return structure!=null?structure.done():fauna!=null?fauna.done():structureAudit!=null?structureAudit.done():faunaAudit.done();}
        void step(ServerLevel level)throws IOException {if(structure!=null)structure.step(level,32);else if(fauna!=null)fauna.step(level,1);else if(structureAudit!=null)structureAudit.step(level);else faunaAudit.step(level);}
        UUID id(){return structure!=null?structure.plan().operation():fauna!=null?fauna.plan().operation():structureAudit!=null?structureAudit.plan().operation():faunaAudit.plan().operation();}
        WorldPrepSavedData.Area area(){return structure!=null?structure.plan().area():fauna!=null?fauna.plan().area():structureAudit!=null?structureAudit.plan().area():faunaAudit.plan().area();}
    }
    private static final Map<ServerLevel,Active> ACTIVE=new WeakHashMap<>();
    private static final Map<ServerLevel,String> STATUS=new WeakHashMap<>();
    public static boolean hasActive(ServerLevel level){return ACTIVE.containsKey(level);}
    public static void requireThread(ServerLevel level) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("Physical engine requires server thread");
    }
    public static Path root(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("worldprep-v2/physical")
            .resolve(PhysicalStore.sha(level.dimension().location().toString()));
    }
    public static PhysicalStore store(ServerLevel level,String kind,UUID id)throws IOException {
        if(!Set.of("structure","fauna").contains(kind))throw new IllegalArgumentException("Unknown engine");
        return new PhysicalStore(root(level).resolve(kind+"-"+id));
    }
    public static void requireNoPhysicalHistory(ServerLevel level) {
        requireThread(level);
        if(NativeQualificationRuntime.active(level)||WorldPrepFaunaModule.active(level)||Files.exists(root(level).resolve("physical-history.json")))
            throw new IllegalStateException("Physical history exists: replay of ecology/earlier passes forbidden");
    }
    public static void guard(ServerLevel level,WorldPrepSavedData.Area area) {
        requireThread(level);
        if(NativeStructureReconciliation.active(level))throw new IllegalStateException("Native reconciliation owns dimension");
        if(WorldPrepFaunaModule.active(level))throw new IllegalStateException("Standalone fauna module owns dimension");
        if(!WorldPrepConfig.ENABLED.get()||!WorldPrepConfig.PHYSICAL_PASSES.get()||WorldPrepConfig.MODE.get()!=WorldPrepConfig.Mode.TEST)
            throw new IllegalStateException("Physical passes disabled; isolated candidate TEST mode required");
        if(level.getServer().getPlayerCount()!=0)throw new IllegalStateException("Players present");
        if(level.getServer().getAverageTickTimeNanos()>WorldPrepConfig.MAX_TICK_MILLIS.get()*1_000_000L)throw new IllegalStateException("Tick pressure");
        CandidateGuard.requireCandidate(level);
        WorldPrepRuntime.assertUnlocked(WorldPrepRuntime.data(level),area);
        if(!area.equals(WorldPrepRuntime.requireArea(WorldPrepRuntime.data(level),area.id())))throw new IllegalStateException("Area changed since planning");
    }
    static void claim(ServerLevel level,String kind,WorldPrepSavedData.Area area,UUID id)throws IOException {
        var root=new PhysicalStore(root(level));
        root.write("physical-history",Map.of("dimension",level.dimension().location().toString(),"version",1));
        // One retained initial plan per area/type. New UUIDs cannot bypass density or duplicate claims.
        root.write(kind+"-claim-"+PhysicalStore.sha(area.id()),Map.of("operation",id.toString(),"area",area));
    }
    public record PopulationReservation(UUID operation,String dimension,String habitat,String species,java.util.List<NativeFaunaPass.Animal> animals) {
        public PopulationReservation {animals=java.util.List.copyOf(animals);if(animals.isEmpty()||animals.size()>64||dimension==null||habitat==null)throw new IllegalArgumentException("Bounded population receipt required");}
    }
    /** Retained JSON can be malformed even when its envelope checksum is valid.
     * Gson record deserialization must not be treated as a constructor/registry check.
     * Reject before using a retained slot in quota or idempotence calculations.
     */
    static void validateRetainedReservation(PopulationReservation reservation)throws IOException {
        if(reservation==null||reservation.operation()==null
            ||reservation.dimension()==null||reservation.species()==null
            ||reservation.habitat()==null||reservation.animals()==null
            ||reservation.animals().isEmpty()||reservation.animals().size()>64)
            throw new IOException("Malformed retained population reservation identity or size");
        var dimension=net.minecraft.resources.ResourceLocation.tryParse(reservation.dimension());
        var species=net.minecraft.resources.ResourceLocation.tryParse(reservation.species());
        if(dimension==null||!dimension.toString().equals(reservation.dimension())
            ||species==null||!species.toString().equals(reservation.species())
            ||!Set.of("OPEN_GRASSLAND","SHALLOW_MARINE","MARINE","DEEP_MARINE").contains(reservation.habitat()))
            throw new IOException("Invalid dimension, species or habitat in retained population reservation");
        var uuids=new HashSet<UUID>();
        for(var animal:reservation.animals()) {
            if(animal==null||animal.uuid()==null||Math.abs((long)animal.x())>30_000_000L
                ||Math.abs((long)animal.z())>30_000_000L||animal.y()<-2048||animal.y()>4096
                ||!uuids.add(animal.uuid()))
                throw new IOException("Malformed animal or duplicate UUID in retained reservation");
        }
    }
    static final class PopulationBudgetRefusal extends IOException {
        PopulationBudgetRefusal(String message){super(message);}
    }
    static void reservePopulation(ServerLevel level,NativeFaunaPass.Plan plan)throws IOException {
        populationReservation(level,plan,false);
    }
    /** Replay requires a PRE-EXISTING durable reservation. Never manufacture one
     * for a resumed plan: it would erase the distinction between a crash before
     * reservation and a completed population claim.
     */
    static void requireRetainedPopulation(ServerLevel level,NativeFaunaPass.Plan plan)throws IOException {
        populationReservation(level,plan,true);
    }
    private static void populationReservation(ServerLevel level,NativeFaunaPass.Plan plan,
                                              boolean replayOnly)throws IOException {
        requireThread(level);
        if(java.nio.file.Files.exists(root(level).resolve("population-budget")))
            throw new IOException("Legacy per-dimension budget requires audited migration; cannot reset history");
        var directory=level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .resolve("worldprep-v2/physical-population-budget");
        if(replayOnly&&!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Cannot resume: missing frozen population ledger directory");
        // Shared world ledger, not a per-dimension loaded-entity census. Death never releases budget.
        var ledger=new PhysicalStore(directory);
        reservePopulationLedger(ledger,plan,level.dimension().location().toString(),
            NativeBackupFaunaCensus.registryDigest(),replayOnly);
    }
    /** Pure-journal preflight on a caller-owned server actor. No ledger policy or slot
     * is published when the census or any density/identity check refuses the plan.
     * Passing the already observed registry fingerprint also makes refusal testable
     * without starting a Minecraft world or modifying its registry.
     */
    private static void requireMatchingPolicy(PhysicalStore ledger,String key,Object proposed)throws IOException {
        if(!ledger.exists(key))return;
        try {
            var existing=com.google.gson.JsonParser.parseString(ledger.readText(key));
            var expected=new com.google.gson.Gson().toJsonTree(proposed);
            if(!existing.equals(expected))
                throw new IOException("Frozen population policy conflict: "+key);
        } catch(com.google.gson.JsonParseException bad) {
            throw new IOException("Malformed population policy: "+key,bad);
        }
    }
    static void reservePopulationLedger(PhysicalStore ledger,NativeFaunaPass.Plan plan,
                                        String dimension,String registryDigest)throws IOException {
        reservePopulationLedger(ledger,plan,dimension,registryDigest,false);
    }
    /** True: verify exact existing slot; false: permit one new immutable slot. */
    static void reservePopulationLedger(PhysicalStore ledger,NativeFaunaPass.Plan plan,
                                        String dimension,String registryDigest,
                                        boolean replayOnly)throws IOException {
        Objects.requireNonNull(ledger);
        Objects.requireNonNull(plan);
        if(dimension==null||dimension.isBlank()||registryDigest==null||!registryDigest.matches("[0-9a-f]{64}"))
            throw new IOException("Explicit dimension and registry digest required");
        String habitat=plan.rule().waterDepth()==0?"OPEN_GRASSLAND":plan.rule().waterDepth()>20?"DEEP_MARINE":plan.rule().waterDepth()>6?"MARINE":"SHALLOW_MARINE";
        var policy=java.util.Map.of("globalLimit",plan.rule().globalLimit(),"habitatLimit",plan.rule().habitatLimit(),"regionLimit",plan.rule().regionLimit());
        String speciesPolicyKey="species-policy-"+PhysicalStore.sha(plan.rule().species());
        var speciesPolicy=java.util.Map.of("species",plan.rule().species(),"limit",plan.rule().speciesLimit());
        if(!ledger.exists("disk-baseline"))
            throw new IOException("Global quiescent disk fauna census required before initial population");
        var baseline=ledger.read("disk-baseline",NativeBackupFaunaCensus.Baseline.class);
        if(baseline.version()!=1||!"COMPLETE_QUIESCENT_BACKUP_CENSUS".equals(baseline.status())
            ||baseline.inventorySha()==null||!baseline.inventorySha().matches("[0-9a-f]{64}")
            ||baseline.registrySha()==null||!baseline.registrySha().matches("[0-9a-f]{64}"))throw new IOException("Invalid disk census baseline");
        if(!baseline.registrySha().equals(registryDigest))throw new IOException("Entity registry changed since disk census");
        if(baseline.counts()==null)throw new IOException("Missing frozen disk census counts");
        requireMatchingPolicy(ledger,"world-policy",policy);
        requireMatchingPolicy(ledger,speciesPolicyKey,speciesPolicy);
        // Unknown habitat membership consumes every habitat budget conservatively. Deaths never
        // release this frozen baseline; retained initial reservations remain additional consumption.
        int total=baseline.counts().total(),species=baseline.counts().species().getOrDefault(plan.rule().species(),0),habitatCount=total,slot=0;
        var regions=new java.util.HashMap<String,Integer>(baseline.counts().regions());
        var chunkCounts=new java.util.HashMap<String,Integer>(baseline.counts().chunks());
        var plannedAnimalIds=new HashSet<UUID>();
        for(var animal:plan.animals())
            if(animal==null||animal.uuid()==null||!plannedAnimalIds.add(animal.uuid()))
                throw new IOException("Missing or duplicate animal UUID in frozen population plan");
        for(var animal:plan.animals())if(chunkCounts.merge(dimension+":"+(animal.x()>>4)+":"+(animal.z()>>4),1,Integer::sum)>plan.rule().chunkLimit())
            throw new PopulationBudgetRefusal("Existing disk fauna consumes chunk density budget");
        int spacingChunks=(plan.rule().separation()+15)/16;
        for(var animal:plan.animals())for(int dx=-spacingChunks;dx<=spacingChunks;dx++)for(int dz=-spacingChunks;dz<=spacingChunks;dz++)
            if(baseline.counts().chunks().containsKey(dimension+":"+((animal.x()>>4)+dx)+":"+((animal.z()>>4)+dz)))
                throw new PopulationBudgetRefusal("Existing disk fauna conservatively reserves nearby chunks");
        // Scan the full finite slot namespace. A missing earlier slot must
        // NEVER mask a later reservation after a crash, manual corruption, or
        // partially copied journal; otherwise budgets could be reset.
        boolean matchingOperation=false;
        int firstGap=-1;
        var retainedAnimalIds=new HashSet<UUID>();
        for(int current=0;current<256;current++) {
            if(!ledger.exists("slot-"+current)) {
                if(firstGap<0)firstGap=current;
                continue;
            }
            if(firstGap>=0)
                throw new IOException("Non-contiguous population ledger: retained reservation after gap "+firstGap);
            var reservation=ledger.read("slot-"+current,PopulationReservation.class);
            validateRetainedReservation(reservation);
            if(reservation.operation().equals(plan.operation())) {
                if(matchingOperation)throw new IOException("Duplicated retained population operation");
                if(!reservation.dimension().equals(dimension)||!reservation.habitat().equals(habitat)
                    ||!reservation.species().equals(plan.rule().species())||!reservation.animals().equals(plan.animals()))
                    throw new IOException("Population reservation identity conflict");
                if(!ledger.exists("world-policy")||!ledger.exists(speciesPolicyKey))
                    throw new IOException("Retained reservation missing frozen budget policy");
                matchingOperation=true;
            }
            total+=reservation.animals().size();
            if(total>256)throw new PopulationBudgetRefusal("Retained population exceeds finite census limit");
            if(reservation.species().equals(plan.rule().species()))species+=reservation.animals().size();
            if(reservation.habitat().equals(habitat))habitatCount+=reservation.animals().size();
            for(var prior:reservation.animals()) {
                if(!retainedAnimalIds.add(prior.uuid()))
                    throw new IOException("Duplicate animal UUID between retained population slots");
                regions.merge(reservation.dimension()+":"+(prior.x()>>9)+":"+(prior.z()>>9),1,Integer::sum);
                if(!reservation.operation().equals(plan.operation()))
                    for(var animal:plan.animals()) {
                        if(prior.uuid().equals(animal.uuid()))
                            throw new IOException("Animal UUID reused by a foreign population operation");
                        if(reservation.dimension().equals(dimension)
                            &&((prior.x()>>4)==(animal.x()>>4)&&(prior.z()>>4)==(animal.z()>>4)
                            ||Math.pow((double)prior.x()-animal.x(),2)+Math.pow((double)prior.z()-animal.z(),2)
                                <(long)plan.rule().separation()*plan.rule().separation()))
                            throw new PopulationBudgetRefusal("Chunk or spacing already has a retained population reservation");
                    }
            }
        }
        if(matchingOperation)return; // checked every retained slot, never re-issue an existing operation
        if(replayOnly)
            throw new IOException("MISSING_RETAINED_POPULATION_RESERVATION: resume must never allocate a slot");
        slot=firstGap<0?256:firstGap;
        for(var animal:plan.animals())if(regions.merge(dimension+":"+(animal.x()>>9)+":"+(animal.z()>>9),1,Integer::sum)>plan.rule().regionLimit())throw new PopulationBudgetRefusal("Retained region population budget exhausted");
        if(slot==256||total+plan.animals().size()>plan.rule().globalLimit()||species+plan.animals().size()>plan.rule().speciesLimit()||habitatCount+plan.animals().size()>plan.rule().habitatLimit())
            throw new PopulationBudgetRefusal("Retained initial population budget exhausted; deaths never reset it");
        // All refusals precede any policy publication. Immutable policies must
        // be synced before the slot so recovery cannot see an unbound reservation.
        ledger.write("world-policy",policy);
        ledger.write(speciesPolicyKey,speciesPolicy);
        ledger.write("slot-"+slot,new PopulationReservation(plan.operation(),dimension,habitat,plan.rule().species(),plan.animals()));
    }
    public static UUID structures(ServerLevel level,String areaId,String template,BlockPos origin,Rotation rotation,Mirror mirror)throws IOException {
        var area=WorldPrepRuntime.requireArea(WorldPrepRuntime.data(level),areaId);guard(level,area);
        if(ACTIVE.containsKey(level)||NativeQualificationRuntime.active(level))throw new IllegalStateException("Physical engine already active");
        String config="exact-static-template-v1;air-only;maxCells=4096;spawnReserve=16;maxWrites=32";
        var plan=NativeStructurePass.plan(level,area,template,origin,rotation,mirror,config);
        claim(level,"structure",area,plan.operation());
        var engine=new NativeStructurePass(plan,store(level,"structure",plan.operation()));
        ACTIVE.put(level,new Active(engine,null,null,null));STATUS.put(level,"RUNNING "+plan.operation());return plan.operation();
    }
    public static UUID fauna(ServerLevel level,String areaId,NativeFaunaPass.Rule rule,List<BlockPos> positions)throws IOException {
        var area=WorldPrepRuntime.requireArea(WorldPrepRuntime.data(level),areaId);guard(level,area);
        if(ACTIVE.containsKey(level)||NativeQualificationRuntime.active(level))throw new IllegalStateException("Physical engine already active");
        String config=new com.google.gson.Gson().toJson(rule);
        var plan=NativeFaunaPass.plan(level,area,rule,positions,config);
        reservePopulation(level,plan);
        claim(level,"fauna",area,plan.operation());
        var engine=new NativeFaunaPass(plan,store(level,"fauna",plan.operation()));
        ACTIVE.put(level,new Active(null,engine,null,null));STATUS.put(level,"RUNNING "+plan.operation());return plan.operation();
    }
    public static void resume(ServerLevel level,String kind,UUID id,boolean rollback)throws IOException {
        requireThread(level);if(ACTIVE.containsKey(level)||NativeQualificationRuntime.active(level))throw new IllegalStateException("Physical engine active");
        var store=store(level,kind,id);
        Active active;
        if(kind.equals("structure")) {
            var plan=store.read("plan",NativeStructurePass.Plan.class);guard(level,plan.area());
            if(!plan.operation().equals(id)||!plan.dimension().equals(level.dimension().location().toString())
                ||!plan.adapter().equals(NativeStructurePass.ADAPTER)||!NativeStructurePass.TEMPLATES.contains(plan.template()))throw new IllegalStateException("Plan identity/adapter mismatch");
            var engine=new NativeStructurePass(plan,store);if(rollback)engine.rollback();active=new Active(engine,null,null,null);
        } else {
            if(rollback)throw new IllegalStateException("Fauna removal is not a reversible block rollback");
            var plan=store.read("plan",NativeFaunaPass.Plan.class);guard(level,plan.area());
            if(!plan.operation().equals(id))throw new IllegalStateException("Population identity mismatch");
            // Replay is not a new population admission. The complete frozen
            // world ledger must already contain the exact operation.
            requireRetainedPopulation(level,plan);
            active=new Active(null,new NativeFaunaPass(plan,store),null,null);
        }
        claim(level,kind,active.area(),id);ACTIVE.put(level,active);STATUS.put(level,"RUNNING "+id);
    }
    public static void audit(ServerLevel level,String kind,UUID id)throws IOException {
        requireThread(level);if(ACTIVE.containsKey(level)||NativeQualificationRuntime.active(level))throw new IllegalStateException("Physical engine active");
        var store=store(level,kind,id);Active active;
        if(kind.equals("structure")) {
            var plan=store.read("plan",NativeStructurePass.Plan.class);guard(level,plan.area());
            if(!plan.operation().equals(id))throw new IllegalArgumentException("Audit identity mismatch");
            active=new Active(null,null,new NativeStructureAudit(plan,store),null);
        } else {
            var plan=store.read("plan",NativeFaunaPass.Plan.class);guard(level,plan.area());
            if(!plan.operation().equals(id))throw new IllegalArgumentException("Audit identity mismatch");
            active=new Active(null,null,null,new NativePopulationAudit(plan,store));
        }
        ACTIVE.put(level,active);STATUS.put(level,"AUDITING "+id);
    }
    public static void pause(ServerLevel level) {requireThread(level);var active=ACTIVE.remove(level);STATUS.put(level,"PAUSED "+(active==null?"none":active.id()));}
    public static String status(ServerLevel level){return STATUS.getOrDefault(level,"IDLE; persisted operations require explicit UUID resume");}
    public static void tick(ServerTickEvent.Post event) {
        for(var level:event.getServer().getAllLevels())tickLevel(level);
    }
    public static void tickLevel(ServerLevel level) {
        requireThread(level);var active=ACTIVE.get(level);if(active==null)return;
        try {
            guard(level,active.area());active.step(level);
            if(active.done()) {ACTIVE.remove(level);STATUS.put(level,(active.structureAudit()!=null||active.faunaAudit()!=null?"DISK_READBACK_VERIFIED ":"MEMORY_COMPLETE_NOT_DISK_VERIFIED ")+active.id());}
        } catch(Exception failure) {
            ACTIVE.remove(level);STATUS.put(level,"PAUSED "+active.id()+": "+failure.getMessage());
            org.slf4j.LoggerFactory.getLogger(NativePhysicalRuntime.class).warn("Physical pass paused {}",active.id(),failure);
        }
    }
    private NativePhysicalRuntime(){}
}
