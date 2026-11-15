package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.orchestration.PerimeterGroups;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NativeTemplateSitePlannerCoverageTest {
    private static final String TEMPLATE="proutlost:worldprep/stone_platform_v1";

    @Test void all36S2GroupsAreBoundedDeterministicAndInsideReservation() throws Exception {
        var groups=new PerimeterGroups(
            UUID.fromString("ae72dbcf-d9ae-5090-8ee1-a4f63d0d0c18"),
            "a".repeat(64),"minecraft:overworld",4496,-1666,5236,-943);
        assertEquals(36,groups.count());
        int total=0,covered=0;
        for(long i=0;i<groups.count();i++) {
            var g=groups.group(i);
            var area=new WorldPrepSavedData.Area("test/"+i,groups.dimension(),
                g.minX(),g.minZ(),g.maxX(),g.maxZ());
            try(var planner=new NativeTemplateSitePlanner(area,TEMPLATE,"frozen-configuration",42L);
                var repeated=new NativeTemplateSitePlanner(area,TEMPLATE,"frozen-configuration",42L)) {
                var positions=planner.auditCandidates();
                assertEquals(positions,repeated.auditCandidates(),"Candidate order must be deterministic");
                assertTrue(positions.size()<=2304,"At most 36 sites per 64 candidate chunks");
                assertEquals(positions.size(),new HashSet<>(positions).size(),"No duplicates");
                if(!positions.isEmpty())covered++;
                boolean inFallback=false;
                for(var point:positions) {
                    boolean primary=(point.getX()&15)%4==0&&(point.getZ()&15)%4==0;
                    if(!primary)inFallback=true;
                    assertFalse(inFallback&&primary,"Secondary sites must follow every primary site");
                    assertEquals(0,point.getY());
                    assertTrue((long)point.getX()>=g.minX()+16L &&
                        (long)point.getX()<=g.maxX()-18L);
                    assertTrue((long)point.getZ()>=g.minZ()+16L &&
                        (long)point.getZ()<=g.maxZ()-18L);
                    assertTrue(point.getX()>=4496 && point.getX()<=5236);
                    assertTrue(point.getZ()>=-1666 && point.getZ()<=-943);
                }
                total+=positions.size();
            }
        }
        assertTrue(covered>=20,"Enough full-size groups should have bounded sampling windows");
        assertTrue(total>2209,"Multi-position survey covers more than the original center-only search");
        assertTrue(total<=36*2304);
    }

    @Test void narrowAreaProducesExplicitEmptySurveyNotUnsafePositions() throws Exception {
        var small=new WorldPrepSavedData.Area("small","minecraft:overworld",10,10,12,12);
        try(var planner=new NativeTemplateSitePlanner(small,TEMPLATE,"frozen",7)) {
            assertTrue(planner.exhausted());
            assertTrue(planner.auditCandidates().isEmpty());
        }
    }

    @Test void invalidTemplatesAndOversizedAreaStillRefuse() {
        var area=new WorldPrepSavedData.Area("test","minecraft:overworld",0,0,1023,1023);
        assertThrows(IllegalArgumentException.class,
            ()->new NativeTemplateSitePlanner(area,TEMPLATE,"frozen",7));
        var one=new WorldPrepSavedData.Area("test","minecraft:overworld",0,0,127,127);
        assertThrows(IllegalArgumentException.class,
            ()->new NativeTemplateSitePlanner(one,"minecraft:village", "frozen",7));
    }
}
