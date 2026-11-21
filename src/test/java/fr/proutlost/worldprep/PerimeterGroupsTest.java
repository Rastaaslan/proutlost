package fr.proutlost.worldprep;
import fr.proutlost.worldprep.orchestration.PerimeterGroups;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PerimeterGroupsTest {
    @Test void full2209ChunkPerimeterHasNoGapsOrDuplicates() {
        var groups=new PerimeterGroups(UUID.fromString("f1d00b2a-d490-466e-ae03-0cab665a2ceb"),"a".repeat(64),"minecraft:overworld",4496,-1666,5236,-943);
        assertEquals(2209,groups.chunks());assertEquals(36,groups.count());
        var covered=new HashSet<String>();
        for(long n=0;n<groups.count();n++) {
            var g=groups.group(n);assertTrue(g.chunks()<=64);
            for(int x=g.minX()>>4;x<=g.maxX()>>4;x++)for(int z=g.minZ()>>4;z<=g.maxZ()>>4;z++)assertTrue(covered.add(x+","+z));
        }
        assertEquals(2209,covered.size());
        assertEquals(4496,groups.group(0).minX());assertEquals(-1666,groups.group(0).minZ());
        assertEquals(5236,groups.group(35).maxX());assertEquals(-943,groups.group(35).maxZ());
    }
    @Test void resumeOrdinalKeepsIdentityAndChangedConfigCannotReuseIt() {
        var id=UUID.randomUUID();var a=new PerimeterGroups(id,"a".repeat(64),"minecraft:overworld",-500,-500,500,500);
        var resumed=new PerimeterGroups(id,"a".repeat(64),"minecraft:overworld",-500,-500,500,500);
        assertEquals(a.group(12),resumed.group(12));
        assertNotEquals(a.group(12).operation(),new PerimeterGroups(id,"b".repeat(64),"minecraft:overworld",-500,-500,500,500).group(12).operation());
        assertThrows(IllegalArgumentException.class,()->a.group(a.count()));
    }
}
