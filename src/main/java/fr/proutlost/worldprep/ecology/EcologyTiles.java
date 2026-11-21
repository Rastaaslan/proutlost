package fr.proutlost.worldprep.ecology;

/** Lazy, globally chunk-aligned tiling. Never allocates an island-sized list. */
public record EcologyTiles(int minX, int minZ, int maxX, int maxZ) {
    public record Tile(int minX, int minZ, int maxX, int maxZ) {}
    public EcologyTiles {
        if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Inverted bounds");
    }
    public long width() { return (long)(maxX >> 4) - (minX >> 4) + 1; }
    public long count() { return width() * ((long)(maxZ >> 4) - (minZ >> 4) + 1); }
    public Tile at(long index) {
        if (index < 0 || index >= count()) throw new IndexOutOfBoundsException();
        int x = (int)(((long)(minX >> 4) + index % width()) * 16);
        int z = (int)(((long)(minZ >> 4) + index / width()) * 16);
        return new Tile(Math.max(minX, x), Math.max(minZ, z), Math.min(maxX, x + 15), Math.min(maxZ, z + 15));
    }
}
