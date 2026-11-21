package fr.proutlost.worldprep;

import fr.proutlost.worldprep.ecology.FaunaSuitability;
import fr.proutlost.worldprep.validation.SpawnAccess;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class FaunaAndSpawnTest {
    private final Set<String> registry=Set.copyOf(FaunaSuitability.SPECIES);
    private FaunaSuitability.Evidence ground(String floor,int clearance,boolean protectedColumn){
        return new FaunaSuitability.Evidence(10,70,20,"OPEN_GRASSLAND","minecraft:plains",floor,0,clearance,protectedColumn);
    }
    @Test void groundRulesRequireHabitatClearanceKnownSupportAndRegistry(){
        var good=FaunaSuitability.evaluate(ground("minecraft:grass_block",2,false),registry);
        assertEquals("SUITABLE_BY_RULE",good.species().get("minecraft:cow"));
        assertEquals("NO_RUNTIME_OR_INITIAL_SPAWNING",good.populationPolicy());
        assertEquals("UNSUITABLE_OR_UNKNOWN_SUPPORT",FaunaSuitability.evaluate(ground("unknown:floor",2,false),registry).species().get("minecraft:cow"));
        assertEquals("UNSUITABLE_CLEARANCE_OR_WATER",FaunaSuitability.evaluate(ground("minecraft:grass_block",1,false),registry).species().get("minecraft:cow"));
        assertEquals("UNSUITABLE_PROTECTED",FaunaSuitability.evaluate(ground("minecraft:grass_block",2,true),registry).species().get("minecraft:cow"));
        assertEquals("MISSING_REGISTRY_ENTRY",FaunaSuitability.evaluate(ground("minecraft:grass_block",2,false),Set.of()).species().get("minecraft:cow"));
        assertEquals(good,FaunaSuitability.evaluate(ground("minecraft:grass_block",2,false),registry));
    }
    @Test void fishRulesDistinguishRiverOceanUnknownBiomeAndDepth(){
        var river=new FaunaSuitability.Evidence(10,60,20,"SHALLOW_MARINE","minecraft:river","minecraft:gravel",3,0,false);
        assertEquals("SUITABLE_BY_RULE",FaunaSuitability.evaluate(river,registry).species().get("minecraft:salmon"));
        assertEquals("UNSUPPORTED_BIOME",FaunaSuitability.evaluate(river,registry).species().get("minecraft:cod"));
        var unknown=new FaunaSuitability.Evidence(10,60,20,"MARINE","mod:unknown","minecraft:sand",3,0,false);
        assertEquals("UNSUPPORTED_BIOME",FaunaSuitability.evaluate(unknown,registry).species().get("minecraft:cod"));
        var shallow=new FaunaSuitability.Evidence(10,60,20,"SHALLOW_MARINE","minecraft:ocean","minecraft:sand",1,0,false);
        assertEquals("UNSUITABLE_WATER_DEPTH",FaunaSuitability.evaluate(shallow,registry).species().get("minecraft:cod"));
    }
    @Test void localSpawnRequiresConnectedSafeColumnsAndExactFeetHeight(){
        var columns=new ArrayList<SpawnAccess.Column>();for(int z=0;z<4;z++)for(int x=0;x<4;x++)columns.add(new SpawnAccess.Column(x,z,70,true));
        var spawn=new SpawnAccess.Position(0,71,0);var access=SpawnAccess.validate(spawn,columns);
        assertEquals("LOCAL_ACCESS_VERIFIED_GLOBAL_ROUTE_UNCHECKED",access.status());assertEquals(16,access.reachable().size());
        assertEquals("UNSAFE_OR_UNKNOWN",SpawnAccess.validate(new SpawnAccess.Position(0,75,0),columns).status());
        columns.set(1,new SpawnAccess.Column(1,0,74,true));columns.set(4,new SpawnAccess.Column(0,1,70,false));
        assertEquals(1,SpawnAccess.validate(spawn,columns).reachable().size());
        assertEquals("INSUFFICIENT_LOCAL_ACCESS",SpawnAccess.validate(spawn,columns).status());
        assertEquals("OUTSIDE_TILE_UNCHECKED",SpawnAccess.validate(new SpawnAccess.Position(-1,71,0),columns).status());
    }
}
