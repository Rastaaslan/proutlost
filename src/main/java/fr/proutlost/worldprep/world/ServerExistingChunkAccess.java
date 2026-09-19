package fr.proutlost.worldprep.world;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The only production WorldPrep gateway to a live chunk.  {@code getChunkNow}
 * never creates a ticket, loads from disk, generates, or promotes a chunk.
 * Consequently an existing but currently unloaded chunk is a clean refusal;
 * operators must explicitly load and preflight the candidate before compiling.
 */
public final class ServerExistingChunkAccess {
    public enum Refusal {
        WRONG_DIMENSION, OUTSIDE_AREA, OUTSIDE_BORDER, OUTSIDE_BUILD_HEIGHT, MISSING_OR_INCOMPLETE_CHUNK
    }

    public static final class Refused extends IllegalStateException {
        private final Refusal refusal;

        public Refused(Refusal refusal, String detail) {
            super(refusal + ": " + detail);
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    public static LevelChunk requireBlock(ServerLevel level, WorldPrepSavedData.Area area, int x, int y, int z) {
        String actualDimension = level.dimension().location().toString();
        if (!area.dimension().equals(actualDimension)) {
            throw new Refused(Refusal.WRONG_DIMENSION, actualDimension);
        }
        if (x < area.minX() || x > area.maxX() || z < area.minZ() || z > area.maxZ()) {
            throw new Refused(Refusal.OUTSIDE_AREA, x + "," + z);
        }
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
            throw new Refused(Refusal.OUTSIDE_BUILD_HEIGHT, Integer.toString(y));
        }
        if (!level.getWorldBorder().isWithinBounds(new BlockPos(x, y, z))) {
            throw new Refused(Refusal.OUTSIDE_BORDER, x + "," + z);
        }
        return requireChunk(level, x >> 4, z >> 4);
    }

    public static LevelChunk requireSample(ServerLevel level, int x, int z) {
        if (!level.getWorldBorder().isWithinBounds(new BlockPos(x, level.getMinBuildHeight(), z))) {
            throw new Refused(Refusal.OUTSIDE_BORDER, x + "," + z);
        }
        return requireChunk(level, x >> 4, z >> 4);
    }

    public static LevelChunk requireChunk(ServerLevel level, int chunkX, int chunkZ) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            throw new Refused(Refusal.MISSING_OR_INCOMPLETE_CHUNK, chunkX + "," + chunkZ);
        }
        return chunk;
    }

    private ServerExistingChunkAccess() {}
}
