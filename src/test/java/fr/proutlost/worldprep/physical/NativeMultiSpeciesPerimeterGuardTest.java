package fr.proutlost.worldprep.physical;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class NativeMultiSpeciesPerimeterGuardTest {
    @Test void v4CannotOpenTheV5MultispeciesPhysicalRunner() {
        IOException refused=assertThrows(IOException.class,()->
            new NativeMultiSpeciesPerimeter(null,null,null,"v4-existing-frozen-request",null,4));
        assertTrue(refused.getMessage().contains("V4/single-species"));
    }
}
