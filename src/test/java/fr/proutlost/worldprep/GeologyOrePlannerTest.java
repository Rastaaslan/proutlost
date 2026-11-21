package fr.proutlost.worldprep;

import fr.proutlost.worldprep.geology.GeologyPlanner;
import fr.proutlost.worldprep.ore.OrePlanner;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GeologyOrePlannerTest {
    @Test void provincesAreDeterministicBroadAndNegativeCoordinateSafe(){var a=GeologyPlanner.province(42,-129,-40,65);assertEquals(a,GeologyPlanner.province(42,-129,-40,65));assertEquals(a,GeologyPlanner.province(42,-190,-40,65));}
    @Test void geologyIsDeterministicAndUsesSupportedStates(){String value=GeologyPlanner.replacement(7,12,-20,-33,GeologyPlanner.Province.TUFF_RICH);assertEquals(value,GeologyPlanner.replacement(7,12,-20,-33,GeologyPlanner.Province.TUFF_RICH));assertTrue(Set.of("minecraft:stone","minecraft:deepslate","minecraft:granite","minecraft:diorite","minecraft:andesite","minecraft:tuff").contains(value));}
    @Test void oreRejectsRangeHostProtectionAndBlockEntities(){var p=new OrePlanner.Profile("minecraft:diamond_ore",-64,16,1,9,Set.of("minecraft:stone"));assertTrue(OrePlanner.shouldPlace(p,1,0,0,0,"minecraft:stone",false,false));assertFalse(OrePlanner.shouldPlace(p,1,0,17,0,"minecraft:stone",false,false));assertFalse(OrePlanner.shouldPlace(p,1,0,0,0,"mod:machine",false,false));assertFalse(OrePlanner.shouldPlace(p,1,0,0,0,"minecraft:stone",true,false));assertFalse(OrePlanner.shouldPlace(p,1,0,0,0,"minecraft:stone",false,true));}
}
