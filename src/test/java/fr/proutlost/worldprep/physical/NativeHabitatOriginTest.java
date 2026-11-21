package fr.proutlost.worldprep.physical;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NativeHabitatOriginTest {
    @Test void landHeightmapIsAlreadyFirstFreeBlock() {
        var samples=new AtomicInteger();
        var chosen=NativeHabitatOrigin.chooseY(81,-64,0,y->{
            samples.incrementAndGet();return false;
        });
        assertEquals(81,chosen.orElseThrow());
        assertEquals(0,samples.get(),"Never inspect water for land fauna");
    }

    @Test void actualS2OceanProfileFindsSixDeepWaterOriginAboveSand() {
        // S2 native-qualified cod site (4500,49,-1656):
        // first air 63, 19 water layers at 62..44, seabed sand y=43.
        var count=new AtomicInteger();
        var candidate=NativeHabitatOrigin.chooseY(63,-64,6,y->{
            count.incrementAndGet();
            return y>=44&&y<=62;
        });
        assertEquals(49,candidate.orElseThrow());
        assertEquals(20,count.get(),"Seabed must be positively observed");
    }

    @Test void shallowWaterNeverPretendsADeeperFloor() {
        var candidate=NativeHabitatOrigin.chooseY(63,-64,6,y->y>=61);
        assertTrue(candidate.isEmpty());
    }

    @Test void unboundedDeepWaterNeverProducesAnInferredSubstrate() {
        var count=new AtomicInteger();
        var candidate=NativeHabitatOrigin.chooseY(63,-64,8,y->{
            count.incrementAndGet();return true;
        });
        assertTrue(candidate.isEmpty());
        assertEquals(33,count.get(),"At most 33 read-only probes per candidate");
    }

    @Test void worldHeightBoundaryRejectsAnUnknownFloor() {
        var candidate=NativeHabitatOrigin.chooseY(-61,-64,4,y->true);
        assertTrue(candidate.isEmpty());
    }

    @Test void invalidDepthOrNoFluidPredicateCannotBeUsed() {
        assertThrows(IllegalArgumentException.class,
            ()->NativeHabitatOrigin.chooseY(63,-64,-1,y->true));
        assertThrows(IllegalArgumentException.class,
            ()->NativeHabitatOrigin.chooseY(63,-64,33,y->true));
        assertThrows(IllegalArgumentException.class,
            ()->NativeHabitatOrigin.chooseY(63,-64,0,null));
    }

    @Test void firstAirAtWorldBottomCannotBecomeAnOutsideBuildHeightFatalRead() {
        assertFalse(NativeHabitatOrigin.verticalEnvelopeFits(-64,-64,320,0));
        assertFalse(NativeHabitatOrigin.verticalEnvelopeFits(-63,-64,320,6));
        assertTrue(NativeHabitatOrigin.verticalEnvelopeFits(-63,-64,320,0));
        assertTrue(NativeHabitatOrigin.verticalEnvelopeFits(-58,-64,320,6));
        assertFalse(NativeHabitatOrigin.verticalEnvelopeFits(319,-64,320,0));
        assertTrue(NativeHabitatOrigin.verticalEnvelopeFits(317,-64,320,0));
        assertFalse(NativeHabitatOrigin.verticalEnvelopeFits(-58,-64,320,-1));
    }

    @Test void exactMinimumWaterDepthAcceptsAnObservedSeabed() {
        var candidate=NativeHabitatOrigin.chooseY(63,-64,6,y->y>=57&&y<=62);
        assertEquals(62,candidate.orElseThrow());
    }
}
