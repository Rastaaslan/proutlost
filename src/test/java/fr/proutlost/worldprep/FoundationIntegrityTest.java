package fr.proutlost.worldprep;

import fr.proutlost.worldprep.pipeline.PassGraph;
import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.pipeline.PipelineRun;
import fr.proutlost.worldprep.pipeline.SemanticConfig;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import fr.proutlost.worldprep.storage.DurablePageStore;
import fr.proutlost.worldprep.storage.PlanManifest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FoundationIntegrityTest {
    private static String hash(String value) {
        return PlanFingerprint.of(value).value();
    }

    @Test void sealedManifestVerifiesCompleteIdentity() {
        UUID planId = UUID.randomUUID();
        var pages = List.of(new PlanManifest.PageReference(0, -2, 7, 3, hash("page")));
        var manifest = PlanManifest.create(DurablePageStore.FORMAT_VERSION, planId, PassId.GEOLOGY,
                "proutlost:island", "minecraft:overworld", "proutlost:river_test", hash("semantic"),
                "engine-1", "geology-1", PlanManifest.State.SEALED, pages);
        assertDoesNotThrow(manifest::requireApplicable);

        assertThrows(IllegalArgumentException.class, () -> new PlanManifest(
                DurablePageStore.FORMAT_VERSION, planId, PassId.GEOLOGY, "proutlost:island",
                "minecraft:overworld", "proutlost:other_profile", hash("semantic"), "engine-1",
                "geology-1", PlanManifest.State.SEALED, pages, manifest.rootFingerprint()));
        assertThrows(IllegalArgumentException.class, () -> new PlanManifest(
                DurablePageStore.FORMAT_VERSION, planId, PassId.GEOLOGY, "proutlost:island",
                "minecraft:overworld", "proutlost:river_test", hash("different-semantic"), "engine-1",
                "geology-1", PlanManifest.State.SEALED, pages, manifest.rootFingerprint()));
        assertThrows(IllegalArgumentException.class, () -> new PlanManifest(
                DurablePageStore.FORMAT_VERSION, planId, PassId.ORES, "proutlost:island",
                "minecraft:overworld", "proutlost:river_test", hash("semantic"), "engine-1",
                "geology-1", PlanManifest.State.SEALED, pages, manifest.rootFingerprint()));
        assertThrows(IllegalArgumentException.class, () -> new PlanManifest(
                DurablePageStore.FORMAT_VERSION, planId, PassId.GEOLOGY, "proutlost:island",
                "minecraft:overworld", "proutlost:river_test", hash("semantic"), "engine-1",
                "geology-1", PlanManifest.State.SEALED, pages, hash("forged-root")));
    }

    @Test void manifestRejectsNonCanonicalPages() {
        var first = new PlanManifest.PageReference(0, 0, 0, 1, hash("a"));
        var second = new PlanManifest.PageReference(1, 1, 0, 1, hash("b"));
        assertDoesNotThrow(() -> PlanManifest.create(DurablePageStore.FORMAT_VERSION, UUID.randomUUID(),
                PassId.BIOMES, "area", "minecraft:overworld", "profile", hash("semantic"),
                "engine", "planner", PlanManifest.State.SEALED, List.of(first, second)));
        assertThrows(IllegalArgumentException.class, () -> PlanManifest.create(
                DurablePageStore.FORMAT_VERSION, UUID.randomUUID(), PassId.BIOMES, "area",
                "minecraft:overworld", "profile", hash("semantic"), "engine", "planner",
                PlanManifest.State.SEALED, List.of(second, first)));
        assertThrows(IllegalArgumentException.class, () -> PlanManifest.create(
                DurablePageStore.FORMAT_VERSION, UUID.randomUUID(), PassId.BIOMES, "area",
                "minecraft:overworld", "profile", hash("semantic"), "engine", "planner",
                PlanManifest.State.SEALED,
                List.of(first, new PlanManifest.PageReference(0, 2, 0, 1, hash("c")))));
    }

    @Test void sealedPipelineVerifiesCompleteIdentityAndPlanFingerprint() {
        UUID runId = UUID.randomUUID();
        UUID biomePlan = UUID.randomUUID();
        var plans = Map.of(PassId.BIOMES, new PipelineRun.PlanReference(biomePlan, hash("biome-plan")));
        var run = PipelineRun.create(runId, "area", "minecraft:overworld", "proutlost:river_test",
                hash("world"), plans, PipelineRun.State.SEALED);
        assertDoesNotThrow(run::requireSealed);

        assertThrows(IllegalArgumentException.class, () -> new PipelineRun(runId, "other-area",
                "minecraft:overworld", "proutlost:river_test", hash("world"), plans,
                run.rootFingerprint(), PipelineRun.State.SEALED));
        assertThrows(IllegalArgumentException.class, () -> new PipelineRun(runId, "area",
                "minecraft:overworld", "proutlost:river_test", hash("world"),
                Map.of(PassId.BIOMES, new PipelineRun.PlanReference(biomePlan, hash("different-plan"))),
                run.rootFingerprint(), PipelineRun.State.SEALED));
        assertThrows(IllegalArgumentException.class, () -> new PipelineRun(runId, "area",
                "minecraft:overworld", "proutlost:river_test", hash("world"), plans,
                hash("forged-root"), PipelineRun.State.SEALED));
    }

    @Test void validationIsInvalidatedByEveryEnvironmentalPass() {
        for (PassId pass : List.of(PassId.TERRAIN, PassId.BIOMES, PassId.GEOLOGY, PassId.ORES,
                PassId.SOILS, PassId.FLORA, PassId.TREES, PassId.AQUATIC, PassId.HABITATS)) {
            assertTrue(PassGraph.invalidatedBy(pass).contains(PassId.VALIDATION),
                    () -> pass + " did not invalidate VALIDATION");
        }
    }

    @Test void semanticConfigEncodingIsCanonicalAndUnambiguous() {
        var orderedA = new LinkedHashMap<String, String>();
        orderedA.put("β", "unicode");
        orderedA.put("a", "line\nvalue");
        orderedA.put("empty", "");
        var orderedB = new LinkedHashMap<String, String>();
        orderedB.put("empty", "");
        orderedB.put("a", "line\nvalue");
        orderedB.put("β", "unicode");
        assertEquals(new SemanticConfig(new TreeMap<>(orderedA)).fingerprint(),
                new SemanticConfig(new TreeMap<>(orderedB)).fingerprint());

        assertNotEquals(new SemanticConfig(new TreeMap<>(Map.of("a=b", "c"))).fingerprint(),
                new SemanticConfig(new TreeMap<>(Map.of("a", "b=c"))).fingerprint());
        assertNotEquals(new SemanticConfig(new TreeMap<>(Map.of("a:b", "c"))).fingerprint(),
                new SemanticConfig(new TreeMap<>(Map.of("a", "b:c"))).fingerprint());
        assertNotEquals(new SemanticConfig(new TreeMap<>(Map.of("a\n", "b"))).fingerprint(),
                new SemanticConfig(new TreeMap<>(Map.of("a", "\nb"))).fingerprint());
    }
}
