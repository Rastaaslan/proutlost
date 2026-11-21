package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import net.minecraft.server.level.ServerLevel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Opt-in v2 parent structure sweep. No chunk tickets or generation. Ineligible groups retain
 * an explicit survey result; eligible groups retain their exact plan and disk audit separately.
 * This is structure evidence only, never a full-map or Director readiness receipt.
 */
final class NativePerimeterStructures implements QualificationOrchestrator.Task {
    record Evidence(long ordinal,String child,String disposition,String planSha,String diskSha) {}
    private final PerimeterExecution execution;
    NativePerimeterStructures(ServerLevel level,WorldPrepSavedData.Area parent,java.util.UUID batch,
                              String configuration,String template,Path directory)throws IOException {
        this(level,parent,batch,configuration,template,directory,null);
    }
    /** Dedicated opt-in structure module, independent from the nine-phase V5 coordinator.
     * The existing V5 constructor retains the exact historical behavior.
     */
    NativePerimeterStructures(ServerLevel level,WorldPrepSavedData.Area parent,java.util.UUID batch,
                              String configuration,String template,Path directory,
                              fr.proutlost.worldprep.structure.StructureCatalog catalog)throws IOException {
        var groups=new PerimeterGroups(batch,PhysicalStore.sha(configuration),parent.dimension(),parent.minX(),parent.minZ(),parent.maxX(),parent.maxZ());
        execution=new PerimeterExecution(groups,new PhysicalStore(directory.resolve("index")),group->{
            var area=new WorldPrepSavedData.Area(parent.id()+"/group/"+group.operation(),parent.dimension(),group.minX(),group.minZ(),group.maxX(),group.maxZ());
            var store=new PhysicalStore(directory.resolve("group-"+group.ordinal()));
            String childConfig=configuration+"/child/"+group.operation();
            return new QualificationOrchestrator.Task() {
                final NativeChunkWindow window=new NativeChunkWindow(level);
                public void close(){try{if(survey!=null)survey.close();}finally{window.close();}}
                NativeTemplateSitePlanner survey;
                NativeStructurePass pass;
                NativeStructureAudit audit;NativeStructurePass.Plan selected;
                boolean saved;
                public Optional<String> step()throws Exception {
                    NativePhysicalRuntime.requireThread(level);
                    if(store.exists("empty-survey"))return Optional.of(store.readText("empty-survey"));
                    if(pass==null) {
                        NativeStructurePass.Plan plan;
                        if(selected!=null)plan=selected;
                        else if(store.exists("plan"))plan=store.read("plan",NativeStructurePass.Plan.class);
                        else {
                            if(survey==null)survey=new NativeTemplateSitePlanner(area,template,childConfig,level.getSeed(),catalog);
                            if(survey.exhausted()) {
                                store.write("empty-survey",new Evidence(group.ordinal(),group.operation().toString(),"NO_ELIGIBLE_EXACT_SITE","", ""));
                                return Optional.of(store.readText("empty-survey"));
                            }
                            var candidate=survey.step(level);if(candidate.isEmpty())return Optional.empty();plan=candidate.get();
                            // Reserve 16 blocks inside each group boundary: independently selected
                            // neighboring footprints remain at least 32 blocks apart.
                            if(plan.cells().stream().anyMatch(c->(long)c.x()<area.minX()+16L||(long)c.x()>area.maxX()-16L
                                ||(long)c.z()<area.minZ()+16L||(long)c.z()>area.maxZ()-16L))return Optional.empty();
                        }
                        selected=plan;
                        if(survey!=null){survey.close();survey=null;}
                        if(!window.structure(plan))return Optional.empty();
                        NativePhysicalRuntime.claim(level,"structure",area,plan.operation());
                        pass=new NativeStructurePass(plan,store);return Optional.empty();
                    }
                    if(!pass.done()){pass.step(level,32);return Optional.empty();}
                    if(!saved) {
                        if(!fr.proutlost.worldprep.runtime.BlockMutationRuntime.checkpointReady(level,fr.proutlost.worldprep.runtime.WorldPrepRuntime.data(level)))return Optional.empty();
                        level.save(null,true,false);saved=true;return Optional.empty();
                    }
                    if(audit==null)audit=new NativeStructureAudit(pass.plan(),store);
                    if(!audit.step(level))return Optional.empty();
                    NativeVerifiedStructureCatalog.publish(store);
                    store.write("evidence",new Evidence(group.ordinal(),group.operation().toString(),"PLACED_VERIFIED",
                        PhysicalStore.sha(store.readText("plan")),PhysicalStore.sha(store.readText("disk-verified"))));
                    return Optional.of(store.readText("evidence"));
                }
            };
        },(group,text)->{
            var store=new PhysicalStore(directory.resolve("group-"+group.ordinal()));
            var e=new com.google.gson.Gson().fromJson(text,Evidence.class);
            if(e==null||e.ordinal()!=group.ordinal()||!group.operation().toString().equals(e.child()))throw new IOException("Group evidence identity mismatch");
            if(e.disposition().equals("NO_ELIGIBLE_EXACT_SITE")) {
                if(store.exists("plan")||!store.readText("empty-survey").equals(text))throw new IOException("Survey evidence conflict");
            } else if(e.disposition().equals("PLACED_VERIFIED")) {
                if(store.exists("rollback-intent")||!store.readText("evidence").equals(text)
                    ||!PhysicalStore.sha(store.readText("plan")).equals(e.planSha())||!PhysicalStore.sha(store.readText("disk-verified")).equals(e.diskSha()))throw new IOException("Group physical proof changed");
                var plan=store.read("plan",NativeStructurePass.Plan.class);
                var receipt=store.read("disk-verified",NativeStructureAudit.Receipt.class);
                if(!receipt.operation().equals(plan.operation())||!receipt.planSha256().equals(e.planSha())||!receipt.status().equals("PLACED_VERIFIED"))throw new IOException("Group disk receipt mismatch");
            } else throw new IOException("Unknown group disposition");
        });
    }
    public Optional<String> step()throws Exception{return execution.step();}
    public void close()throws IOException{execution.close();}
}
