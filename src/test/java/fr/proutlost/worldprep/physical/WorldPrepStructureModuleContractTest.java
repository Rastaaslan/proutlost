package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.biome.v2.TerrainSemantic;
import fr.proutlost.worldprep.structure.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class WorldPrepStructureModuleContractTest {
    private static StructureCapability valid() {
        return new StructureCapability("proutlost:worldprep/village_hut",
            StructureSupportStatus.EXACT_TEMPLATE, NativeStructurePass.ADAPTER,
            7,6,7,3,48,5,Set.of(TerrainSemantic.LOWLAND));
    }
    private static WorldPrepStructureModule.Request request(int limit) {
        return new WorldPrepStructureModule.Request(1,UUID.fromString("e70d53ef-e32c-4583-ac1d-01594f2a1ddb"),
            "proutlost:contra_s2_ecology",27594267L,"a".repeat(64),limit,2,List.of(valid()),
            List.of(new AutomaticStructurePlanner.Candidate(valid().structureId(),100,70,100,100,TerrainSemantic.LOWLAND,1)),
            java.util.Map.of(valid().structureId(),Set.of("proutlost:structure","proutlost:village","proutlost:explorable")));
    }
    @Test void validStandaloneRequestAccepted() { assertEquals(5,request(5).catalog().getFirst().rarityDivisor()); }
    @Test void emptyRequestFails() {
        assertThrows(IllegalArgumentException.class,()->new WorldPrepStructureModule.Request(1,UUID.randomUUID(),
            "area",27594267L,"a".repeat(64),1,1,List.of(valid()),List.of(),
            java.util.Map.of(valid().structureId(),Set.of("proutlost:structure"))));
    }
    @Test void illegalDensityBoundFails() {assertThrows(IllegalArgumentException.class,()->request(4097));}
    @Test void immutableCatalogDefendsInput() {
        var r=request(2);
        assertThrows(UnsupportedOperationException.class,()->r.catalog().clear());
        assertThrows(UnsupportedOperationException.class,()->r.candidates().clear());
    }
}
