package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.api.model.BlockPosition;
import fr.proutlost.director.api.model.ChunkRef;
import fr.proutlost.director.api.model.DimensionId;
import java.util.*;

/** Immutable topology gate for a physically meaningful, bounded animal migration.
 * This is geometry only: native collisions, ownership and J04 durability have
 * additional independent gates. An empty walk, cycle or teleport is not a move.
 */
final class FaunaRouteGeometryFence {
    private FaunaRouteGeometryFence() {}

    static boolean valid(List<BlockPosition> cells, DimensionId dimension, ChunkRef ownerChunk) {
        if (cells==null || dimension==null || ownerChunk==null ||
                !ownerChunk.dimension().equals(dimension) ||
                cells.size()<2 || cells.size()>64) return false;
        Set<BlockPosition> visited=new HashSet<>();
        BlockPosition previous=null;
        for (BlockPosition cell:cells) {
            if (cell==null || !cell.dimension().equals(dimension) || !visited.add(cell))
                return false;
            if (previous==null) {
                if ((cell.x()>>4)!=ownerChunk.x() || (cell.z()>>4)!=ownerChunk.z())
                    return false;
            } else {
                long dx=Math.abs((long)cell.x()-previous.x());
                long dz=Math.abs((long)cell.z()-previous.z());
                long dy=Math.abs((long)cell.y()-previous.y());
                if (dx+dz!=1 || dy>1) return false;
            }
            previous=cell;
        }
        return true;
    }
}
