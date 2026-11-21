package fr.proutlost.worldprep;

import fr.proutlost.worldprep.ecology.HabitatDiscovery;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class HabitatDiscoveryTest {
    @Test void parallelAndSerialAreIdenticalInInputOrder() throws Exception {
        var samples = IntStream.range(0, 256).mapToObj(i ->
                new HabitatDiscovery.Sample(-i, i * 4, 63, i % 30, i % 2 == 0, i % 7 == 0)).toList();
        var serial = HabitatDiscovery.classify(samples, false);
        assertEquals(serial, HabitatDiscovery.classify(samples, true));
        assertEquals(serial, HabitatDiscovery.classify(samples, true));
        assertEquals(-255, serial.getLast().x());
        assertEquals("PROTECTED", serial.getFirst().support());
        assertThrows(UnsupportedOperationException.class, () -> serial.clear());
    }
    @Test void classifiesDepthAndForestWithoutSpawning() throws Exception {
        var samples = List.of(new HabitatDiscovery.Sample(0, 0, 50, 21, false, false),
                new HabitatDiscovery.Sample(1, 0, 50, 7, false, false),
                new HabitatDiscovery.Sample(2, 0, 50, 2, false, false),
                new HabitatDiscovery.Sample(3, 0, 70, 0, true, false),
                new HabitatDiscovery.Sample(4, 0, 70, 0, false, false));
        assertEquals(List.of("DEEP_MARINE", "MARINE", "SHALLOW_MARINE", "FOREST", "OPEN_GRASSLAND"),
                HabitatDiscovery.classify(samples, true).stream().map(HabitatDiscovery.Cell::habitat).toList());
    }
    @Test void oversizedBatchRefusesBothModes() {
        var samples = java.util.Collections.nCopies(257, new HabitatDiscovery.Sample(0, 0, 60, 0, false, false));
        for (boolean parallel : new boolean[]{false, true})
            assertThrows(IllegalArgumentException.class, () -> HabitatDiscovery.classify(samples, parallel));
    }
    @Test void interruptedWorkCanBeRetriedWithoutPartialResult() throws Exception {
        var samples = List.of(new HabitatDiscovery.Sample(0, 0, 60, 0, false, false));
        for (boolean parallel : new boolean[]{false, true}) {
            Thread.currentThread().interrupt();
            try { assertThrows(InterruptedException.class, () -> HabitatDiscovery.classify(samples, parallel)); }
            finally { Thread.interrupted(); }
            assertEquals(1, HabitatDiscovery.classify(samples, parallel).size());
        }
    }
}
