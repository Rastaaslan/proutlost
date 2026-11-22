package fr.proutlost.worldprep.storage;

import fr.proutlost.worldprep.plan.CanonicalDigest;
import java.util.Objects;

/** Canonical terrain input census. The caller owns the actor and supplies only already loaded cells. */
public final class TerrainInputFingerprint {
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxYExclusive, int maxZ) {
        public Bounds {
            if (maxX < minX || maxZ < minZ || maxYExclusive <= minY)
                throw new IllegalArgumentException("Invalid terrain input bounds");
            volume(minX, minY, minZ, maxX, maxYExclusive, maxZ);
        }
        private static long volume(int x0, int y0, int z0, int x1, int y1, int z1) {
            return Math.multiplyExact(Math.multiplyExact((long)x1 - x0 + 1, (long)z1 - z0 + 1),
                (long)y1 - y0);
        }
        public long cells() { return volume(minX, minY, minZ, maxX, maxYExclusive, maxZ); }
        public int x(long cursor) {
            requireCursor(cursor);
            return Math.toIntExact(minX + (cursor / height()) % width());
        }
        public int y(long cursor) {
            requireCursor(cursor);
            return Math.toIntExact(minY + cursor % height());
        }
        public int z(long cursor) {
            requireCursor(cursor);
            return Math.toIntExact(minZ + cursor / height() / width());
        }
        private long width() { return (long)maxX - minX + 1; }
        private long height() { return (long)maxYExclusive - minY; }
        private void requireCursor(long cursor) {
            if (cursor < 0 || cursor >= cells()) throw new IllegalArgumentException("Terrain cursor outside bounds");
        }
    }

    @FunctionalInterface
    public interface Reader { String state(int x, int y, int z); }

    /** Mutable only on its owning actor. No world reference is captured or passed to a worker. */
    public static final class Cursor {
        private final Bounds bounds;
        private final CanonicalDigest digest;
        private long scanned;
        private String completed;
        public Cursor(Bounds bounds) {
            this.bounds = Objects.requireNonNull(bounds);
            digest = CanonicalDigest.sha256().putString("worldprep-terrain-input-v1")
                .putInt(bounds.minX()).putInt(bounds.minY()).putInt(bounds.minZ())
                .putInt(bounds.maxX()).putInt(bounds.maxYExclusive()).putInt(bounds.maxZ());
        }
        public Bounds bounds() { return bounds; }
        public long scanned() { return scanned; }
        public boolean complete() { return scanned == bounds.cells(); }
        public int advance(int cellBudget, Reader reader) {
            if (cellBudget < 1) throw new IllegalArgumentException("Positive terrain cell budget required");
            Objects.requireNonNull(reader);
            int used = 0;
            while (used < cellBudget && !complete()) {
                int x = bounds.x(scanned), y = bounds.y(scanned), z = bounds.z(scanned);
                String state = Objects.requireNonNull(reader.state(x, y, z), "Missing observed terrain state");
                digest.putInt(x).putInt(y).putInt(z).putString(state);
                scanned++;
                used++;
            }
            return used;
        }
        public String fingerprint() {
            if (!complete()) throw new IllegalStateException("Terrain input census is incomplete");
            if (completed == null) completed = digest.finish();
            return completed;
        }
    }

    private TerrainInputFingerprint() {}
}
