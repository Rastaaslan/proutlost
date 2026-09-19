package fr.proutlost.worldprep;
import fr.proutlost.worldprep.ecology.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AquaticHabitatTest {
 @Test void aquaticDepthClassesAreStable(){assertEquals(AquaticPlanner.MarineClass.SHORELINE,AquaticPlanner.plan(1,0,0,1).marineClass());assertEquals(AquaticPlanner.MarineClass.DEEP_MARINE,AquaticPlanner.plan(30,0,30,1).marineClass());assertFalse(AquaticPlanner.plan(30,0,30,1).vegetation());}
 @Test void coarseMapUsesFloorCoordinates(){var c=new HabitatMap.Cell(HabitatMap.Habitat.FOREST,new HabitatMap.Scores(1,0,0,0,0));var map=new HabitatMap(-16,-16,16,1,1,new HabitatMap.Cell[]{c});assertEquals(c,map.atBlock(-1,-1));assertEquals(16,map.cellSize());}
}
