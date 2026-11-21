package fr.proutlost.worldprep.physical;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeFaunaHabitatPolicyTest {
    private NativeFaunaHabitatPolicy.Profile profile(int min, int max) {
        return new NativeFaunaHabitatPolicy.Profile(
                "minecraft:cow", "LAND", min, max, 1.25, 16, 24, 16, true);
    }

    @Test void acceptsOnlyGroupIntervalsContainedInAnActualNativeEntry() {
        assertTrue(NativeFaunaHabitatPolicy.fitsNativeGroup(profile(4, 4), 4, 4));
        assertTrue(NativeFaunaHabitatPolicy.fitsNativeGroup(profile(3, 5), 2, 6));
        assertFalse(NativeFaunaHabitatPolicy.fitsNativeGroup(profile(1, 4), 4, 4));
        assertFalse(NativeFaunaHabitatPolicy.fitsNativeGroup(profile(4, 5), 4, 4));
        assertFalse(NativeFaunaHabitatPolicy.fitsNativeGroup(profile(4, 4), 0, 8));
        assertFalse(NativeFaunaHabitatPolicy.fitsNativeGroup(profile(4, 4), 6, 2));
    }

    @Test void refusesImpossibleOrUnboundedGroupAndDensityContracts() {
        assertThrows(IllegalArgumentException.class, () -> profile(0, 4));
        assertThrows(IllegalArgumentException.class, () -> profile(5, 4));
        assertThrows(IllegalArgumentException.class, () -> profile(1, 65));
        assertThrows(IllegalArgumentException.class, () ->
                new NativeFaunaHabitatPolicy.Profile("minecraft:cow", "LAND", 4, 4,
                        1.25, 16, 24, 3, true));
        assertThrows(IllegalArgumentException.class, () ->
                new NativeFaunaHabitatPolicy.Profile("minecraft:cow", "LAND", 4, 4,
                        1.25, 16, 3, 4, true));
        assertThrows(IllegalArgumentException.class, () ->
                new NativeFaunaHabitatPolicy.Profile("minecraft:cow", "LAND", 4, 4,
                        1.25, 129, 24, 8, true));
    }

    @Test void refusesNonFiniteSpacingAndUnknownMedium() {
        for (double separation : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1, 0, 129}) {
            assertThrows(IllegalArgumentException.class, () ->
                    new NativeFaunaHabitatPolicy.Profile("minecraft:cow", "LAND", 4, 4,
                            separation, 16, 24, 8, true));
        }
        assertThrows(IllegalArgumentException.class, () ->
                new NativeFaunaHabitatPolicy.Profile("minecraft:cow", "LAVA", 4, 4,
                        1.25, 16, 24, 8, true));
        assertThrows(IllegalArgumentException.class, () ->
                new NativeFaunaHabitatPolicy.Profile("minecraft:cow", null, 4, 4,
                        1.25, 16, 24, 8, true));
    }

    @Test void rejectsInvalidRegistryIdentifiersBeforeWorldAccess() {
        assertThrows(IllegalArgumentException.class, () ->
                new NativeFaunaHabitatPolicy.Profile("NOT AN ENTITY", "LAND", 4, 4,
                        1.25, 16, 24, 8, true));
        assertThrows(IllegalArgumentException.class, () ->
                new NativeFaunaHabitatPolicy.Profile(null, "LAND", 4, 4,
                        1.25, 16, 24, 8, true));
        assertThrows(IllegalStateException.class, () ->
                NativeFaunaHabitatPolicy.validateRegistry(null, profile(4, 4)));
    }
}
