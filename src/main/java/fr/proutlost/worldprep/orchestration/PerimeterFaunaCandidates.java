package fr.proutlost.worldprep.orchestration;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.physical.PhysicalStore;
import net.minecraft.core.BlockPos;
import java.util.*;

/** Finite cow/habitat survey preserving the old chunk-center search as phase 1.
 * Only after all original candidates are exhausted, try eight alternatives per
 * fully existing chunk. Never creates chunk tickets; planner owns no world data.
 */
public final class PerimeterFaunaCandidates {
    public static List<BlockPos> enumerate(WorldPrepSavedData.Area area,long worldSeed,UUID groupOperation) {
        long chunks=(long)((area.maxX()>>4)-(area.minX()>>4)+1)*
            ((area.maxZ()>>4)-(area.minZ()>>4)+1);
        if(chunks<1||chunks>64)throw new IllegalArgumentException("Fauna survey requires 1..64 existing chunks");
        var primary=new LinkedHashSet<BlockPos>();
        var fallback=new LinkedHashSet<BlockPos>();
        for(int cx=area.minX()>>4;cx<=area.maxX()>>4;cx++)
            for(int cz=area.minZ()>>4;cz<=area.maxZ()>>4;cz++) {
                var center=PerimeterCandidateCoordinates.inside(cx,cz,
                    area.minX(),area.minZ(),area.maxX(),area.maxZ());
                primary.add(new BlockPos(center.x(),0,center.z()));
                for(int ox:new int[]{4,8,12})
                    for(int oz:new int[]{4,8,12}) {
                        long x=(long)cx*16+ox,z=(long)cz*16+oz;
                        if(x<area.minX()||x>area.maxX()||z<area.minZ()||z>area.maxZ())continue;
                        fallback.add(new BlockPos((int)x,0,(int)z));
                    }
            }
        fallback.removeAll(primary);
        // Preserve both complete candidate tiers and deterministic seed identity,
        // but keep fallback positions of one existing chunk together. Reusing the
        // same native verified 3x3 chunk halo prevents repeated disk reads/tickets
        // for each of the eight alternate sites. No habitat/rarity rules change.
        var chunkRanks=new HashMap<Long,String>();
        var deterministic=Comparator
            .comparing((BlockPos p)->chunkRanks.computeIfAbsent(
                WorldPrepSavedData.cellKey(p.getX()>>4,p.getZ()>>4),
                key->PhysicalStore.sha(worldSeed+"/"+groupOperation+"/fauna-chunk-v1/"
                    +(p.getX()>>4)+","+(p.getZ()>>4))))
            .thenComparingInt(p->p.getX()>>4)
            .thenComparingInt(p->p.getZ()>>4)
            .thenComparing(p->PhysicalStore.sha(worldSeed+"/"+groupOperation+"/"+p))
            .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ);
        var out=new ArrayList<BlockPos>(primary.size()+fallback.size());
        out.addAll(primary.stream().sorted(deterministic).toList());
        out.addAll(fallback.stream().sorted(deterministic).toList());
        if(out.size()>chunks*9)throw new IllegalStateException("Unbounded candidate survey");
        return List.copyOf(out);
    }
    private PerimeterFaunaCandidates() {}
}
