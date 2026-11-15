package fr.proutlost.worldprep.physical;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.proutlost.worldprep.ecology.VegetationPlanner;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldPrepStructureModuleJsonTest {
    private static JsonObject legacy() {
        return JsonParser.parseString("""
            {
              "schema":1,"batch":"e70d53ef-e32c-4583-ac1d-01594f2a1ddb",
              "area":"proutlost:standalone","worldSeed":27594267,
              "frozenConfigSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "globalLimit":1,"perRegionLimit":1,
              "catalog":[{"structureId":"proutlost:worldprep/village_hut",
                "status":"EXACT_TEMPLATE","adapterFingerprint":"fixture-template",
                "width":7,"height":6,"depth":7,"clearance":3,
                "minimumSpacing":48,"rarityDivisor":1,"eligibleTerrain":["LOWLAND"]}],
              "candidates":[{"structureId":"proutlost:worldprep/village_hut",
                "x":100,"y":70,"z":100,"suitability":100,"terrain":"LOWLAND","regionId":1}],
              "directorTagsByStructure":{"proutlost:worldprep/village_hut":["proutlost:structure"]},
              "standaloneArea":{"id":"proutlost:standalone","dimension":"minecraft:overworld",
                "minX":0,"minZ":0,"maxX":200,"maxZ":200}
            }
            """).getAsJsonObject();
    }
    private static WorldPrepStructureModule.Request parse(JsonObject json)throws IOException {
        return WorldPrepStructureModule.parseRequest(json.toString());
    }
    @Test void schema1SixFieldAreaGetsOnlyHistoricalDefaults()throws Exception {
        var json=legacy();var request=parse(json);var area=request.standaloneArea();
        assertEquals(new WorldPrepSavedData.Area("proutlost:standalone","minecraft:overworld",0,0,200,200),area);
        assertEquals(WorldPrepSavedData.AreaMask.FULL_AREA,area.mask());
        assertEquals(VegetationPlanner.TreeMode.NORMAL,area.treeMode());
        assertFalse(json.getAsJsonObject("standaloneArea").has("mask"));
        assertFalse(json.getAsJsonObject("standaloneArea").has("treeMode"));
        assertEquals(1,request.catalog().size());assertEquals(100,request.candidates().getFirst().x());
    }
    @Test void eachMissingFieldDefaultsWithoutReplacingThePresentRichField()throws Exception {
        var withMask=legacy();withMask.getAsJsonObject("standaloneArea").addProperty("mask","LAND");
        assertEquals(WorldPrepSavedData.AreaMask.LAND,parse(withMask).standaloneArea().mask());
        assertEquals(VegetationPlanner.TreeMode.NORMAL,parse(withMask).standaloneArea().treeMode());
        var withMode=legacy();withMode.getAsJsonObject("standaloneArea").addProperty("treeMode","MANUAL_ONLY");
        assertEquals(WorldPrepSavedData.AreaMask.FULL_AREA,parse(withMode).standaloneArea().mask());
        assertEquals(VegetationPlanner.TreeMode.MANUAL_ONLY,parse(withMode).standaloneArea().treeMode());
    }
    @Test void richLandAreaAndEveryTreeModeSurviveDecodeExactly()throws Exception {
        for(var mode:VegetationPlanner.TreeMode.values()) {
            var json=legacy();var area=json.getAsJsonObject("standaloneArea");
            area.addProperty("mask","LAND");area.addProperty("treeMode",mode.name());
            assertEquals(new WorldPrepSavedData.Area("proutlost:standalone","minecraft:overworld",0,0,200,200,
                WorldPrepSavedData.AreaMask.LAND,mode),parse(json).standaloneArea());
        }
    }
    @Test void explicitNullMaskOrTreeModeNeverReceivesADefault() {
        for(var key:new String[]{"mask","treeMode"}) {
            var json=legacy();json.getAsJsonObject("standaloneArea").add(key,null);
            assertThrows(IOException.class,()->parse(json),key);
        }
    }
    @Test void malformedMaskOrTreeModeIsRejected() {
        for(var key:new String[]{"mask","treeMode"}) {
            for(var invalid:new String[]{"\"UNKNOWN\"","\"normal\"","17","true","[]","{}"}) {
                var json=legacy();json.getAsJsonObject("standaloneArea").add(key,JsonParser.parseString(invalid));
                assertThrows(IOException.class,()->parse(json),key+"="+invalid);
            }
        }
    }
    @Test void unsupportedOrMalformedSchemaAndInvalidGeometryStillReject() {
        for(var schema:new String[]{"2","0","null","\"1\"","1.5","{}"}) {
            var json=legacy();json.add("schema",JsonParser.parseString(schema));
            assertThrows(IOException.class,()->parse(json),"schema="+schema);
        }
        var json=legacy();json.getAsJsonObject("standaloneArea").addProperty("maxX",-1);
        assertThrows(IOException.class,()->parse(json));
    }
    @Test void unsupportedTerrainMasksFailBeforeStandalonePlacementInsteadOfBeingIgnored()throws Exception {
        for(var mask:WorldPrepSavedData.AreaMask.values()) {
            var area=new WorldPrepSavedData.Area("a","minecraft:overworld",0,0,200,200,
                mask,VegetationPlanner.TreeMode.DENSE);
            if(mask==WorldPrepSavedData.AreaMask.FULL_AREA)
                assertDoesNotThrow(()->WorldPrepStructureModule.requireSupportedAreaMask(area));
            else {
                var failure=assertThrows(IOException.class,()->WorldPrepStructureModule.requireSupportedAreaMask(area));
                assertTrue(failure.getMessage().contains("FULL_AREA"));
            }
        }
    }
}
