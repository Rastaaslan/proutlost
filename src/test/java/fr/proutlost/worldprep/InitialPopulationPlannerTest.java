package fr.proutlost.worldprep;

import fr.proutlost.worldprep.ecology.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InitialPopulationPlannerTest {
    final UUID batch = new UUID(1, 2);
    final String config = "a".repeat(64);
    final Set<String> registry = Set.of("minecraft:cow");
    final InitialPopulationPlanner.Limits limits = new InitialPopulationPlanner.Limits(10, 5, 4, 1, 8);
    InitialPopulationPlanner.Candidate cow(int x, boolean protectedColumn) {
        return new InitialPopulationPlanner.Candidate("minecraft:cow", new FaunaSuitability.Evidence(x, 75, 0,
                "OPEN_GRASSLAND", "minecraft:plains", "minecraft:grass_block", 0, 2, protectedColumn), 0);
    }
    @Test void validFaunaStableDespiteInputOrdering() {
        var a = cow(0, false); var b = cow(32, false);
        var first = InitialPopulationPlanner.plan(batch,config,4,List.of(a,b),List.of(),Set.of(),registry,limits);
        assertEquals(2, first.size());
        assertEquals(first, InitialPopulationPlanner.plan(batch,config,4,List.of(b,a),List.of(),Set.of(),registry,limits));
    }
    @Test void densityIncludesExistingEntities() {
        assertTrue(InitialPopulationPlanner.plan(batch,config,4,List.of(cow(4,false)),List.of(cow(0,false)),Set.of(),registry,limits).isEmpty());
    }
    @Test void restartDoesNotReplaceDeadIssuedAnimals() {
        var original = InitialPopulationPlanner.plan(batch,config,4,List.of(cow(0,false)),List.of(),Set.of(),registry,limits);
        assertTrue(InitialPopulationPlanner.plan(batch,config,4,List.of(cow(0,false),cow(32,false)),List.of(),Set.of(original.getFirst().entity()),registry,limits).isEmpty());
    }
    @Test void missingRegistryAndProtectionRefused() {
        assertTrue(InitialPopulationPlanner.plan(batch,config,4,List.of(cow(0,false)),List.of(),Set.of(),Set.of(),limits).isEmpty());
        assertTrue(InitialPopulationPlanner.plan(batch,config,4,List.of(cow(0,true)),List.of(),Set.of(),registry,limits).isEmpty());
    }
    @Test void globalAndRegionCapsAreFinite() {
        var input = new ArrayList<InitialPopulationPlanner.Candidate>();
        for(int x=0;x<1000;x+=32) input.add(cow(x,false));
        assertEquals(4, InitialPopulationPlanner.plan(batch,config,4,input,List.of(),Set.of(),registry,limits).size());
    }
    @org.junit.jupiter.api.Test void discoveredHabitatDrivesExtensibleRegistryPolicy()throws Exception {
        var sample=new fr.proutlost.worldprep.ecology.HabitatDiscovery.Sample(1,2,70,0,false,false);
        var habitat=fr.proutlost.worldprep.ecology.HabitatDiscovery.classify(java.util.List.of(sample),false).getFirst();
        var evidence=new fr.proutlost.worldprep.ecology.FaunaSuitability.Evidence(1,71,2,habitat.habitat(),"example:biome","example:floor",0,2,false);
        var policy=new fr.proutlost.worldprep.ecology.FaunaSuitability.InitialPolicy("example:animal",java.util.Set.of("OPEN_GRASSLAND"),java.util.Set.of("example:biome"),java.util.Set.of("example:floor"),0,0,2);
        org.junit.jupiter.api.Assertions.assertEquals("SUITABLE_BY_RULE",fr.proutlost.worldprep.ecology.FaunaSuitability.evaluateInitial(evidence,java.util.Set.of("example:animal"),policy));
        org.junit.jupiter.api.Assertions.assertEquals("MISSING_REGISTRY_ENTRY",fr.proutlost.worldprep.ecology.FaunaSuitability.evaluateInitial(evidence,java.util.Set.of(),policy));
        var forest=new fr.proutlost.worldprep.ecology.FaunaSuitability.Evidence(1,71,2,"FOREST","example:biome","example:floor",0,2,false);
        org.junit.jupiter.api.Assertions.assertEquals("UNSUPPORTED_HABITAT",fr.proutlost.worldprep.ecology.FaunaSuitability.evaluateInitial(forest,java.util.Set.of("example:animal"),policy));
    }
}
