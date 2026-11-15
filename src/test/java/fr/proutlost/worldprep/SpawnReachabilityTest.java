package fr.proutlost.worldprep;
import fr.proutlost.worldprep.orchestration.SpawnReachability;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SpawnReachabilityTest {
    List<SpawnReachability.Cell> flat(){var cells=new ArrayList<SpawnReachability.Cell>();for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)cells.add(new SpawnReachability.Cell(x,0,z));return cells;}
    @Test void safeConnectedGroundHasFourIndependentDirections(){var r=SpawnReachability.verify(4,3,16,flat());assertEquals(81,r.reachable());assertEquals(Set.of("WEST","EAST","NORTH","SOUTH"),r.exits());}
    @Test void adjacentFallOrWaterCannotBeHiddenByLargeReachableArea(){var cells=flat();cells.remove(new SpawnReachability.Cell(1,0,0));assertThrows(IllegalStateException.class,()->SpawnReachability.verify(4,3,16,cells));}
    @Test void structureWallBlockingExitFailsEvenWithManySafeCells(){var cells=flat();cells.removeIf(c->c.x()==2);assertThrows(IllegalStateException.class,()->SpawnReachability.verify(4,3,16,cells));}
    @Test void disconnectedSafeIslandsCannotCountAsAccessible(){var cells=flat();cells.removeIf(c->Math.abs(c.x())==2||Math.abs(c.z())==2);assertThrows(IllegalStateException.class,()->SpawnReachability.verify(4,3,16,cells));}
    @Test void oneBlockSlopeConnectsButLargeCliffDoesNot(){var cells=flat();cells.replaceAll(c->new SpawnReachability.Cell(c.x(),c.x()>0?1:0,c.z()));assertEquals(81,SpawnReachability.verify(4,3,16,cells).reachable());cells.replaceAll(c->new SpawnReachability.Cell(c.x(),c.x()>0?3:0,c.z()));assertThrows(IllegalStateException.class,()->SpawnReachability.verify(4,3,16,cells));}
    @Test void invalidOrAmbiguousObservationsNeverProduceProof(){var cells=flat();cells.set(0,new SpawnReachability.Cell(0,1,0));assertThrows(IllegalArgumentException.class,()->SpawnReachability.verify(4,3,16,cells));assertThrows(IllegalArgumentException.class,()->SpawnReachability.verify(100,3,16,List.of()));}
}
