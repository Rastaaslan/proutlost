package fr.proutlost.worldprep.physical;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native LevelChunk.getHeight is TOP SOLID Y; unlike NBT first-free encoding. */
class NativeTemplateHeightmapTest {
    @Test void nativeSolidTop69BecomesConfirmedAnvilFirstAir70() {
        assertEquals(70,NativeTemplateSitePlanner.foundationY(69));
    }
    @Test void nativeSolidTop74BecomesSpawnFirstAir75() {
        assertEquals(75,NativeTemplateSitePlanner.foundationY(74));
    }
    @Test void belowSeaLevelTopSolidGetsOneAirY() {
        assertEquals(-15,NativeTemplateSitePlanner.foundationY(-16));
    }
    @Test void highMountainTopSolidGetsOneAirY() {
        assertEquals(149,NativeTemplateSitePlanner.foundationY(148));
    }
}
