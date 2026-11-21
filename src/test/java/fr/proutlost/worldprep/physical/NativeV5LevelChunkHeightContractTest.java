package fr.proutlost.worldprep.physical;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native LevelChunk height is top blocking Y, unlike on-disk NBT first free Y.
 * These tests protect the V5 native per-group fauna planner from off-by-one.
 */
class NativeV5LevelChunkHeightContractTest {
    @Test void terrestrialCandidateUsesFirstUnoccupiedHeight(){
        int topBlocking=69;
        int firstFree=NativeTemplateSitePlanner.foundationY(topBlocking);
        assertEquals(70,firstFree);
        assertEquals(70,NativeHabitatOrigin.chooseY(
            firstFree,-64,0,y->false).orElseThrow());
    }
    @Test void aquaticCandidateUsesRealSeabedNotTopWaterSurface(){
        int topSurface=63;
        int firstFree=NativeTemplateSitePlanner.foundationY(topSurface);
        assertEquals(64,firstFree);
        // Water 45..63, seabed at 44. Select 6th water cell above seabed.
        assertEquals(50,NativeHabitatOrigin.chooseY(
            firstFree,-64,6,y->y>=45&&y<=63).orElseThrow());
    }
    @Test void openOceanWithoutSubstrateIsRefused(){
        int firstFree=NativeTemplateSitePlanner.foundationY(63);
        assertTrue(NativeHabitatOrigin.chooseY(
            firstFree,-64,6,y->true).isEmpty());
    }
    @Test void shallowWaterCannotFakeSixBlockDepth(){
        int firstFree=NativeTemplateSitePlanner.foundationY(63);
        assertTrue(NativeHabitatOrigin.chooseY(
            firstFree,-64,6,y->y>=61&&y<=63).isEmpty());
    }
    @Test void nativeHighlandCandidateDoesNotLoseOneBlock(){
        assertEquals(111,NativeTemplateSitePlanner.foundationY(110));
    }
}
