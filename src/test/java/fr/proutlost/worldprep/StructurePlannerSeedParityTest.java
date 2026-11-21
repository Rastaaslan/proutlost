package fr.proutlost.worldprep;

import fr.proutlost.worldprep.biome.v2.TerrainSemantic;
import fr.proutlost.worldprep.structure.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Regression: old XOR rarity 4 rejects ALL even-sampled S2 template sites. */
class StructurePlannerSeedParityTest {
    private static final String TEMPLATE="proutlost:worldprep/stone_platform_v1";
    private static final long SEED=-355732122L;
    private static StructureCatalog catalog() {
        return new StructureCatalog(List.of(new StructureCapability(TEMPLATE,
            StructureSupportStatus.EXACT_TEMPLATE,"exact-static-template-v1",
            3,1,3,2,32,4,Set.of(TerrainSemantic.LOWLAND,TerrainSemantic.HIGHLAND))));
    }
    private static AutomaticStructurePlanner.Candidate candidate(int x,int z) {
        return new AutomaticStructurePlanner.Candidate(
            TEMPLATE,x,70,z,100,TerrainSemantic.LOWLAND,0);
    }
    private static List<StructureInstancePlan> allocate(int x,int z) {
        return new AutomaticStructurePlanner().plan(List.of(candidate(x,z)),
            catalog(),SEED,1,1,"a".repeat(64));
    }
    @Test void formerRawXorProvesOriginalEvenGridCouldNeverPassRarityFour(){
        for(int x=4528;x<4592;x+=2)
            for(int z=-1296;z<-1248;z+=2){
                long old=SEED^((long)x*341873128712L)^((long)z*132897987541L)
                    ^TEMPLATE.hashCode();
                assertNotEquals(0,Math.floorMod(old,4));
            }
    }
    @Test void avalancheRestoresActualRarityWithoutRemovingIt(){
        int accepted=0;
        for(int x=4530;x<4562;x+=2)
            for(int z=-1294;z<-1278;z+=2){
                var result=allocate(x,z);
                assertTrue(result.size()<=1);
                if(!result.isEmpty())accepted++;
            }
        assertTrue(accepted>10 && accepted<80,
            "Salted rarity must admit some, but not all, even-grid candidates: "+accepted);
    }
    @Test void rarityFourNoLongerForcesOnlyNorthFacingRotations(){
        var rotations=EnumSet.noneOf(StructureInstancePlan.Rotation.class);
        for(int x=4530;x<4594;x+=2)
            for(int z=-1294;z<-1258;z+=2){
                var result=allocate(x,z);
                if(!result.isEmpty())rotations.add(result.getFirst().rotation());
            }
        assertTrue(rotations.size()>=3,
            "Rarity and rotation must be independently salted: "+rotations);
    }
    @Test void exactS2Group03RealAnvilGeometryCandidatePassesRarityGate(){
        assertEquals(1,allocate(4584,-1240).size(),
            "Frozen S2 3x3 flat foundation has a valid automatic rarity draw");
    }
    @Test void allocationRemainsExactlyDeterministicForFrozenSeedAndConfig(){
        var first=allocate(4552,-1270);
        var second=allocate(4552,-1270);
        assertEquals(first,second);
    }
    @Test void earlyRarityCheckMatchesFullAllocatorOnRealS2CoordinateGrid(){
        for(int x=4518;x<4602;x+=2)
            for(int z=-1318;z<-1262;z+=2){
                boolean admitted=AutomaticStructurePlanner.rarityAdmitted(SEED,TEMPLATE,x,z,4);
                assertEquals(!allocate(x,z).isEmpty(),admitted,
                    "Preflight must not reject any valid native placement candidate at "+x+","+z);
            }
    }
}
