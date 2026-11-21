package fr.proutlost.worldprep;
import fr.proutlost.worldprep.structure.*;
import fr.proutlost.worldprep.biome.v2.TerrainSemantic;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class StructureQualificationTest {
    StructureCatalog catalog(String fingerprint, StructureSupportStatus status) {
        return new StructureCatalog(List.of(new StructureCapability("test:hut",status,fingerprint,5,4,9,2,8,1,Set.of(TerrainSemantic.LOWLAND))));
    }
    List<StructureInstancePlan> plan(StructureCatalog c, String config, int x) {
        return new AutomaticStructurePlanner().plan(List.of(new AutomaticStructurePlanner.Candidate("test:hut",x,75,0,10,TerrainSemantic.LOWLAND,0)),c,42,5,3,config);
    }
    @Test void catalogAndConfigChangesProduceNewIdentity() {
        var c = catalog("adapter-v1",StructureSupportStatus.EXACT_TEMPLATE);
        String first = plan(c,"a".repeat(64),0).getFirst().instanceId();
        assertNotEquals(first,plan(c,"b".repeat(64),0).getFirst().instanceId());
        assertNotEquals(first,plan(catalog("adapter-v2",StructureSupportStatus.EXACT_TEMPLATE),"a".repeat(64),0).getFirst().instanceId());
    }
    @Test void sandboxMaterializationIsNotProductionCapability() {
        assertThrows(IllegalStateException.class,()->plan(catalog("unproven",StructureSupportStatus.SANDBOX_MATERIALIZABLE),"a".repeat(64),0));
    }
    @Test void envelopeOverflowRefused() {
        assertThrows(ArithmeticException.class,()->plan(catalog("v1",StructureSupportStatus.EXACT_TEMPLATE),"a".repeat(64),Integer.MAX_VALUE));
    }
    @Test void missingRegistryCapabilityRefused() {
        assertThrows(IllegalStateException.class,()->plan(new StructureCatalog(List.of()),"a".repeat(64),0));
    }
}
