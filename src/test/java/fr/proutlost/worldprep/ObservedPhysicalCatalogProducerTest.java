package fr.proutlost.worldprep;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObservedPhysicalCatalogProducerTest {
    @TempDir Path temporary;
    @Test void closedRequestCompleteWitnessesAndGeometryOnlyExport()throws Exception{
        ObservedPhysicalCatalogProducerScenario.run(temporary);
        ObservedPhysicalCatalogProducerScenario.reopen(temporary);
    }
}
