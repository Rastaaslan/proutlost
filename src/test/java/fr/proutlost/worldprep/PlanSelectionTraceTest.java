package fr.proutlost.worldprep;

import com.google.gson.*;
import fr.proutlost.worldprep.storage.*;
import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.preview.PreviewRenderer;
import fr.proutlost.worldprep.runtime.RuntimePlanStore;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData.BiomeCell;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PlanSelectionTraceTest {
    @TempDir Path root;
    private static final String DIGEST=PlanFingerprint.of("selection-diagnostic-test").value();
    private PagedPlanStore.Writer<BiomeCell> writer()throws IOException {
        return PagedPlanStore.begin(root.resolve("plans"),UUID.randomUUID(),PassId.BIOMES,"test:area",
            "minecraft:overworld","test",DIGEST,RuntimePlanStore.ENGINE_VERSION,PlanSelectionTrace.PLANNER_VERSION,1,RuntimePlanStore.BIOME_CODEC);
    }
    private static BiomeCell cell(int qx,String id){return new BiomeCell(qx,0,id,7);}
    private PreviewRenderer.Request request(PagedPlanStore.Publication p,Set<String> registry){
        var m=p.manifest();return new PreviewRenderer.Request(p.directory(),root.resolve("exports"),m.planId(),m.passId(),
            m.areaId(),m.dimension(),m.profile(),1,0,0,15,15,-64,320,m.rootFingerprint(),m.semanticInputDigest(),
            m.pages().stream().mapToLong(PlanManifest.PageReference::entryCount).sum(),
            Map.of("area_mask","FULL_AREA","tree_mode","NORMAL"),registry,List.of(),List.of(),"{}");
    }
    @Test void measuredDirectAndPositiveFallbackAreBoundToSealedPlanAndCachedExport()throws Exception {
        var w=writer();
        w.appendSelected(cell(0,"minecraft:forest"),PlanSelectionTrace.Selection.DIRECT);
        // A real resolver result is marked FALLBACK; this fixture does not claim native planners substitute IDs.
        var resolved=new fr.proutlost.worldprep.biome.BiomeCandidate("missing:biome","minecraft:plains").resolve("minecraft:plains"::equals);
        assertEquals("FALLBACK",resolved.status().name());
        w.appendSelected(cell(1,resolved.resolvedId()),PlanSelectionTrace.Selection.FALLBACK);
        assertTrue(w.finish(1).isEmpty());
        assertEquals(PlanManifest.State.WRITING,PagedPlanStore.readManifest(w.directory()).state());
        assertFalse(Files.exists(PlanSelectionTrace.path(w.directory())));
        var p=w.finish(1).orElseThrow();var count=PlanSelectionTrace.read(p.directory(),p.manifest()).orElseThrow();
        assertEquals(1,count.direct());assertEquals(1,count.fallback());assertEquals(2,count.entries());
        byte[] trace=Files.readAllBytes(PlanSelectionTrace.path(p.directory()));
        var req=request(p,Set.of("minecraft:plains","minecraft:forest"));
        var out=PreviewRenderer.render(req);
        var json=JsonParser.parseString(Files.readString(out.metadata())).getAsJsonObject();
        var usage=json.getAsJsonObject("fallback_usage").getAsJsonObject("planner");
        assertEquals(1,usage.get("fallback").getAsLong());assertEquals(2,usage.get("entries").getAsLong());
        assertEquals(count.checksum(),json.getAsJsonObject("input_details").get("planner_selection_trace_checksum").getAsString());
        assertTrue(PreviewRenderer.render(req).cached());assertArrayEquals(trace,Files.readAllBytes(PlanSelectionTrace.path(p.directory())));
    }
    @Test void emptyPlansHaveMeasuredZeroWithoutInventingEntries()throws Exception {
        var w=writer();assertThrows(IllegalStateException.class,()->w.append(cell(0,"minecraft:plains")));
        var p=w.finish(1).orElseThrow();var m=PlanSelectionTrace.read(p.directory(),p.manifest()).orElseThrow();
        assertEquals(0,m.entries());assertEquals(0,m.fallback());assertEquals(0,m.direct());
        var out=PreviewRenderer.render(request(p,Set.of()));
        assertEquals(0,JsonParser.parseString(Files.readString(out.metadata())).getAsJsonObject().get("entries").getAsLong());
    }
    @Test void missingCorruptAndWrongOwnerTraceFailClosedEvenWithCachedExport()throws Exception {
        var w=writer();w.appendSelected(cell(0,"minecraft:plains"),PlanSelectionTrace.Selection.DIRECT);
        var p=w.finish(1).orElseThrow();var req=request(p,Set.of("minecraft:plains"));PreviewRenderer.render(req);
        Path path=PlanSelectionTrace.path(p.directory());byte[] original=Files.readAllBytes(path);
        Files.delete(path);assertThrows(IOException.class,()->PreviewRenderer.render(req));
        Files.write(path,original);Files.write(path,new byte[]{1},StandardOpenOption.APPEND);
        assertThrows(IOException.class,()->PreviewRenderer.render(req));
        Files.write(path,original);
        var other=writer();other.appendSelected(cell(0,"minecraft:plains"),PlanSelectionTrace.Selection.DIRECT);
        var otherPlan=other.finish(1).orElseThrow();Files.copy(PlanSelectionTrace.path(otherPlan.directory()),path,StandardCopyOption.REPLACE_EXISTING);
        assertThrows(IOException.class,()->PreviewRenderer.render(req));
    }
    @Test void rootInputAndCountMismatchCannotBePublishedOrRead()throws Exception {
        var w=writer();w.appendSelected(cell(0,"minecraft:plains"),PlanSelectionTrace.Selection.DIRECT);
        var p=w.finish(1).orElseThrow();Path path=PlanSelectionTrace.path(p.directory());
        Files.delete(path);assertThrows(IOException.class,()->PlanSelectionTrace.publish(p.directory(),p.manifest(),0,0));
        PlanSelectionTrace.publish(p.directory(),p.manifest(),1,0);
        var altered=PlanManifest.create(p.manifest().planFormatVersion(),p.manifest().planId(),p.manifest().passId(),
            p.manifest().areaId(),p.manifest().dimension(),p.manifest().profile(),PlanFingerprint.of("different-input").value(),
            p.manifest().engineVersion(),p.manifest().plannerVersion(),PlanManifest.State.SEALED,p.manifest().pages());
        assertThrows(IOException.class,()->PlanSelectionTrace.read(p.directory(),altered));
        assertThrows(IOException.class,()->PlanSelectionTrace.publish(p.directory(),p.manifest(),0,1));
    }
    @Test void legacyRemainsUnknownAndMissingRegistryTargetsAreNotReplaced()throws Exception {
        var p=PagedPlanStore.publish(root.resolve("plans"),UUID.randomUUID(),PassId.BIOMES,"test:area","minecraft:overworld","test",
            DIGEST,RuntimePlanStore.ENGINE_VERSION,RuntimePlanStore.PLANNER_VERSION,1,List.of(cell(0,"missing:biome")),RuntimePlanStore.BIOME_CODEC);
        assertTrue(PlanSelectionTrace.read(p.directory(),p.manifest()).isEmpty());
        var req=request(p,Set.of("minecraft:plains"));var out=PreviewRenderer.render(req);
        var json=JsonParser.parseString(Files.readString(out.metadata())).getAsJsonObject();
        assertEquals("NOT_ENCODED_IN_SEALED_PLAN",json.getAsJsonObject("fallback_usage").get("planner").getAsString());
        assertFalse(json.getAsJsonObject("registry_validation").get("valid").getAsBoolean());
        assertTrue(json.getAsJsonObject("distribution_statistics").has("missing:biome"));assertTrue(PreviewRenderer.render(req).cached());
    }
}
