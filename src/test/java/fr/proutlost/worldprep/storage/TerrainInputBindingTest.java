package fr.proutlost.worldprep.storage;

import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TerrainInputBindingTest {
    @TempDir Path root;
    private static final String BASE = PlanFingerprint.of("actual-profile-and-policy-context").value();
    private static final PagedPlanStore.EntryCodec<Integer> CODEC = new PagedPlanStore.EntryCodec<>() {
        public void write(DataOutput out,Integer value)throws IOException { out.writeInt(value); }
        public Integer read(DataInput in)throws IOException { return in.readInt(); }
    };

    private static String scan(TerrainInputFingerprint.Bounds bounds,int budget,
            TerrainInputFingerprint.Reader reader) {
        var cursor=new TerrainInputFingerprint.Cursor(bounds);
        while(!cursor.complete()){
            var reads=new AtomicInteger();
            int used=cursor.advance(budget,(x,y,z)->{reads.incrementAndGet();return reader.state(x,y,z);});
            assertEquals(used,reads.get());
            assertTrue(used<=budget);
        }
        assertEquals(bounds.cells(),cursor.scanned());
        return cursor.fingerprint();
    }

    @Test void changedSupportOutsideEffectCellsInvalidatesThePersistedInputUnderTheSameCellBudget(){
        var bounds=new TerrainInputFingerprint.Bounds(16,64,16,31,96,31);
        var original=(TerrainInputFingerprint.Reader)(x,y,z)->y==80?"minecraft:grass_block[snowy=false]":"minecraft:air";
        var changed=(TerrainInputFingerprint.Reader)(x,y,z)->
            x==23&&y==80&&z==20?"minecraft:dirt":original.state(x,y,z);
        String before=scan(bounds,256,original);
        assertEquals(before,scan(bounds,17,original),"Slice boundaries must not change input identity");
        var binding=new TerrainInputTrace.Binding(BASE,bounds,before);
        assertEquals(original.state(23,81,20),changed.state(23,81,20),"The planned AIR effect cell is unchanged");
        String after=scan(bounds,256,changed);
        assertNotEquals(before,after);
        assertThrows(IllegalStateException.class,()->binding.requireTerrain(after));
        assertDoesNotThrow(()->binding.requireTerrain(before));
    }

    @Test void incompleteCensusAndOverflowingOrInvalidFootprintsDoNotProduceAnIdentity(){
        var bounds=new TerrainInputFingerprint.Bounds(0,0,0,1,2,1);
        var cursor=new TerrainInputFingerprint.Cursor(bounds);
        assertEquals(1,cursor.advance(1,(x,y,z)->"minecraft:air"));
        assertThrows(IllegalStateException.class,cursor::fingerprint);
        assertThrows(IllegalArgumentException.class,()->cursor.advance(0,(x,y,z)->"minecraft:air"));
        assertThrows(IllegalArgumentException.class,()->new TerrainInputFingerprint.Bounds(1,0,0,0,1,0));
        assertThrows(ArithmeticException.class,()->new TerrainInputFingerprint.Bounds(
            Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE));
    }

    @Test void newWriterRequiresBindingBeforeSealAndMandatoryTraceCannotBeDroppedOrCorrupted()throws Exception{
        var bounds=new TerrainInputFingerprint.Bounds(0,0,0,1,2,1);
        var binding=new TerrainInputTrace.Binding(BASE,bounds,scan(bounds,2,(x,y,z)->"minecraft:air"));
        var writer=PagedPlanStore.begin(root,UUID.randomUUID(),PassId.FLORA,"test:area",
            "minecraft:overworld","proutlost:river_test",BASE,"worldprep-v2",
            TerrainInputTrace.PLANNER_VERSION,256,CODEC);
        writer.appendSelected(7,PlanSelectionTrace.Selection.DIRECT);
        assertThrows(IOException.class,()->writer.finish(1));
        assertEquals(PlanManifest.State.WRITING,PagedPlanStore.readManifest(writer.directory()).state());
        var wrongBase=new TerrainInputTrace.Binding(PlanFingerprint.of("other-profile").value(),bounds,binding.terrainDigest());
        assertThrows(IllegalArgumentException.class,()->writer.bindTerrainInput(wrongBase));
        writer.bindTerrainInput(binding);
        var publication=writer.finish(1).orElseThrow();
        assertEquals(TerrainInputTrace.PLAN_FORMAT_VERSION,publication.manifest().planFormatVersion());
        assertEquals(binding,TerrainInputTrace.read(publication.directory(),publication.manifest()).orElseThrow());
        assertEquals(1,PlanSelectionTrace.read(publication.directory(),publication.manifest()).orElseThrow().entries());
        Path trace=TerrainInputTrace.path(publication.directory());byte[] exact=Files.readAllBytes(trace);
        Files.delete(trace);
        assertThrows(IOException.class,()->PagedPlanStore.readManifest(publication.directory()));
        Files.write(trace,exact);
        assertEquals(publication.manifest(),PagedPlanStore.readManifest(publication.directory()));
        byte[] corrupt=exact.clone();corrupt[corrupt.length-1]^=1;Files.write(trace,corrupt);
        assertThrows(IOException.class,()->PagedPlanStore.readManifest(publication.directory()));
    }

    @Test void traceCannotBeReboundToAnotherOwnerOrSemanticContext()throws Exception{
        var bounds=new TerrainInputFingerprint.Bounds(0,0,0,0,1,0);
        var binding=new TerrainInputTrace.Binding(BASE,bounds,scan(bounds,1,(x,y,z)->"minecraft:stone"));
        var writer=PagedPlanStore.begin(root,UUID.randomUUID(),PassId.GEOLOGY,"test:area",
            "minecraft:overworld","proutlost:river_test",BASE,"worldprep-v2",
            TerrainInputTrace.PLANNER_VERSION,256,CODEC);
        writer.bindTerrainInput(binding);
        var publication=writer.finish(1).orElseThrow();var m=publication.manifest();
        var another=PlanManifest.create(m.planFormatVersion(),UUID.randomUUID(),m.passId(),m.areaId(),m.dimension(),
            m.profile(),m.semanticInputDigest(),m.engineVersion(),m.plannerVersion(),m.state(),m.pages());
        assertThrows(IOException.class,()->TerrainInputTrace.read(publication.directory(),another));
        var changed=new TerrainInputTrace.Binding(BASE,bounds,PlanFingerprint.of("changed-terrain").value());
        assertThrows(IOException.class,()->TerrainInputTrace.publish(publication.directory(),m,changed));
    }

    @Test void existingSelectionPlansRemainReadableAndVersionFourDoesNotMeanAnUnboundLegacyPlan()throws Exception{
        var writer=PagedPlanStore.begin(root,UUID.randomUUID(),PassId.FLORA,"test:legacy",
            "minecraft:overworld","proutlost:river_test",BASE,"worldprep-v2",
            PlanSelectionTrace.PLANNER_VERSION,256,CODEC);
        writer.appendSelected(5,PlanSelectionTrace.Selection.DIRECT);
        var publication=writer.finish(1).orElseThrow();
        assertEquals(DurablePageStore.FORMAT_VERSION,publication.manifest().planFormatVersion());
        assertTrue(TerrainInputTrace.read(publication.directory(),publication.manifest()).isEmpty(),
            "A terrain binding must never be invented for legacy data");
        var values=new ArrayList<Integer>();
        PagedPlanStore.read(publication.directory(),publication.manifest(),CODEC,values::add);
        assertEquals(List.of(5),values);
        assertThrows(IllegalArgumentException.class,()->PlanManifest.create(TerrainInputTrace.PLAN_FORMAT_VERSION,
            UUID.randomUUID(),PassId.FLORA,"test:legacy","minecraft:overworld","proutlost:river_test",
            BASE,"worldprep-v2",PlanSelectionTrace.PLANNER_VERSION,PlanManifest.State.SEALED,List.of()));
    }
}
