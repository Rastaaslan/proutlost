package fr.proutlost.worldprep;
import fr.proutlost.worldprep.pipeline.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PipelineModelTest {
 @Test void graphContainsDependenciesAndTransitiveInvalidation(){assertEquals(Set.of(PassId.GEOLOGY),PassGraph.dependencies(PassId.ORES));assertTrue(PassGraph.invalidatedBy(PassId.BIOMES).containsAll(Set.of(PassId.SOILS,PassId.FLORA,PassId.TREES,PassId.AQUATIC,PassId.HABITATS)));}
 @Test void plannedOverlayPrecedesActual(){var view=new PlanningWorldView<String,String>(k->"actual",List.of(Map.of("p","planned")));assertEquals("planned",view.get("p"));assertEquals("actual",view.get("q"));}
 @Test void semanticFingerprintIsCanonicalAndExecutionIndependent(){var a=new SemanticConfig(new TreeMap<>(Map.of("b","2","a","1")));var b=new SemanticConfig(new TreeMap<>(Map.of("a","1","b","2")));assertEquals(a.fingerprint(),b.fingerprint());assertNotNull(new ExecutionConfig(1,2,3));}
}
