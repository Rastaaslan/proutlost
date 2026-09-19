package fr.proutlost.worldprep;
import fr.proutlost.worldprep.analysis.TerrainAnalysis.Kind;
import fr.proutlost.worldprep.ecology.*;
import fr.proutlost.worldprep.mutation.MutationGroup;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TerrestrialPlannerTest {
 @Test void soilsRespectTerrain(){assertEquals(0,SoilPlanner.plan(Kind.CLIFF,.2,1).depth());assertEquals(SoilPlanner.Type.SAND,SoilPlanner.plan(Kind.BEACH,.2,1).type());}
 @Test void vegetationIsOrderIndependentAndManualOnlyRefuses(){boolean a=VegetationPlanner.flora(4,-8,9,.5,.8);boolean b=VegetationPlanner.flora(4,-8,9,.5,.8);assertEquals(a,b);assertFalse(VegetationPlanner.treeCandidate(1,1,1,VegetationPlanner.TreeMode.MANUAL_ONLY,1,0));}
 @Test void mutationGroupIsCompleteAndUnique(){assertThrows(IllegalArgumentException.class,()->new MutationGroup(UUID.randomUUID(),List.of(new MutationGroup.Mutation(1,2,3,"a","b"),new MutationGroup.Mutation(1,2,3,"a","c"))));}
}
