package fr.proutlost.worldprep;

import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PerimeterFaunaCandidatesTest {
    @Test void all36RealGroupsKeepOriginalCentersFirstAndBoundedFallback() {
        var groups=new PerimeterGroups(UUID.fromString("ae72dbcf-d9ae-5090-8ee1-a4f63d0d0c18"),
            "a".repeat(64),"minecraft:overworld",4496,-1666,5236,-943);
        assertEquals(36,groups.count());
        int allPrimary=0,allFallback=0;
        for(long n=0;n<groups.count();n++) {
            var g=groups.group(n);
            var area=new WorldPrepSavedData.Area("group/"+n,groups.dimension(),
                g.minX(),g.minZ(),g.maxX(),g.maxZ());
            var candidates=PerimeterFaunaCandidates.enumerate(area,-355732122L,g.operation());
            var retry=PerimeterFaunaCandidates.enumerate(area,-355732122L,g.operation());
            assertEquals(candidates,retry,"Deterministic candidate order and identity");
            int chunks=((g.maxX()>>4)-(g.minX()>>4)+1)*
                ((g.maxZ()>>4)-(g.minZ()>>4)+1);
            assertTrue(candidates.size()>=chunks && candidates.size()<=chunks*9);
            assertEquals(candidates.size(),new HashSet<>(candidates).size());
            var originalCenters=new HashSet<BlockPos>();
            for(int x=g.minX()>>4;x<=g.maxX()>>4;x++)
                for(int z=g.minZ()>>4;z<=g.maxZ()>>4;z++){
                    var center=PerimeterCandidateCoordinates.inside(x,z,
                        g.minX(),g.minZ(),g.maxX(),g.maxZ());
                    originalCenters.add(new BlockPos(center.x(),0,center.z()));
                }
            assertEquals(chunks,originalCenters.size());
            // Every fallback chunk is visited only once, so native verified
            // halo leases can be shared across its alternative positions.
            var fallbackChunkOrder=new HashSet<String>();
            String previousChunk=null;
            for(var pos:candidates.subList(chunks,candidates.size())){
                String key=(pos.getX()>>4)+","+(pos.getZ()>>4);
                if(!key.equals(previousChunk)){
                    assertTrue(fallbackChunkOrder.add(key),
                        "Backtracking would repeatedly reload the same native halo: "+key);
                    previousChunk=key;
                }
            }
            var first=new HashSet<>(candidates.subList(0,chunks));
            assertEquals(originalCenters,first,"All old center locations precede fallback");
            for(var p:candidates) {
                assertTrue(p.getX()>=g.minX()&&p.getX()<=g.maxX());
                assertTrue(p.getZ()>=g.minZ()&&p.getZ()<=g.maxZ());
                assertEquals(0,p.getY());
            }
            allPrimary+=chunks;
            allFallback+=candidates.size()-chunks;
        }
        assertEquals(2209,allPrimary);
        assertTrue(allFallback>2209,"Alternative positions must cover more than one/chunk");
    }

    @Test void oneBlockEdgeDoesNotInventPositionsOrOtherChunks() {
        var area=new WorldPrepSavedData.Area("tiny","minecraft:overworld",-1,-1,-1,-1);
        var candidates=PerimeterFaunaCandidates.enumerate(area,1L,UUID.randomUUID());
        assertEquals(1,candidates.size());
        assertEquals(new BlockPos(-1,0,-1),candidates.getFirst());
    }

    @Test void explicitGroupBoundPreventsUnlimitedSurvey() {
        var area=new WorldPrepSavedData.Area("too-wide","minecraft:overworld",0,0,1024,1024);
        assertThrows(IllegalArgumentException.class,()->PerimeterFaunaCandidates.enumerate(
            area,1L,UUID.randomUUID()));
    }
}
