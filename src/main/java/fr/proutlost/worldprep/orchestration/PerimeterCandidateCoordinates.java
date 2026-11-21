package fr.proutlost.worldprep.orchestration;

/**
 * A deterministic per-chunk candidate inside an exact (possibly partial) area.
 * Chunk centers are useful for full cells, but can lie outside an area's clipped
 * border. Never use an out-of-bounds site as a fatal chunk loading failure.
 */
public final class PerimeterCandidateCoordinates {
    public record Candidate(int x, int z) {}

    public static Candidate inside(int chunkX, int chunkZ,
                                   int minX, int minZ, int maxX, int maxZ) {
        if (minX > maxX || minZ > maxZ)
            throw new IllegalArgumentException("Invalid bounded area");
        if (chunkX < (minX >> 4) || chunkX > (maxX >> 4)
                || chunkZ < (minZ >> 4) || chunkZ > (maxZ >> 4))
            throw new IllegalArgumentException("Candidate chunk outside area");
        return new Candidate(clampCenter(chunkX, minX, maxX),
                             clampCenter(chunkZ, minZ, maxZ));
    }

    private static int clampCenter(int chunk, int min, int max) {
        long center = (long) chunk * 16 + 8;
        return (int) Math.max((long) min, Math.min((long) max, center));
    }

    private PerimeterCandidateCoordinates() {}
}
