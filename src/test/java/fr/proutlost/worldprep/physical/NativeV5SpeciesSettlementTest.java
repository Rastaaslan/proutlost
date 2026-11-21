package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeV5SpeciesSettlementTest {
    @TempDir Path world;

    private static final UUID PARENT=UUID.fromString("5f71e31c-2c2b-5350-9bea-2fe075521e08");
    private final WorldPrepSavedData.Area area=new WorldPrepSavedData.Area(
        "proutlost:s2_full_ecology","minecraft:overworld",
        4496,-1666,5236,-943);
    private final String request="v5-test-exact-canonical-frozen-request";

    private MultiSpeciesFaunaPlan.Stage stage(){
        var cod=new NativeFaunaPass.Rule("minecraft:cod",
            Set.of("minecraft:ocean"),Set.of("minecraft:sand"),
            -64,319,-10f,10f,6,64,16,2,16,true,8,32);
        var cow=new NativeFaunaPass.Rule("minecraft:cow",
            Set.of("minecraft:plains"),Set.of("minecraft:grass_block"),
            -64,319,-10f,10f,0,64,16,2,16,true,8,32);
        return MultiSpeciesFaunaPlan.prepare(PARENT,List.of(cod,cow)).stages().getFirst();
    }
    private void fillSkipPerimeter(Path target,MultiSpeciesFaunaPlan.Stage stage)throws IOException {
        String config=request+"/species/"+stage.species()+"/"+stage.ruleSha256();
        var groups=new PerimeterGroups(stage.childBatch(),PhysicalStore.sha(config),
            area.dimension(),area.minX(),area.minZ(),area.maxX(),area.maxZ());
        assertEquals(36,groups.count());
        var index=new PhysicalStore(target.resolve("index"));
        index.write("perimeter",groups);
        String previous="0".repeat(64);
        for(long i=0;i<groups.count();i++) {
            var group=groups.group(i);
            String key="group-"+i;
            index.write("intent-"+i,new PerimeterExecution.Intent(1,
                stage.childBatch(),i,group.operation(),groups.configurationSha256()));
            var child=new PhysicalStore(target.resolve(key));
            var evidence=new NativePerimeterFauna.Evidence(i,
                group.operation().toString(),"NO_ELIGIBLE_HABITAT","","");
            child.write("evidence",evidence);
            String raw=child.readText("evidence");
            index.write(key,new PerimeterExecution.Receipt(1,stage.childBatch(),i,
                group.operation(),previous,raw,PhysicalStore.sha(raw)));
            previous=PhysicalStore.sha(index.readText(key));
        }
        index.write("aggregate",new PerimeterExecution.Aggregate(1,
            stage.childBatch(),groups.count(),groups.chunks(),previous,
            "GROUP_EVIDENCE_COMPLETE_NOT_MAP_READY"));
    }

    @Test void all36SkipReceiptsNeverPassRealAnimalAcceptance()throws Exception {
        var stage=stage();
        var dir=world.resolve("species-cod");
        fillSkipPerimeter(dir,stage);
        var summary=NativeV5SpeciesSettlement.verify(world,dir,area,stage,request);
        assertEquals(36,summary.groups());
        assertEquals(36,summary.groupsSkipped());
        assertEquals(0,summary.groupsPopulated());
        assertEquals(0,summary.animalsVerified());
        assertThrows(IOException.class,()->NativeV5SpeciesSettlement.requireObservedFauna(summary));
    }

    @Test void missingRetainedGroupRefusesFullPerimeterEvenIfAggregateExists()throws Exception {
        var stage=stage();
        var dir=world.resolve("species-cod");
        fillSkipPerimeter(dir,stage);
        java.nio.file.Files.delete(dir.resolve("index/group-23.json"));
        assertThrows(IOException.class,()->NativeV5SpeciesSettlement.verify(
            world,dir,area,stage,request));
    }

    @Test void foreignSpeciesStageCannotReplayValidLookingSkipChain()throws Exception {
        var stage=stage();
        var dir=world.resolve("species-cod");
        fillSkipPerimeter(dir,stage);
        var all=MultiSpeciesFaunaPlan.prepare(PARENT,List.of(stage.rule(),
            new NativeFaunaPass.Rule("minecraft:cow",Set.of("minecraft:plains"),
                Set.of("minecraft:grass_block"),-64,319,-10f,10f,0,64,16,2,16,true,8,32)));
        var cow=all.stages().get(1);
        assertThrows(IOException.class,()->NativeV5SpeciesSettlement.verify(
            world,dir,area,cow,request));
    }
}
