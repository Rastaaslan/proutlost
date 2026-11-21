package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NativeVerifiedStructureCatalogTest {
    @TempDir Path dir;
    private NativeStructurePass.Plan plan() {
        var area = new WorldPrepSavedData.Area("test:contra","minecraft:overworld",0,0,63,63);
        return new NativeStructurePass.Plan(1,UUID.fromString("57b9360f-1d48-4525-8ace-cda276ef330a"),
                "minecraft:overworld",area,"proutlost:worldprep/custom_hut",
                NativeStructurePass.ADAPTER,"a".repeat(64),"NONE","NONE","frozen",
                List.of(new NativeStructurePass.Cell(40,70,50,"minecraft:air","minecraft:stone")),
                List.of());
    }
    @Test void onlyVerifiedPlacementYieldsLocationRecord() throws Exception {
        var store=new PhysicalStore(dir.resolve("positive"));
        var p=plan();
        store.write("plan",p);
        store.write("disk-verified",new NativeStructureAudit.Receipt(1,p.operation(),
                PhysicalStore.sha(store.readText("plan")),"b".repeat(64),List.of("2,3"),"PLACED_VERIFIED"));
        var r=NativeVerifiedStructureCatalog.publish(store,
            java.util.Set.of("proutlost:structure","proutlost:village","proutlost:explorable"));
        assertEquals("PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED",r.status());
        assertEquals(40,r.minX());assertEquals(41,r.maxXExclusive());
        assertEquals(70,r.minY());assertEquals(71,r.maxYExclusive());
        assertEquals(50,r.minZ());assertEquals(51,r.maxZExclusive());
        assertTrue(store.exists("director-location-candidate"));
        assertTrue(r.tags().contains("proutlost:village"));
        assertTrue(r.tags().contains("proutlost:explorable"));
        assertEquals(1,r.anchors().size());
        assertTrue(r.anchors().getFirst().tags().contains("proutlost:anchor_center"));
        assertEquals(r,NativeVerifiedStructureCatalog.publish(store,
            java.util.Set.of("proutlost:structure","proutlost:village","proutlost:explorable")));
    }
    @Test void noDiskReceiptMeansNoCatalog() throws Exception {
        var store=new PhysicalStore(dir.resolve("missing"));
        store.write("plan",plan());
        assertThrows(java.io.IOException.class,()->NativeVerifiedStructureCatalog.publish(store));
        assertFalse(store.exists("director-location-candidate"));
    }
    @Test void forgedOperationIsRejected() throws Exception {
        var store=new PhysicalStore(dir.resolve("mismatch"));
        store.write("plan",plan());
        store.write("disk-verified",new NativeStructureAudit.Receipt(1,UUID.randomUUID(),
                PhysicalStore.sha(store.readText("plan")),"b".repeat(64),List.of(),"PLACED_VERIFIED"));
        assertThrows(java.io.IOException.class,()->NativeVerifiedStructureCatalog.publish(store));
    }
    @Test void rollbackBlocksCatalog() throws Exception {
        var store=new PhysicalStore(dir.resolve("rollback"));
        store.write("plan",plan());
        store.write("rollback-intent","STOP");
        assertThrows(java.io.IOException.class,()->NativeVerifiedStructureCatalog.publish(store));
    }
}
