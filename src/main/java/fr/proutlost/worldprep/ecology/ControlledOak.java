package fr.proutlost.worldprep.ecology;

import java.util.ArrayList;
import java.util.List;

/** Small explicit development tree, not a replayed configured feature. Coordinates are relative to the root. */
public final class ControlledOak {
    public record Voxel(int x, int y, int z, String blockState) {}
    public static final List<Voxel> SHAPE = shape();
    private static List<Voxel> shape() {
        var result = new ArrayList<Voxel>();
        for (int y = 0; y < 4; y++) result.add(new Voxel(0, y, 0, "minecraft:oak_log[axis=y]"));
        for (int y = 2; y <= 4; y++) for (int z = -2; z <= 2; z++) for (int x = -2; x <= 2; x++) {
            if (x == 0 && z == 0 && y < 4) continue;
            if (Math.abs(x) + Math.abs(z) > (y == 4 ? 2 : 3)) continue;
            result.add(new Voxel(x, y, z, "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]"));
        }
        return List.copyOf(result);
    }
    /** One jittered candidate per global chunk; independent of batch order or selected bounds. */
    public static int rootX(long seed, int chunkX, int chunkZ) {
        return chunkX * 16 + 5 + (int)(VegetationPlanner.unit(seed, chunkX, chunkZ, 721) * 6);
    }
    public static int rootZ(long seed, int chunkX, int chunkZ) {
        return chunkZ * 16 + 5 + (int)(VegetationPlanner.unit(seed, chunkX, chunkZ, 722) * 6);
    }
    public static boolean selected(long seed, int chunkX, int chunkZ) {
        return VegetationPlanner.unit(seed, chunkX, chunkZ, 723) < .65;
    }
    private ControlledOak() {}
}
