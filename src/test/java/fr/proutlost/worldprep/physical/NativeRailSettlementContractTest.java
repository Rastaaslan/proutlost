package fr.proutlost.worldprep.physical;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Rail admissibility/data binding only. Actual reciprocal-neighbor geometry and
 * disk/live equality are enforced by the native candidate reconciliation. */
class NativeRailSettlementContractTest {
    @Test void onlyFlatVanillaRailShapeCanBeReconciled() {
        String before="minecraft:rail[shape=east_west,waterlogged=false]";
        assertTrue(NativeRegistryStructureAudit.flatRailShapeOnly(before,"minecraft:rail[shape=south_west,waterlogged=false]"));
        assertFalse(NativeRegistryStructureAudit.flatRailShapeOnly(before,"minecraft:rail[shape=south_west,waterlogged=true]"));
        assertFalse(NativeRegistryStructureAudit.flatRailShapeOnly(before,"minecraft:air"));
        assertFalse(NativeRegistryStructureAudit.flatRailShapeOnly(before,"minecraft:powered_rail[shape=north_south,waterlogged=false]"));
        assertFalse(NativeRegistryStructureAudit.flatRailShapeOnly(before,"minecraft:rail[shape=ascending_east,waterlogged=false]"));
        assertFalse(NativeRegistryStructureAudit.flatRailShapeOnly(before,"minecraft:rail[shape=south_west,waterlogged=false,powered=false]"));
        assertFalse(NativeRegistryStructureAudit.flatRailShapeOnly(before,before));
    }
    @Test void malformedOrIncompleteStateCannotAuthorizeSettlement() {
        assertFalse(NativeRegistryStructureAudit.flatRailShapeOnly("minecraft:rail[shape=east_west]","minecraft:rail[shape=south_west]"));
        assertFalse(NativeRegistryStructureAudit.flatRailShapeOnly(null,"minecraft:rail[shape=south_west,waterlogged=false]"));
        assertFalse(NativeRegistryStructureAudit.flatRailShapeOnly("minecraft:rail[shape=east_west,waterlogged=false,shape=north_south]","minecraft:rail[shape=south_west,waterlogged=false]"));
    }
    @Test void publishedReceiptHashIncludesLiteralSettlementAndNeighborEvidence() {
        var neighbor=new NativeRegistryStructureAudit.RailNeighbor(2704,18,1583,"west",
            "minecraft:rail[shape=east_west,waterlogged=false]","minecraft:rail[shape=east_west,waterlogged=false]",
            "minecraft:gravel","minecraft:gravel");
        var change=new NativeRegistryStructureAudit.SettledRail(2705,18,1583,
            "minecraft:rail[shape=east_west,waterlogged=false]","minecraft:rail[shape=south_west,waterlogged=false]",
            NativeRegistryStructureAudit.SETTLEMENT_RULE,List.of(neighbor));
        var id=UUID.fromString("ebbd4f18-ddf0-3aa6-b505-56b0df3ae1af");
        var exact=new NativeRegistryStructureAudit.Receipt(1,id,"bettermineshafts:mineshaft_spruce","a".repeat(64),63,10984,List.of("169,98"),"PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED");
        var reconciled=new NativeRegistryStructureAudit.Receipt(1,id,"bettermineshafts:mineshaft_spruce","a".repeat(64),63,10984,List.of("169,98"),"PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED",NativeRegistryStructureAudit.SETTLEMENT_RULE,List.of(change));
        var json=new Gson();String payload=json.toJson(reconciled);
        assertNotEquals(PhysicalStore.sha(json.toJson(exact)),PhysicalStore.sha(payload));
        assertTrue(payload.contains("east_west"));assertTrue(payload.contains("south_west"));assertTrue(payload.contains("supportDisk"));
        assertEquals(1,json.fromJson(payload,NativeRegistryStructureAudit.Receipt.class).settledRails().size());
    }
}
