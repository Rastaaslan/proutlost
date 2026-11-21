package fr.proutlost.worldprep;

import static org.junit.jupiter.api.Assertions.*;
import fr.proutlost.worldprep.compiler.WorldPrepCompiler;
import fr.proutlost.worldprep.pipeline.PassGraph;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import fr.proutlost.worldprep.storage.DurablePageStore;
import fr.proutlost.worldprep.storage.PlanManifest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorldPrepCompilerTest {
    @Test void compileBindsEveryExactManifestAndRejectsLatestAlias() {
        var manifests = PassGraph.order().stream().map(pass -> PlanManifest.create(DurablePageStore.FORMAT_VERSION,
                UUID.nameUUIDFromBytes(pass.name().getBytes()), pass, "area", "minecraft:overworld", "profile",
                PlanFingerprint.of("input", pass.name()).value(), "v2", "planner", PlanManifest.State.SEALED, List.of())).toList();
        var compiler = new WorldPrepCompiler();
        var first = compiler.compile("source", "profile", "pack", 7, manifests);
        var second = compiler.compile("source", "profile", "pack", 7, manifests);
        assertEquals(first, second);
        assertEquals(PassGraph.order().size(), first.passes().size());
        assertThrows(IllegalStateException.class, () -> compiler.requireExact(first, "latest"));
        assertDoesNotThrow(() -> compiler.requireExact(first, first.pipelineId()));
    }

    @Test void changingUpstreamManifestChangesPipelineAndDownstreamChain() {
        var original = manifests("one"); var changed = manifests("two"); var compiler = new WorldPrepCompiler();
        var first = compiler.compile("source", "profile", "pack", 7, original);
        var second = compiler.compile("source", "profile", "pack", 7, changed);
        assertNotEquals(first.pipelineId(), second.pipelineId());
        assertNotEquals(first.passes().getLast().upstreamChainFingerprint(), second.passes().getLast().upstreamChainFingerprint());
    }

    private static List<PlanManifest> manifests(String salt) {
        return PassGraph.order().stream().map(pass -> PlanManifest.create(DurablePageStore.FORMAT_VERSION,
                UUID.nameUUIDFromBytes((salt + pass.name()).getBytes()), pass, "area", "minecraft:overworld", "profile",
                PlanFingerprint.of("input", pass.name()).value(), "v2", "planner", PlanManifest.State.SEALED, List.of())).toList();
    }
}
