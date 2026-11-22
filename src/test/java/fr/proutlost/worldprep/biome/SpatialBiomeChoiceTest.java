package fr.proutlost.worldprep.biome;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpatialBiomeChoiceTest {
    private static BiomeProfile.Resolved profile() throws Exception {
        try (var in = SpatialBiomeChoiceTest.class.getResourceAsStream(
                "/data/proutlost/worldprep/profiles/river_test.json")) {
            assertNotNull(in);
            var resolved = BiomeProfile.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                .resolve(id -> true);
            resolved.requireValid();
            return resolved;
        }
    }

    private static String choose(BiomeProfile.Resolved profile, long seed, int x, int z) {
        return profile.chooseSpatial(SpatialBiomeChoice.lowlandPalette(seed, x, z),
            SpatialBiomeChoice.variant(seed, x, z)).resolvedId();
    }

    @Test void identicalTerrainFormsRegionsInsteadOfFourBlockConfetti() throws Exception {
        var p = profile();
        long seed = p.profile().seed();
        var cells = new HashMap<Long, String>();
        var distinct = new HashSet<String>();
        for (int z = -128; z < 128; z++) for (int x = -128; x < 128; x++) {
            String id = choose(p, seed, x, z);
            cells.put(((long)x << 32) ^ (z & 0xffffffffL), id);
            distinct.add(id);
        }
        int edges = 0, different = 0;
        for (int z = -128; z < 128; z++) for (int x = -128; x < 128; x++) {
            String here = cells.get(((long)x << 32) ^ (z & 0xffffffffL));
            for (int[] next : new int[][] {{x + 1, z}, {x, z + 1}}) {
                String other = cells.get(((long)next[0] << 32) ^ (next[1] & 0xffffffffL));
                if (other != null) {
                    edges++;
                    if (!here.equals(other)) different++;
                }
            }
        }
        assertTrue(distinct.size() >= 2, "Coherence must retain regional diversity");
        assertTrue(different < edges / 10,
            "Identical terrain must form broad regions, not alternating quart cells: " + different + "/" + edges);
        System.out.println("PURE_SPATIAL_BIOME_REGIONS cells=" + cells.size()
            + " adjacentEdges=" + edges + " different=" + different + " biomes=" + distinct);
    }

    @Test void seedCoordinatesAndSamplingOrderDetermineTheSameChoices() throws Exception {
        var p = profile();
        var coordinates = new ArrayList<int[]>();
        for (int z = -1024; z <= 1024; z += 29) for (int x = -1024; x <= 1024; x += 31)
            coordinates.add(new int[] {x, z});
        var expected = new HashMap<String, String>();
        for (var c : coordinates) expected.put(c[0] + "/" + c[1], choose(p, 777, c[0], c[1]));
        Collections.shuffle(coordinates, new Random(21));
        int changedSeed = 0;
        for (var c : coordinates) {
            String key = c[0] + "/" + c[1];
            assertEquals(expected.get(key), choose(p, 777, c[0], c[1]));
            if (!expected.get(key).equals(choose(p, 778, c[0], c[1]))) changedSeed++;
            double a = SpatialBiomeChoice.variant(777, c[0], c[1]);
            double b = SpatialBiomeChoice.variant(777, c[0] + 1, c[1]);
            assertTrue(Double.isFinite(a) && a >= 0 && a <= 1);
            assertTrue(Math.abs(a - b) < .05, "Adjacent quart cells must not receive unrelated variant draws");
        }
        assertTrue(changedSeed > coordinates.size() / 10, "Profile seed must affect actual selections");
    }

    @Test void spatialPaletteSelectionPreservesRegistryResolutionAndFailsClosed() {
        var profile = new BiomeProfile("proutlost:test", 1,
            Map.of("open_lowland", List.of(new BiomeCandidate("absent:biome", "minecraft:plains"),
                new BiomeCandidate("minecraft:meadow", null))));
        var resolved = profile.resolve(id -> id.startsWith("minecraft:"));
        assertEquals(BiomeCandidate.Status.FALLBACK, resolved.chooseSpatial("open_lowland", 0).status());
        assertEquals("minecraft:meadow", resolved.chooseSpatial("open_lowland", 1).resolvedId());
        for (double value : new double[] {Double.NaN, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, -.001, 1.001})
            assertThrows(IllegalArgumentException.class, () -> resolved.chooseSpatial("open_lowland", value));
        assertThrows(IllegalStateException.class, () -> resolved.chooseSpatial("missing", .5));
    }
}
