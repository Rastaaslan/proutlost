package fr.proutlost.worldprep.biome;

/** Stateless, profile-seeded spatial choices in quart coordinates (64 quarts = 256 blocks).
 * Smooth lattice noise changes a region gradually instead of hashing every four-block column.
 * It does not select world effects, relax a guard, or regenerate a persisted preview. */
public final class SpatialBiomeChoice {
    public static final String REVISION = "coherent-lowland-biomes-1";
    private static final double QUARTS_PER_SAMPLE = 64.0;
    private static final long FOREST_SALT = 0x6a09e667f3bcc909L;
    private static final long VARIANT_SALT = 0xbb67ae8584caa73bL;

    private SpatialBiomeChoice() {}

    public static String lowlandPalette(long seed, int quartX, int quartZ) {
        // Retain the old nominal one-in-four forest choice; correlate it over space.
        return sample(seed ^ FOREST_SALT, quartX, quartZ) < .25
            ? "lowland_forest" : "open_lowland";
    }

    public static double variant(long seed, int quartX, int quartZ) {
        return sample(seed ^ VARIANT_SALT, quartX, quartZ);
    }

    private static double sample(long seed, int quartX, int quartZ) {
        double x = quartX / QUARTS_PER_SAMPLE, z = quartZ / QUARTS_PER_SAMPLE;
        long ix = (long)Math.floor(x), iz = (long)Math.floor(z);
        double tx = smooth(x - ix), tz = smooth(z - iz);
        double north = lerp(value(seed, ix, iz), value(seed, ix + 1, iz), tx);
        double south = lerp(value(seed, ix, iz + 1), value(seed, ix + 1, iz + 1), tx);
        return lerp(north, south, tz);
    }

    private static double smooth(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double value(long seed, long x, long z) {
        long bits = seed ^ (x * 341873128712L) ^ (z * 132897987541L);
        bits = (bits ^ (bits >>> 30)) * 0xbf58476d1ce4e5b9L;
        bits = (bits ^ (bits >>> 27)) * 0x94d049bb133111ebL;
        bits ^= bits >>> 31;
        return (bits >>> 11) * 0x1.0p-53;
    }
}
