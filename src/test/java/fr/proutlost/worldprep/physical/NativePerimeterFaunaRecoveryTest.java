package fr.proutlost.worldprep.physical;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NativePerimeterFaunaRecoveryTest {
    @TempDir Path root;

    @Test void emptyUnplannedGroupCanProduceSkipEvidence() throws Exception {
        var store = new PhysicalStore(root.resolve("new-group"));
        assertDoesNotThrow(() -> NativePerimeterFauna.requireNoRetainedIntentForSkip(store));
    }

    @Test void durablePlanCannotBecomeHabitatSkipAfterRestart() throws Exception {
        var store = new PhysicalStore(root.resolve("planned-group"));
        store.write("plan", Map.of("operation", "retained-uuid"));
        IOException error = assertThrows(IOException.class,
                () -> NativePerimeterFauna.requireNoRetainedIntentForSkip(store));
        assertTrue(error.getMessage().contains("RETAINED_FAUNA_INTENT"));
        assertFalse(store.exists("evidence"));
    }

    @Test void issuedAnimalCannotBeConvertedIntoBudgetSkip() throws Exception {
        var store = new PhysicalStore(root.resolve("issued-group"));
        store.write("population-issued", Map.of("operation", "retained-uuid"));
        assertThrows(IOException.class,
                () -> NativePerimeterFauna.requireNoRetainedIntentForSkip(store));
        assertFalse(store.exists("evidence"));
    }

    @Test void diskReceiptCannotBecomeExclusionEvidence() throws Exception {
        var store = new PhysicalStore(root.resolve("verified-group"));
        store.write("population-disk-verified", Map.of("status", "POPULATION_SAVE_READBACK_VERIFIED"));
        assertThrows(IOException.class,
                () -> NativePerimeterFauna.requireNoRetainedIntentForSkip(store));
    }
}
