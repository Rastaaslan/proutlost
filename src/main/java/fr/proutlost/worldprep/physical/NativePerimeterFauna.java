package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Whole-perimeter, sparse initial population. One candidate chunk/tick and at most one
 * group of one animal per <=64 chunks; habitat and retained disk budgets remain authoritative.
 * Skipped habitats/budgets are evidence, not spawn receipts. No dead entity is restored.
 */
final class NativePerimeterFauna implements QualificationOrchestrator.Task {
    record Evidence(long ordinal,String child,String disposition,String planSha,String diskSha) {}
    private final PerimeterExecution execution;
    NativePerimeterFauna(ServerLevel level,WorldPrepSavedData.Area parent,UUID batch,String configuration,
                        NativeFaunaPass.Rule rule,Path directory)throws IOException {
        var speciesId=net.minecraft.resources.ResourceLocation.parse(rule.species());
        if(!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(speciesId))throw new IOException("Missing fauna registry entry: "+speciesId);
        if(!Set.of(net.minecraft.world.entity.MobCategory.CREATURE,net.minecraft.world.entity.MobCategory.WATER_CREATURE,net.minecraft.world.entity.MobCategory.WATER_AMBIENT)
            .contains(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(speciesId).getCategory()))throw new IOException("Unsupported fauna registry category");
        var groups=new PerimeterGroups(batch,PhysicalStore.sha(configuration),parent.dimension(),parent.minX(),parent.minZ(),parent.maxX(),parent.maxZ());
        execution=new PerimeterExecution(groups,new PhysicalStore(directory.resolve("index")),group->{
            var area=new WorldPrepSavedData.Area(parent.id()+"/group/"+group.operation(),parent.dimension(),group.minX(),group.minZ(),group.maxX(),group.maxZ());
            var store=new PhysicalStore(directory.resolve("group-"+group.ordinal()));
            // First retain the old single center per chunk, then eight
            // exact in-chunk alternatives if those centers have no habitat.
            // One candidate/verified lease per tick; no missing chunks generated.
            var candidates=PerimeterFaunaCandidates.enumerate(area,level.getSeed(),group.operation());
            return new QualificationOrchestrator.Task() {
                final NativeChunkWindow window=new NativeChunkWindow(level);
                public void close(){window.close();}
                int cursor;NativeFaunaPass pass;NativePopulationAudit audit;boolean saved;
                Optional<String> skip(String disposition)throws IOException {
                    requireNoRetainedIntentForSkip(store);
                    store.write("evidence",new Evidence(group.ordinal(),group.operation().toString(),disposition,"",""));
                    return Optional.of(store.readText("evidence"));
                }
                public Optional<String> step()throws Exception {
                    NativePhysicalRuntime.requireThread(level);
                    if(store.exists("evidence"))return Optional.of(store.readText("evidence"));
                    if(pass==null) {
                        NativeFaunaPass.Plan plan;
                        boolean replayPlan=store.exists("plan");
                        if(replayPlan){plan=store.read("plan",NativeFaunaPass.Plan.class);if(!window.fauna(plan))return Optional.empty();}
                        else {
                            if(cursor==candidates.size())return skip("NO_ELIGIBLE_HABITAT");
                            var candidate=candidates.get(cursor);
                            int cx=candidate.getX()>>4,cz=candidate.getZ()>>4;
                            var local=new WorldPrepSavedData.Area(area.id(),area.dimension(),Math.max(area.minX(),cx<<4),Math.max(area.minZ(),cz<<4),Math.min(area.maxX(),(cx<<4)+15),Math.min(area.maxZ(),(cz<<4)+15));
                            if(!window.blocks(local.minX(),local.minZ(),local.maxX(),local.maxZ()))return Optional.empty();
                            cursor++;
                            // Existing FULL precondition is outside suitability rejection handling.
                            var chunk=ServerExistingChunkAccess.requireChunk(level,candidate.getX()>>4,candidate.getZ()>>4);
                            // Native LevelChunk.getHeight is the top blocking block Y,
                            // unlike the first-free Y encoded in on-disk heightmap NBT.
                            // Convert once before the V5 habitat/seabed calculation.
                            // Neither terrestrial nor aquatic placement can accept
                            // an occupied top surface as the animal's origin.
                            int firstAir=NativeTemplateSitePlanner.foundationY(
                                chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                    candidate.getX()&15,candidate.getZ()&15));
                            var habitatY=NativeHabitatOrigin.chooseY(
                                firstAir,level.getMinBuildHeight(),rule.waterDepth(),probeY->{
                                    ServerExistingChunkAccess.requireBlock(level,local,
                                        candidate.getX(),probeY,candidate.getZ());
                                    return level.getFluidState(new BlockPos(
                                        candidate.getX(),probeY,candidate.getZ())).is(
                                        net.minecraft.tags.FluidTags.WATER);
                                });
                            if(habitatY.isEmpty())return Optional.empty();
                            int y=habitatY.getAsInt();
                            // A heightmap can legitimately report first air at the
                            // bottom build layer. That site has no valid substrate
                            // block below it; reject the candidate, not the entire
                            // crash-safe species phase with OUTSIDE_BUILD_HEIGHT -65.
                            if(!NativeHabitatOrigin.verticalEnvelopeFits(y,
                                    level.getMinBuildHeight(),level.getMaxBuildHeight(),
                                    rule.waterDepth()))return Optional.empty();
                            try{plan=NativeFaunaPass.plan(level,local,rule,List.of(new BlockPos(candidate.getX(),y,candidate.getZ())),configuration+"/child/"+group.operation());}
                            catch(fr.proutlost.worldprep.world.ServerExistingChunkAccess.Refused unloaded){throw unloaded;}
                            catch(NativeFaunaPass.UnsuitableHabitat|NativeStructurePass.SiteConflict unsuitable){return Optional.empty();}
                        }
                        if(replayPlan) {
                            // Retained plan can only reopen its OLD immutable reservation.
                            // Budget rejection must not become a skip over prior intent.
                            NativePhysicalRuntime.requireRetainedPopulation(level,plan);
                        } else {
                            try{NativePhysicalRuntime.reservePopulation(level,plan);}
                            catch(NativePhysicalRuntime.PopulationBudgetRefusal exhausted){return skip("DURABLE_BUDGET_CONSUMED");}
                        }
                        NativePhysicalRuntime.claim(level,"fauna",area,plan.operation());pass=new NativeFaunaPass(plan,store);return Optional.empty();
                    }
                    if(!pass.done()){pass.step(level,1);return Optional.empty();}
                    if(!saved) {
                        if(!fr.proutlost.worldprep.runtime.BlockMutationRuntime.checkpointReady(level,fr.proutlost.worldprep.runtime.WorldPrepRuntime.data(level)))return Optional.empty();
                        level.save(null,true,false);saved=true;return Optional.empty();
                    }
                    if(audit==null)audit=new NativePopulationAudit(pass.plan(),store);
                    if(!audit.step(level))return Optional.empty();
                    store.write("evidence",new Evidence(group.ordinal(),group.operation().toString(),"POPULATION_SAVE_READBACK_VERIFIED",
                        PhysicalStore.sha(store.readText("plan")),PhysicalStore.sha(store.readText("population-disk-verified"))));
                    return Optional.of(store.readText("evidence"));
                }
            };
        },(group,text)->{
            var store=new PhysicalStore(directory.resolve("group-"+group.ordinal()));
            var e=new com.google.gson.Gson().fromJson(text,Evidence.class);
            if(e==null||e.ordinal()!=group.ordinal()||!group.operation().toString().equals(e.child())||!store.readText("evidence").equals(text))throw new IOException("Group fauna evidence identity mismatch");
            if(e.disposition().equals("POPULATION_SAVE_READBACK_VERIFIED")) {
                if(!PhysicalStore.sha(store.readText("plan")).equals(e.planSha())||!PhysicalStore.sha(store.readText("population-disk-verified")).equals(e.diskSha()))throw new IOException("Group fauna proof changed");
                var plan=store.read("plan",NativeFaunaPass.Plan.class);var receipt=store.read("population-disk-verified",NativePopulationAudit.Receipt.class);
                requireSpeciesLineage(rule,plan,group,configuration,parent.dimension());
                if(!receipt.operation().equals(plan.operation())||!receipt.planSha256().equals(e.planSha())||!receipt.status().equals(e.disposition()))throw new IOException("Group population disk receipt mismatch");
            } else if(!Set.of("NO_ELIGIBLE_HABITAT","DURABLE_BUDGET_CONSUMED").contains(e.disposition())
                ||store.exists("plan")||store.exists("population-issued")||store.exists("population-disk-verified"))
                throw new IOException("Invalid fauna exclusion evidence");
        });
    }
    /** True native species identity is required when a retained group is reopened.
     * A correct receipt hash is insufficient if it belongs to another species
     * or a different S2 child batch.
     */
    static void requireSpeciesLineage(NativeFaunaPass.Rule intended,
             NativeFaunaPass.Plan actual,PerimeterGroups.Group group,
             String frozenConfiguration,String dimension)throws IOException {
        String expected=frozenConfiguration+"/child/"+group.operation();
        if(!actual.rule().equals(intended)
            ||!actual.config().equals(expected)
            ||!actual.area().dimension().equals(dimension)
            ||actual.area().minX()<group.minX()||actual.area().maxX()>group.maxX()
            ||actual.area().minZ()<group.minZ()||actual.area().maxZ()>group.maxZ()
            ||actual.animals().stream().anyMatch(animal->
                animal.x()<group.minX()||animal.x()>group.maxX()
                ||animal.z()<group.minZ()||animal.z()>group.maxZ()))
            throw new IOException("Foreign species, group or configuration in retained fauna plan");
    }
    /** Never publish a no-habitat/budget skip over a retained physical population intent. */
    static void requireNoRetainedIntentForSkip(PhysicalStore store)throws IOException {
        if(store.exists("plan")||store.exists("population-issued")||store.exists("population-disk-verified"))
            throw new IOException("RETAINED_FAUNA_INTENT: resume/reconcile recorded plan; skip receipt refused");
    }
    public Optional<String> step()throws Exception{return execution.step();}
    public void close()throws IOException{execution.close();}
}
