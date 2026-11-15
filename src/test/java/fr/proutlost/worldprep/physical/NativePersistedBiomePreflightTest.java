package fr.proutlost.worldprep.physical;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static fr.proutlost.worldprep.physical.NativeRegistryStructurePass.*;

class NativePersistedBiomePreflightTest {
    @Test void visitsOnlyQuartCellsIntersectingActualBounds() throws Exception {
        var visited = new HashSet<BiomeQuart>();
        requirePersistedBiomeCompatibility(Conditions.NATIVE,
            new Bounds(1,-3,9,6,2,12), new ChunkPos(0,0), cell -> {
                assertTrue(visited.add(cell), "Every quart cell is sampled once");
                return true;
            });
        assertEquals(Set.of(
            new BiomeQuart(0,-1,2), new BiomeQuart(0,0,2),
            new BiomeQuart(0,-1,3), new BiomeQuart(0,0,3),
            new BiomeQuart(1,-1,2), new BiomeQuart(1,0,2),
            new BiomeQuart(1,-1,3), new BiomeQuart(1,0,3)), visited);
    }

    @Test void negativeBoundsUseFloorDivisionAndCurrentChunkIntersection() throws Exception {
        var visited = new HashSet<BiomeQuart>();
        requirePersistedBiomeCompatibility(Conditions.NATIVE,
            new Bounds(-3,-5,13,20,8,18), new ChunkPos(-1,0), cell -> {
                visited.add(cell);
                return true;
            });
        assertEquals(Set.of(new BiomeQuart(-1,-2,3), new BiomeQuart(-1,-1,3),
            new BiomeQuart(-1,0,3), new BiomeQuart(-1,1,3), new BiomeQuart(-1,2,3)), visited);
    }

    @Test void nextChunkReceivesAbsoluteQuartCoordinates() throws Exception {
        var visited = new HashSet<BiomeQuart>();
        requirePersistedBiomeCompatibility(Conditions.NATIVE,
            new Bounds(-3,8,13,20,11,18), new ChunkPos(0,1), cell -> {
                visited.add(cell);
                return true;
            });
        assertEquals(Set.of(new BiomeQuart(0,2,4), new BiomeQuart(1,2,4),
            new BiomeQuart(2,2,4), new BiomeQuart(3,2,4)), visited);
    }

    @Test void everyHeightIsCheckedIncludingTheLastWritableQuart() throws Exception {
        var visited = new HashSet<BiomeQuart>();
        requirePersistedBiomeCompatibility(Conditions.NATIVE,
            new Bounds(0,-64,0,3,319,3), new ChunkPos(0,0), cell -> {
                visited.add(cell);
                return true;
            });
        assertEquals(96, visited.size());
        assertTrue(visited.contains(new BiomeQuart(0,-16,0)));
        assertTrue(visited.contains(new BiomeQuart(0,79,0)));
        var error = assertThrows(IOException.class, () -> requirePersistedBiomeCompatibility(
            Conditions.NATIVE, new Bounds(0,-64,0,3,319,3), new ChunkPos(0,0),
            cell -> cell.y() != 79));
        assertTrue(error.getMessage().contains("quart=(0,79,0)"));
    }

    @Test void mismatchedUndergroundCellRejectsNativePlacement() {
        var visits = new AtomicInteger();
        var error = assertThrows(IOException.class, () -> requirePersistedBiomeCompatibility(
            Conditions.NATIVE, new Bounds(16,-49,32,19,-43,35), new ChunkPos(1,2), cell -> {
                visits.incrementAndGet();
                return cell.y() != -12;
            }));
        assertEquals(2, visits.get());
        assertTrue(error.getMessage().contains("quart=(4,-12,8)"));
    }

    @Test void explicitLabModesDoNotConsultTheNativeBiomePredicate() throws Exception {
        for (var mode : new Conditions[]{Conditions.OVERRIDE_BIOME, Conditions.FORCED}) {
            requirePersistedBiomeCompatibility(mode, new Bounds(0,-64,0,15,319,15),
                new ChunkPos(0,0), cell -> { throw new AssertionError("LAB mode predicate consulted"); });
        }
    }

    @Test void aChunkOutsideActualFootprintFailsBeforeSampling() {
        assertThrows(IOException.class, () -> requirePersistedBiomeCompatibility(
            Conditions.NATIVE, new Bounds(0,-32,0,15,-16,15), new ChunkPos(1,0),
            cell -> { throw new AssertionError("Outside footprint sampled"); }));
    }
}
