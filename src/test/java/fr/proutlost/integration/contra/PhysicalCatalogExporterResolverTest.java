package fr.proutlost.integration.contra;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real exporter/J04/resolver regression; synthetic evidence never qualifies Contra or audio. */
class PhysicalCatalogExporterResolverTest {
    @TempDir Path temporary;
    @Test void explicitTagsAndChannelsSurviveExportAndReachTheRealResolver()throws Exception{
        PhysicalCatalogExporterResolverScenario.run(temporary);
    }
}
