package fr.proutlost.worldprep.physical;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import fr.proutlost.worldprep.world.NativeChunkEligibility;
class NativeChunkEligibilityTest {
 @Test void explicitStructuresOnlyLetsNativeLightingLoad(){assertTrue(NativeChunkEligibility.canPromote(true,false,false,true));}
 @Test void neverPromotesMissingTerrain(){assertFalse(NativeChunkEligibility.canPromote(false,true,false,true));}
 @Test void neverPromotesWorldUpgrade(){assertFalse(NativeChunkEligibility.canPromote(true,true,true,true));}
 @Test void legacyGuardUnchanged(){assertFalse(NativeChunkEligibility.canPromote(true,false,false,false));}
}
