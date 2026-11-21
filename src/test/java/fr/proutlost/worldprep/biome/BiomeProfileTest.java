package fr.proutlost.worldprep.biome;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BiomeProfileTest {
    private static JsonObject fixture(long seed,String id,String fallback){
        var root=new JsonObject();root.addProperty("schema",1);root.addProperty("id","proutlost:river_test");root.addProperty("seed",seed);
        var palettes=new JsonObject();
        for(var name:BiomeProfile.REQUIRED_PALETTES){
            var candidate=new JsonObject();candidate.addProperty("id",id);if(fallback!=null)candidate.addProperty("fallback",fallback);
            var entries=new JsonArray();entries.add(candidate);palettes.add(name,entries);
        }
        root.add("biomes",palettes);return root;
    }
    @Test void shippedRiverProfileRetainsDeclaredSeedAndCandidateContent()throws Exception{
        try(var stream=getClass().getClassLoader().getResourceAsStream("data/proutlost/worldprep/profiles/river_test.json")){
            assertNotNull(stream);var profile=BiomeProfile.parse(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
            assertEquals(7640891576956012809L,profile.seed());
            assertTrue(profile.palettes().get("open_lowland").stream().anyMatch(x->x.id().equals("minecraft:meadow")));
            profile.resolve(id->id.startsWith("minecraft:")).requireValid();
        }
    }
    @Test void JSONOnlyVariantControlsActualSeedAndEveryPaletteChoice(){
        var profile=BiomeProfile.parse(fixture(777,"minecraft:desert",null).toString());
        assertEquals(777,profile.seed());var resolved=profile.resolve(Set.of("minecraft:desert")::contains);resolved.requireValid();
        for(var palette:BiomeProfile.REQUIRED_PALETTES)for(long selector:new long[]{Long.MIN_VALUE,-1,0,777,Long.MAX_VALUE})
            assertEquals("minecraft:desert",resolved.choose(palette,selector).resolvedId());
    }
    @Test void multipleConfiguredCandidatesAreDeterministicWithoutHardcodedDefaults(){
        var json=fixture(-27,"minecraft:desert",null);var second=new JsonObject();second.addProperty("id","minecraft:badlands");
        json.getAsJsonObject("biomes").getAsJsonArray("open_lowland").add(second);
        var first=BiomeProfile.parse(json.toString()).resolve(id->true);var secondRead=BiomeProfile.parse(json.toString()).resolve(id->true);
        first.requireValid();secondRead.requireValid();var seen=new HashSet<String>();
        for(long selector=-32;selector<=32;selector++){
            var choice=first.choose("open_lowland",selector);seen.add(choice.resolvedId());
            assertEquals(choice,secondRead.choose("open_lowland",selector));
        }
        assertEquals(Set.of("minecraft:desert","minecraft:badlands"),seen);
    }
    @Test void missingPrimaryUsesExplicitRuntimeRegistryFallbackAndReportsIt(){
        var resolved=BiomeProfile.parse(fixture(777,"missing_mod:forest","minecraft:desert").toString())
            .resolve(Set.of("minecraft:desert")::contains);resolved.requireValid();
        assertEquals(BiomeProfile.REQUIRED_PALETTES.size(),resolved.counts().get(BiomeCandidate.Status.FALLBACK));
        assertEquals(0,resolved.counts().get(BiomeCandidate.Status.MISSING));
        for(var palette:BiomeProfile.REQUIRED_PALETTES){
            var choice=resolved.choose(palette,0);assertEquals("minecraft:desert",choice.resolvedId());
            assertEquals(BiomeCandidate.Status.FALLBACK,choice.status());
        }
    }
    @Test void unresolvedPrimaryAndFallbackRefuseValidationBeforeAnyChoiceCanApply(){
        var resolved=BiomeProfile.parse(fixture(777,"missing_mod:forest","missing_mod:fallback").toString()).resolve(id->false);
        assertEquals(BiomeProfile.REQUIRED_PALETTES.size(),resolved.counts().get(BiomeCandidate.Status.MISSING));
        assertThrows(IllegalStateException.class,resolved::requireValid);
        assertThrows(IllegalStateException.class,()->resolved.choose("coastal",0));
    }
    @Test void configuredButUnselectedMissingPaletteIsNotSilentlyIgnored(){
        var json=fixture(777,"minecraft:desert",null);var candidate=new JsonObject();candidate.addProperty("id","missing_mod:unselected");
        var list=new JsonArray();list.add(candidate);json.getAsJsonObject("biomes").add("future_wetland",list);
        var resolved=BiomeProfile.parse(json.toString()).resolve(Set.of("minecraft:desert")::contains);
        assertEquals(1,resolved.counts().get(BiomeCandidate.Status.MISSING));assertThrows(IllegalStateException.class,resolved::requireValid);
    }
    @Test void registrySnapshotIncludesDormantFallbackReferences(){
        var json=fixture(777,"minecraft:desert","qa:alternative").toString();
        var first=BiomeProfile.parse(json).resolve(Set.of("minecraft:desert")::contains);
        var second=BiomeProfile.parse(json).resolve(Set.of("minecraft:desert","qa:alternative")::contains);
        assertEquals(first.choose("coastal",0),second.choose("coastal",0));
        assertNotEquals(first.registryState(),second.registryState());assertFalse(first.registryState().get("qa:alternative"));
    }
    @Test void missingClassifierPaletteCannotBeReportedValidEvenWhenEveryConfiguredCandidateExists(){
        var json=fixture(777,"minecraft:desert",null);json.getAsJsonObject("biomes").remove("coastal");
        var resolved=BiomeProfile.parse(json.toString()).resolve(id->true);
        assertEquals(0,resolved.counts().get(BiomeCandidate.Status.MISSING));
        assertThrows(IllegalStateException.class,resolved::requireValid);
    }
    @Test void invalidSchemaSeedCandidateAndExistingByteLimitAreRejected(){
        var schema=fixture(777,"minecraft:desert",null);schema.addProperty("schema",2);
        assertThrows(IllegalArgumentException.class,()->BiomeProfile.parse(schema.toString()));
        var fraction=fixture(777,"minecraft:desert",null);fraction.addProperty("seed",1.5);
        assertThrows(IllegalArgumentException.class,()->BiomeProfile.parse(fraction.toString()));
        var overflow=fixture(777,"minecraft:desert",null);overflow.add("seed",JsonParser.parseString("9223372036854775808"));
        assertThrows(IllegalArgumentException.class,()->BiomeProfile.parse(overflow.toString()));
        assertThrows(IllegalArgumentException.class,()->BiomeProfile.parse(fixture(777,"invalid id",null).toString()));
        assertThrows(IllegalArgumentException.class,()->BiomeProfile.parse(" ".repeat(65537)));
    }
}
