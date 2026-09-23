package fr.proutlost.worldprep;

import fr.proutlost.worldprep.pipeline.EnvironmentalPipeline;
import fr.proutlost.worldprep.pipeline.PassGraph;
import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.pipeline.PlanningWorldView;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import fr.proutlost.worldprep.storage.PagedPlanStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PlanningWorldViewTest {
    @Test void pagedViewIsExactPriorityOrderedAndBounded(@TempDir Path root) throws Exception {
        var layers = new ArrayList<PlanningWorldView.SealedLayer<String, String, EnvironmentalPipeline.Entry>>();
        for (PassId pass : List.of(PassId.STRUCTURES, PassId.SOILS, PassId.BIOMES, PassId.TERRAIN)) {
            UUID id = UUID.nameUUIDFromBytes(pass.name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var entries = new ArrayList<EnvironmentalPipeline.Entry>();
            for (int i=0;i<100;i++) entries.add(new EnvironmentalPipeline.Entry(i, 64, 0, pass.name()));
            var publication = PagedPlanStore.publish(root, id, pass, "test:area", "minecraft:overworld",
                    "test", PlanFingerprint.of("semantic", pass.name()).value(), "test", "test", 3,
                    entries, EnvironmentalPipeline.CODEC);
            layers.add(new PlanningWorldView.SealedLayer<>(publication.directory(), publication.manifest(),
                    id, publication.manifest().rootFingerprint(), EnvironmentalPipeline.CODEC,
                    e -> e.x()+":"+e.y()+":"+e.z(), EnvironmentalPipeline.Entry::value));
        }
        assertEquals(PassGraph.dependencies(PassId.FLORA),
                java.util.Set.of(PassId.TERRAIN, PassId.BIOMES, PassId.SOILS, PassId.STRUCTURES));
        var view = PlanningWorldView.open(key -> "BASE", PassId.FLORA, layers, 2);
        assertEquals("STRUCTURES", view.get("99:64:0"));
        assertEquals("BASE", view.get("1000:64:0"));
        assertTrue(view.maximumResidentPages() <= layers.size() * 2);
    }

    @Test void staleMissingAndCorruptUpstreamFailClosed(@TempDir Path root) throws Exception {
        UUID id = UUID.randomUUID();
        var publication = PagedPlanStore.publish(root, id, PassId.TERRAIN, "test:area", "minecraft:overworld",
                "test", PlanFingerprint.of("semantic").value(), "test", "test", 1,
                List.of(new EnvironmentalPipeline.Entry(0, 0, 0, "terrain")), EnvironmentalPipeline.CODEC);
        var valid = new PlanningWorldView.SealedLayer<>(publication.directory(), publication.manifest(), id,
                publication.manifest().rootFingerprint(), EnvironmentalPipeline.CODEC,
                EnvironmentalPipeline.Entry::x, EnvironmentalPipeline.Entry::value);
        assertThrows(IllegalStateException.class, () -> PlanningWorldView.open(x -> "base", PassId.SOILS,
                List.of(valid), 1));
        var stale = new PlanningWorldView.SealedLayer<>(publication.directory(), publication.manifest(),
                UUID.randomUUID(), publication.manifest().rootFingerprint(), EnvironmentalPipeline.CODEC,
                EnvironmentalPipeline.Entry::x, EnvironmentalPipeline.Entry::value);
        assertThrows(IllegalStateException.class, () -> PlanningWorldView.open(x -> "base", PassId.BIOMES,
                List.of(stale), 1));
        Files.write(PagedPlanStore.pagePath(publication.directory(), 0), new byte[]{1,2,3});
        assertThrows(java.io.IOException.class, () -> PlanningWorldView.open(x -> "base", PassId.BIOMES,
                List.of(valid), 1));
    }
}
