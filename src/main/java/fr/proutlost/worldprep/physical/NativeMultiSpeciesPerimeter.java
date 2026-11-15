package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import net.minecraft.server.level.ServerLevel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Internal adapter for a FUTURE separately sealed v5 NativeQualificationSession.
 *
 * Not registered as a command, a world tick handler or a v4 fallback.
 * The real v5 binding must be checked by its caller BEFORE constructing this
 * adapter. All species retain distinct child perimeter operations and journals;
 * the world population ledger is intentionally still shared.
 */
final class NativeMultiSpeciesPerimeter implements QualificationOrchestrator.Task {
    private final MultiSpeciesStageExecution execution;

    NativeMultiSpeciesPerimeter(ServerLevel level,WorldPrepSavedData.Area area,
                                MultiSpeciesFaunaPlan.Manifest manifest,
                                String frozenRequest,Path directory,int protocolVersion)throws IOException {
        if(protocolVersion!=MultiSpeciesFaunaPlan.VERSION)
            throw new IOException("V4/single-species request cannot open a v5 stage");
        // A claimed backup SHA is insufficient: independently reopen the
        // candidate-bound, immutable v5 WAL AND both backup headers before any
        // child engine or population budget is constructed.
        NativeV5ContractGate.requireActualSealedBinding(
            level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),
            directory,frozenRequest,manifest,area,
            net.neoforged.fml.loading.FMLLoader.isProduction());
        NativePhysicalRuntime.requireThread(level);
        MultiSpeciesFaunaPlan.verify(manifest);
        if(manifest==null||manifest.version()!=5
            ||manifest.stages().size()<2||manifest.stages().size()>8
            ||frozenRequest==null||frozenRequest.isBlank()||frozenRequest.length()>4096)
            throw new IOException("Missing bounded frozen v5 parent intent");
        var groups=new PerimeterGroups(manifest.parentBatch(),
            PhysicalStore.sha(frozenRequest),area.dimension(),
            area.minX(),area.minZ(),area.maxX(),area.maxZ());
        if(groups.count()>64)throw new IOException("V5 qualification limited to 64 groups");
        var root=new PhysicalStore(directory.resolve("multi-species-index"));
        execution=new MultiSpeciesStageExecution(manifest,root,
            stage->new NativePerimeterFauna(level,area,stage.childBatch(),
                frozenRequest+"/species/"+stage.species()+"/"+stage.ruleSha256(),
                stage.rule(),directory.resolve(stage.directory())),
            (stage,evidence)->{
                var childStore=new PhysicalStore(directory.resolve(stage.directory()).resolve("index"));
                String current=childStore.readText("aggregate");
                if(!current.equals(evidence))
                    throw new IOException("Retained species aggregate bytes changed");
                var record=new com.google.gson.Gson().fromJson(evidence,PerimeterExecution.Aggregate.class);
                if(record==null||record.version()!=1
                    ||!record.parent().equals(stage.childBatch())
                    ||record.groups()!=groups.count()
                    ||record.chunks()!=groups.chunks()
                    ||!"GROUP_EVIDENCE_COMPLETE_NOT_MAP_READY".equals(record.status()))
                    throw new IOException("V5 species aggregate is not a complete exact S2 perimeter");
                // Aggregate completion is a cursor boundary, NOT evidence of
                // any real animal. Independently replay every group receipt and
                // actual persisted Anvil UUID before accepting this species stage.
                var settlement=NativeV5SpeciesSettlement.verify(
                    level.getServer().getWorldPath(
                        net.minecraft.world.level.storage.LevelResource.ROOT),
                    directory.resolve(stage.directory()),area,stage,frozenRequest);
                NativeV5SpeciesSettlement.requireObservedFauna(settlement);
                root.write("native-settlement-"+stage.ordinal(),settlement);
            });
    }
    @Override public Optional<String> step()throws Exception{return execution.step();}
    @Override public void close()throws IOException{execution.close();}
}
