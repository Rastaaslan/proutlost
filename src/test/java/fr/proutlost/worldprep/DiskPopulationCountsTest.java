package fr.proutlost.worldprep;
import fr.proutlost.worldprep.orchestration.DiskPopulationCounts;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class DiskPopulationCountsTest {
    DiskPopulationCounts cow(){return new DiskPopulationCounts(1,Map.of("minecraft:cow",1),Map.of("minecraft:overworld:0:0",1),Map.of("minecraft:overworld:1:1",1));}
    @Test void thousandsOfEmptyChunksDoNotAllocateRetainedCounters(){
        var result=cow();for(int i=0;i<5000;i++)result=result.plus(DiskPopulationCounts.empty());
        assertEquals(cow(),result);assertEquals(1,result.chunks().size());
    }
    @Test void totalsIncludeSpeciesAcrossDimensionsWithoutTruncation(){
        var fish=new DiskPopulationCounts(2,Map.of("minecraft:cod",2),Map.of("mod:sea:0:0",2),Map.of("mod:sea:1:1",2));
        var result=cow().plus(fish);assertEquals(3,result.total());assertEquals(2,result.species().get("minecraft:cod"));assertEquals(2,result.regions().size());
    }
    @Test void capacityExhaustionAndMalformedReceiptsRefusePopulation(){
        assertThrows(IllegalArgumentException.class,()->new DiskPopulationCounts(1,Map.of(),Map.of(),Map.of()));
        assertThrows(IllegalArgumentException.class,()->new DiskPopulationCounts(0,Map.of("cow",-1),Map.of(),Map.of()));
        var all=new DiskPopulationCounts(256,Map.of("cow",256),Map.of("region",256),Map.of("chunk",256));
        assertThrows(IllegalArgumentException.class,()->all.plus(cow()));
        assertThrows(UnsupportedOperationException.class,()->cow().species().clear());
    }
}
