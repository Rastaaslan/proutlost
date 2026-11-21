package fr.proutlost.worldprep.area;

import java.util.Objects;

/** Inclusive, normalized X/Z selection. */
public record WorldPrepArea(String id, String dimension, int minX, int minZ, int maxX, int maxZ) {
    public WorldPrepArea {
        Objects.requireNonNull(id); Objects.requireNonNull(dimension);
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Area id must be namespaced");
        if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Bounds must be normalized");
    }
    public static WorldPrepArea between(String id, String dimension, int x1, int z1, int x2, int z2) {
        return new WorldPrepArea(id, dimension, Math.min(x1,x2), Math.min(z1,z2), Math.max(x1,x2), Math.max(z1,z2));
    }
    public int width() { return Math.addExact(Math.subtractExact(maxX,minX),1); }
    public int depth() { return Math.addExact(Math.subtractExact(maxZ,minZ),1); }
    public long blocks() { return Math.multiplyExact((long) width(),depth()); }
    public boolean contains(int x,int z) { return x>=minX&&x<=maxX&&z>=minZ&&z<=maxZ; }
    public boolean overlaps(WorldPrepArea b) { return dimension.equals(b.dimension)&&minX<=b.maxX&&maxX>=b.minX&&minZ<=b.maxZ&&maxZ>=b.minZ; }
}
