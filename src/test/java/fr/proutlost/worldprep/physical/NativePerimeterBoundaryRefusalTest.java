package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativePerimeterBoundaryRefusalTest {
    private static ServerExistingChunkAccess.Refused refused(ServerExistingChunkAccess.Refusal code) {
        return new ServerExistingChunkAccess.Refused(code, "test");
    }

    @Test void exactAreaBorderIsAnOrdinarySurveyRejection() {
        assertTrue(NativeTemplateSitePlanner.isOutsideCandidateArea(
            refused(ServerExistingChunkAccess.Refusal.OUTSIDE_AREA)));
    }

    @Test void missingFullChunkMustRemainFatal() {
        assertFalse(NativeTemplateSitePlanner.isOutsideCandidateArea(
            refused(ServerExistingChunkAccess.Refusal.MISSING_OR_INCOMPLETE_CHUNK)));
    }

    @Test void wrongDimensionOrBorderDoesNotBecomeEligibleSkip() {
        assertFalse(NativeTemplateSitePlanner.isOutsideCandidateArea(
            refused(ServerExistingChunkAccess.Refusal.WRONG_DIMENSION)));
        assertFalse(NativeTemplateSitePlanner.isOutsideCandidateArea(
            refused(ServerExistingChunkAccess.Refusal.OUTSIDE_BORDER)));
    }
}
