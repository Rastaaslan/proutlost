package fr.proutlost.worldprep;

import static org.junit.jupiter.api.Assertions.*;
import fr.proutlost.worldprep.biome.v2.TerrainSemantic;
import fr.proutlost.worldprep.structure.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class StructurePlannerTest {
    @Test void allocationIsOrderIndependentRotatedAndNonOverlapping() {
        var capability = new StructureCapability("test:hut", StructureSupportStatus.EXACT_TEMPLATE, "adapter",
                9, 7, 5, 2, 4, 1, Set.of(TerrainSemantic.LOWLAND));
        var catalog = new StructureCatalog(List.of(capability));
        var candidates = new ArrayList<>(List.of(
                new AutomaticStructurePlanner.Candidate("test:hut", 0, 64, 0, 10, TerrainSemantic.LOWLAND, 0),
                new AutomaticStructurePlanner.Candidate("test:hut", 100, 64, 100, 8, TerrainSemantic.LOWLAND, 1),
                new AutomaticStructurePlanner.Candidate("test:hut", 2, 64, 2, 9, TerrainSemantic.LOWLAND, 0)));
        var planner = new AutomaticStructurePlanner();
        var first = planner.plan(candidates, catalog, 42, 10, 2);
        Collections.reverse(candidates);
        var second = planner.plan(candidates, catalog, 42, 10, 2);
        assertEquals(first, second);
        assertEquals(2, first.size());
        assertFalse(first.get(0).reservation().intersects(first.get(1).reservation()));
        assertEquals(7, first.get(0).footprint().maxY() - first.get(0).footprint().minY() + 1);
    }

    @Test void opaqueRequiredStructureRefuses() {
        var catalog = new StructureCatalog(List.of(new StructureCapability("mod:opaque",
                StructureSupportStatus.UNSUPPORTED_OPAQUE, "none", 1, 1, 1, 0, 0, 1,
                Set.of(TerrainSemantic.LOWLAND))));
        assertThrows(IllegalStateException.class, () -> new AutomaticStructurePlanner().plan(List.of(
                new AutomaticStructurePlanner.Candidate("mod:opaque", 0, 0, 0, 1,
                        TerrainSemantic.LOWLAND, 0)), catalog, 1, 1, 1));
    }

    @Test void terrainEligibilityAndRegionDensityAreEnforced() {
        var capability = new StructureCapability("test:reef", StructureSupportStatus.EXACT_TEMPLATE, "adapter",
                5, 3, 5, 0, 0, 1, Set.of(TerrainSemantic.SHALLOW_WATER));
        var planner = new AutomaticStructurePlanner();
        var result = planner.plan(List.of(
                new AutomaticStructurePlanner.Candidate("test:reef", 0, 40, 0, 3, TerrainSemantic.LOWLAND, 0),
                new AutomaticStructurePlanner.Candidate("test:reef", 30, 40, 0, 2, TerrainSemantic.SHALLOW_WATER, 0),
                new AutomaticStructurePlanner.Candidate("test:reef", 60, 40, 0, 1, TerrainSemantic.SHALLOW_WATER, 0)),
                new StructureCatalog(List.of(capability)), 4, 10, 1);
        assertEquals(1, result.size());
        assertEquals(30, result.getFirst().x());
    }
}
