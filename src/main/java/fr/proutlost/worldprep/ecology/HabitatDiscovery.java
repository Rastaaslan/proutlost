package fr.proutlost.worldprep.ecology;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded pure computations only. Inputs contain no Level, chunks, registries or SavedData. */
public final class HabitatDiscovery {
    public record Sample(int x, int z, int floorY, int waterDepth, boolean forest, boolean protectedColumn) {}
    public record Cell(int x, int z, int floorY, String habitat, String support) {}

    public static List<Cell> classify(List<Sample> input, boolean parallel) throws InterruptedException {
        var samples = List.copyOf(input);
        if (samples.size() > 256) throw new IllegalArgumentException("Discovery batch exceeds 256 samples");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Discovery interrupted");
        if (!parallel) {
            var result = new ArrayList<Cell>();
            for (var sample : samples) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Discovery interrupted");
                result.add(classify(sample));
            }
            return List.copyOf(result);
        }
        var pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256));
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Cell>>();
            for (var sample : samples) futures.add(pool.submit(() -> classify(sample)));
            var result = new ArrayList<Cell>();
            // Arrival order cannot influence serialized results. Minecraft writes happen only at the caller.
            for (var future : futures) result.add(future.get());
            return List.copyOf(result);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Habitat discovery failed", exception.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private static Cell classify(Sample s) {
        String habitat = s.waterDepth > 20 ? "DEEP_MARINE" : s.waterDepth > 6 ? "MARINE"
                : s.waterDepth > 0 ? "SHALLOW_MARINE" : s.forest ? "FOREST" : "OPEN_GRASSLAND";
        return new Cell(s.x, s.z, s.floorY, habitat, s.protectedColumn ? "PROTECTED" : "OBSERVED_CANDIDATE");
    }
    private HabitatDiscovery() {}
}
