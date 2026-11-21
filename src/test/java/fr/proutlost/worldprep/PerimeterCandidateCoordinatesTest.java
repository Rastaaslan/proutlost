package fr.proutlost.worldprep;

import fr.proutlost.worldprep.orchestration.PerimeterCandidateCoordinates;
import fr.proutlost.worldprep.orchestration.PerimeterGroups;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PerimeterCandidateCoordinatesTest {
    @Test void clippedPositiveAndNegativeBordersStayInsideExactArea() {
        var last = PerimeterCandidateCoordinates.inside(327,-59,4496,-1666,5236,-943);
        assertEquals(5236,last.x());  // (327*16)+8 would be outside the S2 boundary
        assertEquals(-943,last.z()); // (-59*16)+8 would be outside the S2 boundary
        var first = PerimeterCandidateCoordinates.inside(281,-105,4496,-1666,5236,-943);
        assertEquals(4504,first.x());
        assertEquals(-1666,first.z());
    }

    @Test void normalFullChunkKeepsCenter() {
        var center=PerimeterCandidateCoordinates.inside(300,-80,4496,-1666,5236,-943);
        assertEquals(4808,center.x());
        assertEquals(-1272,center.z());
    }

    @Test void negativeCoordinatesAndSingleBlockAreas() {
        assertEquals(new PerimeterCandidateCoordinates.Candidate(-1,-1),
            PerimeterCandidateCoordinates.inside(-1,-1,-1,-1,-1,-1));
        assertEquals(new PerimeterCandidateCoordinates.Candidate(0,0),
            PerimeterCandidateCoordinates.inside(0,0,0,0,0,0));
    }

    @Test void rejectsCandidateChunkOutsideExactArea() {
        assertThrows(IllegalArgumentException.class,
            ()->PerimeterCandidateCoordinates.inside(328,-59,4496,-1666,5236,-943));
        assertThrows(IllegalArgumentException.class,
            ()->PerimeterCandidateCoordinates.inside(281,-106,4496,-1666,5236,-943));
        assertThrows(IllegalArgumentException.class,
            ()->PerimeterCandidateCoordinates.inside(0,0,1,1,0,0));
    }

    @Test void all2209ProutlostCandidateCoordinatesRemainInsideTheirOwnGroupAndChunk() {
        var groups=new PerimeterGroups(UUID.fromString("00000000-0000-0000-0000-000000000201"),
            "a".repeat(64),"minecraft:overworld",4496,-1666,5236,-943);
        var encountered=new HashSet<PerimeterCandidateCoordinates.Candidate>();
        for(long i=0;i<groups.count();i++) {
            var group=groups.group(i);
            for(int x=group.minX()>>4;x<=group.maxX()>>4;x++)
                for(int z=group.minZ()>>4;z<=group.maxZ()>>4;z++) {
                    var point=PerimeterCandidateCoordinates.inside(x,z,
                        group.minX(),group.minZ(),group.maxX(),group.maxZ());
                    assertTrue(point.x()>=group.minX() && point.x()<=group.maxX());
                    assertTrue(point.z()>=group.minZ() && point.z()<=group.maxZ());
                    assertEquals(x,point.x()>>4);
                    assertEquals(z,point.z()>>4);
                    assertTrue(encountered.add(point),"No duplicate candidate sites across groups");
                }
        }
        assertEquals(2209,encountered.size());
    }

    @Test void extremeIntCoordinatesDoNotOverflowChunkCenters() {
        var maximum=PerimeterCandidateCoordinates.inside(Integer.MAX_VALUE>>4,0,
            Integer.MAX_VALUE-2,0,Integer.MAX_VALUE,0);
        assertEquals(Integer.MAX_VALUE-2,maximum.x());
        var minimum=PerimeterCandidateCoordinates.inside(Integer.MIN_VALUE>>4,0,
            Integer.MIN_VALUE,0,Integer.MIN_VALUE+2,0);
        assertEquals(Integer.MIN_VALUE+2,minimum.x());
    }
}
