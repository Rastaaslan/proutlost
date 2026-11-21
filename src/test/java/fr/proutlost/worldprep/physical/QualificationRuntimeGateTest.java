package fr.proutlost.worldprep.physical;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class QualificationRuntimeGateTest {
    @Test void developerLabCanUseRealPinnedDevImporterWithoutProductionAdmission() {
        assertDoesNotThrow(()->QualificationRuntimeGate.requireAllowed("DEV_SERVICE_PROBE",false));
    }
    @Test void productionRefusesDevProviderBeforeEngineStarts() {
        IOException refused=assertThrows(IOException.class,
            ()->QualificationRuntimeGate.requireAllowed("DEV_SERVICE_PROBE",true));
        assertTrue(refused.getMessage().contains("BEFORE physical work"));
    }
    @Test void emptyOrMissingDirectorSourceFailsClosed() {
        assertThrows(IOException.class,()->QualificationRuntimeGate.requireAllowed("",false));
        assertThrows(IOException.class,()->QualificationRuntimeGate.requireAllowed(null,true));
    }
    @Test void pinnedNonDevReceiptIsNotMistakenForLabProbe() {
        assertDoesNotThrow(()->QualificationRuntimeGate.requireAllowed("worldprep-director-admission.json",true));
    }
}
