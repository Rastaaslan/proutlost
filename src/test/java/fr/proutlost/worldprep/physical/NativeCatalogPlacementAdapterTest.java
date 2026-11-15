package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.biome.v2.TerrainSemantic;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.structure.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class NativeCatalogPlacementAdapterTest {
    private final WorldPrepSavedData.Area area =
        new WorldPrepSavedData.Area("test:contra_lab", "minecraft:overworld", 0,0,63,63);
    private StructureCatalog catalog(StructureSupportStatus status,String adapter) {
        return new StructureCatalog(List.of(new StructureCapability(
            "proutlost:worldprep/custom_hut",status,adapter,5,4,5,2,32,4,
            Set.of(TerrainSemantic.LOWLAND))));
    }
    @Test void optInExactCatalogTemplateAcceptedWithoutWorldMutation() {
        var survey=new NativeTemplateSitePlanner(area,"proutlost:worldprep/custom_hut","pinned-test-only",27594267L,
            catalog(StructureSupportStatus.EXACT_TEMPLATE,NativeStructurePass.ADAPTER));
        assertFalse(survey.auditCandidates().isEmpty());
        assertEquals(0,survey.auditCheckedCandidates());
        survey.close();
    }
    @Test void legacyPathMustStillRejectUnknownTemplate() {
        assertThrows(IllegalArgumentException.class,()->
            new NativeTemplateSitePlanner(area,"proutlost:worldprep/custom_hut","pinned-test-only",27594267L));
    }
    @Test void opaqueCatalogMustNotGrantPlacement() {
        assertThrows(IllegalStateException.class,()->
            new NativeTemplateSitePlanner(area,"proutlost:worldprep/custom_hut","pinned-test-only",27594267L,
                catalog(StructureSupportStatus.UNSUPPORTED_OPAQUE,NativeStructurePass.ADAPTER)));
    }
    @Test void adapterMismatchMustFailClosed() {
        assertThrows(IllegalArgumentException.class,()->
            new NativeTemplateSitePlanner(area,"proutlost:worldprep/custom_hut","pinned-test-only",27594267L,
                catalog(StructureSupportStatus.EXACT_TEMPLATE,"untrusted-adapter")));
    }
}
