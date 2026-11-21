package fr.proutlost.worldprep.physical;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Distinguish ordinary site rejection from broken configuration or IO/runtime faults. */
class CandidateRefusalClassificationTest {
    @Test void faunaSiteRefusalIsExplicitType() {
        var rejection = new NativeFaunaPass.UnsuitableHabitat("Ground/clearance unsuitable");
        assertInstanceOf(IllegalArgumentException.class, rejection);
        assertEquals("Ground/clearance unsuitable", rejection.getMessage());
    }

    @Test void invalidFaunaRuleMustNotBecomeARejectedHabitat() {
        var error = assertThrows(IllegalArgumentException.class, () ->
            new NativeFaunaPass.Rule("minecraft:cow", Set.of(), Set.of("minecraft:grass_block"),
                0, 255, 0.0f, 1.0f, 0, 10, 5, 2, 16, true));
        assertFalse(error instanceof NativeFaunaPass.UnsuitableHabitat);
    }

    @Test void unsupportedTemplateIsNotOrdinarySiteRejection() {
        assertFalse(new IllegalArgumentException("Unsupported template") instanceof NativeStructurePass.IneligibleSite);
        assertInstanceOf(IllegalArgumentException.class,
            new NativeStructurePass.IneligibleSite("Exact template requires flat stable foundation"));
    }

    @Test void protectedSiteIsExplicitAndDistinctFromRuntimeFailure() {
        assertInstanceOf(IllegalStateException.class,
            new NativeStructurePass.SiteConflict("Spawn access reservation"));
        assertFalse(new IllegalStateException("Disk corruption") instanceof NativeStructurePass.SiteConflict);
    }
}
