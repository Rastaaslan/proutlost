package fr.proutlost.worldprep.physical;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure policies share the exact id whitelist used by NativeSpawnAudit. */
class NativeSpawnWalkabilityTest {
    @Test void ordinaryAirAccepted() {
        assertTrue(SpawnWalkabilityPolicy.clearance("minecraft:air"));
    }
    @Test void shortGrassWalkable() {
        assertTrue(SpawnWalkabilityPolicy.clearance("minecraft:short_grass"));
    }
    @Test void tallGrassWalkable() {
        assertTrue(SpawnWalkabilityPolicy.clearance("minecraft:tall_grass"));
    }
    @Test void waterNotWalkable() {
        assertFalse(SpawnWalkabilityPolicy.clearance("minecraft:water"));
    }
    @Test void solidRockNotClearance() {
        assertFalse(SpawnWalkabilityPolicy.clearance("minecraft:stone"));
    }
    @Test void dirtPathStableWalkingSupport() {
        assertTrue(SpawnWalkabilityPolicy.footing("minecraft:dirt_path"));
    }
    @Test void originalGrassBlockStableSupport() {
        assertTrue(SpawnWalkabilityPolicy.footing("minecraft:grass_block"));
    }
    @Test void unsafeMaterialsDoNotSupport() {
        assertFalse(SpawnWalkabilityPolicy.footing("minecraft:water"));
        assertFalse(SpawnWalkabilityPolicy.footing("minecraft:sand"));
    }
    @Test void moddedPlantsNotSilentlyAccepted() {
        assertFalse(SpawnWalkabilityPolicy.clearance("foreignmod:ghost_plant"));
    }
    @Test void fireAndCactusNeverAccepted() {
        assertFalse(SpawnWalkabilityPolicy.clearance("minecraft:fire"));
        assertFalse(SpawnWalkabilityPolicy.footing("minecraft:cactus"));
    }
}
