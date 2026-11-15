package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.biome.v2.TerrainSemantic;
import fr.proutlost.worldprep.structure.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeRegistryStructureContractTest {
    @Test void realFootprintMustFitReservedEnvelope() {
        var b=new NativeRegistryStructurePass.Bounds(-4,40,-6,21,95,26);
        var reserved=new StructureInstancePlan.Bounds(-16,20,-16,32,150,32);
        assertTrue(b.inside(reserved));
        assertFalse(b.inside(new StructureInstancePlan.Bounds(-3,20,-16,32,150,32)));
    }
    private static StructureCapability dungeon() {
        return new StructureCapability("betterdungeons:small_dungeon",
            StructureSupportStatus.EXACT_JIGSAW,NativeRegistryStructurePass.ADAPTER,
            64,80,64,8,96,3,Set.of(TerrainSemantic.LOWLAND));
    }
    @Test void requestOptInIsBoundedAndDefaultNativeModeIsNotForced() {
        var id=dungeon().structureId();
        var request=new WorldPrepStructureModule.Request(1,UUID.randomUUID(),"proutlost:test_area",
            1234L,"a".repeat(64),5,2,List.of(dungeon()),
            List.of(new AutomaticStructurePlanner.Candidate(id,50,80,50,100,TerrainSemantic.LOWLAND,1)),
            Map.of(id,Set.of("proutlost:structure","proutlost:dungeon")),
            Map.of(id,NativeRegistryStructurePass.Conditions.OVERRIDE_BIOME));
        assertEquals(NativeRegistryStructurePass.Conditions.OVERRIDE_BIOME,
            request.nativeConditionsByStructure().get(id));
        assertNull(request.directorWorldId());
        assertThrows(IllegalArgumentException.class,()->new WorldPrepStructureModule.Request(
            1,UUID.randomUUID(),"proutlost:test_area",1234L,"a".repeat(64),5,2,
            List.of(dungeon()),List.of(new AutomaticStructurePlanner.Candidate(
                id,50,80,50,100,TerrainSemantic.LOWLAND,1)),
            Map.of(id,Set.of("proutlost:structure")),
            Map.of("betterdungeons:unknown",NativeRegistryStructurePass.Conditions.FORCED)));
    }
    @Test void nativePublisherRequiresIndependentDiskReceipt(@TempDir Path root)throws Exception {
        var store=new PhysicalStore(root);
        assertThrows(java.io.IOException.class,()->NativeVerifiedStructureCatalog.publishNative(store,
            Set.of("proutlost:structure")));
    }
    @Test void g0GeneratesCanonicalDigestAndValidIdentity() {
        UUID location=UUID.fromString("2ac140b9-67e9-4cc5-a87c-0504cf5423ea");
        UUID operation=UUID.fromString("6c56f908-b91d-42ab-811c-bc605fd93478");
        UUID anchor=UUID.fromString("158cf196-ddfe-4263-9ac9-b9c0109ba128");
        var record=new NativeVerifiedStructureCatalog.NativeLocationRecord(1,location,operation,
            "minecraft:overworld","betterdungeons:small_dungeon",
            "a".repeat(64),"b".repeat(64),-4,40,-5,32,90,34,
            Set.of("proutlost:structure","proutlost:dungeon"),
            List.of(new NativeVerifiedStructureCatalog.Anchor(anchor,10,41,10,
                Set.of("proutlost:anchor_center"))),
            "PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED");
        var g0=DirectorG0LocationExport.build(
            UUID.fromString("4f7e4295-7f25-42d2-a0c7-526dd8ae108b"),1L,List.of(record));
        String digest=g0.get("export_digest").getAsString();
        assertEquals(64,digest.length());
        assertEquals("1",g0.get("export_revision").getAsString());
        assertEquals("betterdungeons:small_dungeon/"+operation,
            g0.getAsJsonArray("locations").get(0).getAsJsonObject().get("external_id").getAsString());
        var withoutDigest=g0.deepCopy();
        withoutDigest.remove("export_digest");
        assertEquals(DirectorG0LocationExport.sha256(
            DirectorG0LocationExport.canonical(withoutDigest)),digest);
        assertTrue(DirectorG0LocationExport.canonical(g0).contains("\"max_x_exclusive\":32"));
    }
    @Test void g0CannotClaimIntentAsVerified() {
        var record=new NativeVerifiedStructureCatalog.NativeLocationRecord(1,
            UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld",
            "betterdungeons:small_dungeon","a".repeat(64),"b".repeat(64),
            1,10,1,20,40,20,Set.of("proutlost:structure"),List.of(),
            "PLACEMENT_INTENT_ONLY");
        assertThrows(IllegalArgumentException.class,()->DirectorG0LocationExport.build(
            UUID.randomUUID(),1L,List.of(record)));
    }
}
