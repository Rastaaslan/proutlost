package fr.proutlost.worldprep;

import fr.proutlost.worldprep.orchestration.PerimeterPhysicalCompletion;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

/** A successful sweep may not silently represent zero physical work. */
class PerimeterPhysicalCompletionTest {
    @Test void fullySkippedPerimeterDoesNotBecomeMapReady() {
        IOException error=assertThrows(IOException.class,
            ()->PerimeterPhysicalCompletion.requireNonempty(36,0,0));
        assertTrue(error.getMessage().contains("structures"));
    }

    @Test void structuresWithoutPhysicalAnimalsRemainNotReady() {
        IOException error=assertThrows(IOException.class,
            ()->PerimeterPhysicalCompletion.requireNonempty(36,6,0));
        assertTrue(error.getMessage().contains("fauna"));
    }

    @Test void animalsWithoutPhysicalStructuresRemainNotReady() {
        assertThrows(IOException.class,
            ()->PerimeterPhysicalCompletion.requireNonempty(36,0,4));
    }

    @Test void atLeastOneOfEachIsOnlyMinimumAcceptanceGate() {
        assertDoesNotThrow(()->
            PerimeterPhysicalCompletion.requireNonempty(36,1,1));
    }

    @Test void physicallyImpossibleOrOverflowingCountsRefused() {
        assertThrows(IOException.class,()->PerimeterPhysicalCompletion.requireNonempty(0,0,0));
        assertThrows(IOException.class,()->PerimeterPhysicalCompletion.requireNonempty(36,37,1));
        assertThrows(IOException.class,()->PerimeterPhysicalCompletion.requireNonempty(36,1,-1));
        assertThrows(IOException.class,()->PerimeterPhysicalCompletion.requireNonempty(65,1,1));
    }
}
