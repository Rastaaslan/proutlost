package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.storage.BatchManifestStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContraV5SectorLineageTest {
    private static BatchManifestStore.Header parent() {
        return new BatchManifestStore.Header(1,
            UUID.fromString("d012c28d-b12e-427b-9ba5-920df4764291"),
            "minecraft:overworld","proutlost:contra_s2_ecology",27594267L,
            16,16,4079,4079,645160L,"ecology-batch-v3");
    }
    private static WorldPrepSavedData.Area sector(int i) {
        var b=ContraV5SectorLineage.sectorBounds(i);
        return new WorldPrepSavedData.Area(ContraV5SectorLineage.sectorId(i),
            "minecraft:overworld",b.minX(),b.minZ(),b.maxX(),b.maxZ());
    }
    @Test void allSeventeenExactNativeGroupAreasDisjointCoverEveryCoreChunk() {
        assertEquals(17,ContraV5SectorLineage.SECTOR_COUNT);
        var coveredChunks=new HashSet<Long>();
        var coveredGroups=new HashSet<Long>();
        long sumGroups=0;
        for(int index=0;index<ContraV5SectorLineage.SECTOR_COUNT;index++) {
            var a=sector(index);
            assertTrue(ContraV5SectorLineage.isExactOrPinnedSector(parent(),a));
            assertTrue(ContraV5SectorLineage.wellFormedParent(parent(),a.id()));
            int gX=((a.maxX()>>4)-(a.minX()>>4)+8)/8;
            int gZ=((a.maxZ()>>4)-(a.minZ()>>4)+8)/8;
            assertTrue(gX*gZ>=1&&gX*gZ<=64);
            sumGroups+=gX*gZ;
            int minGx=(a.minX()-16)/128,maxGx=(a.maxX()-16)/128;
            int minGz=(a.minZ()-16)/128,maxGz=(a.maxZ()-16)/128;
            for(int x=minGx;x<=maxGx;x++)for(int z=minGz;z<=maxGz;z++)
                assertTrue(coveredGroups.add(((long)x<<32)|(z&0xffffffffL)));
            for(int x=a.minX()>>4;x<=a.maxX()>>4;x++)
                for(int z=a.minZ()>>4;z<=a.maxZ()>>4;z++)
                    assertTrue(coveredChunks.add(((long)x<<32)|(z&0xffffffffL)));
        }
        assertEquals(1024,sumGroups);
        assertEquals(1024,coveredGroups.size());
        assertEquals(64516,coveredChunks.size());
    }
    @Test void shiftedEdgesAndUnknownOldNameNeverAccepted() {
        var a=sector(16);
        assertFalse(ContraV5SectorLineage.isExactOrPinnedSector(parent(),
           new WorldPrepSavedData.Area(a.id(),a.dimension(),
              a.minX()+16,a.minZ(),a.maxX(),a.maxZ())));
        assertFalse(ContraV5SectorLineage.isExactOrPinnedSector(parent(),
           new WorldPrepSavedData.Area("proutlost:contra_s2_sector_03_03",
              a.dimension(),a.minX(),a.minZ(),a.maxX(),a.maxZ())));
        assertFalse(ContraV5SectorLineage.typedIdentity(parent(),"proutlost:contra_s2_sector_03_03"));
        assertThrows(IllegalArgumentException.class,()->ContraV5SectorLineage.sectorBounds(17));
        assertThrows(IllegalArgumentException.class,()->ContraV5SectorLineage.sectorId(-1));
    }
    @Test void badParentChunkRangeStagesSeedAndPipelineAllRefuse() {
        var h=parent();
        for(var corrupt:List.of(
           new BatchManifestStore.Header(1,h.batchId(),h.dimension(),h.area(),h.worldSeed()+1,
              16,16,4079,4079,645160L,h.pipelineVersion()),
           new BatchManifestStore.Header(1,h.batchId(),h.dimension(),h.area(),h.worldSeed(),
              16,16,4079,4079,645159L,h.pipelineVersion()),
           new BatchManifestStore.Header(1,h.batchId(),h.dimension(),h.area(),h.worldSeed(),
              16,16,4079,4080,645160L,h.pipelineVersion()),
           new BatchManifestStore.Header(1,h.batchId(),h.dimension(),h.area(),h.worldSeed(),
              16,16,4079,4079,645160L,"invented-pipeline"))) {
            assertFalse(ContraV5SectorLineage.isExactOrPinnedSector(corrupt,sector(0)));
            assertFalse(ContraV5SectorLineage.typedIdentity(corrupt,sector(0).id()));
        }
    }
    @Test void unrelatedV5WorldKeepsOriginalStrictAreaSemantics() {
        var h=new BatchManifestStore.Header(1,UUID.randomUUID(),"minecraft:overworld",
            "proutlost:unrelated",42L,0,0,15,15,10L,"ecology-batch-v3");
        assertTrue(ContraV5SectorLineage.isExactOrPinnedSector(h,
            new WorldPrepSavedData.Area("proutlost:unrelated","minecraft:overworld",0,0,15,15)));
        assertFalse(ContraV5SectorLineage.isExactOrPinnedSector(h,sector(1)));
        assertFalse(ContraV5SectorLineage.typedIdentity(h,sector(1).id()));
    }
}
