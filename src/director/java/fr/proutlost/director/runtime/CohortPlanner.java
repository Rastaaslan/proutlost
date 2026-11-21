package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Stable individual spawn intents for a full cohort; caller submits only its per-tick wave.
 * The complete desired cohort is retained, not replaced by particles or one representative.
 */
public final class CohortPlanner {
    private CohortPlanner() {}
    public static List<EffectIntent> spawnIntents(WorldId world,EventInstanceId event,FaunaMigrate migration,
                                                 List<BlockPosition> reservedPositions,Set<ChunkRef> loaded,int availablePersistentSlots) {
        if(migration.cohortSize()<1||reservedPositions.size()!=migration.cohortSize()||availablePersistentSlots<migration.cohortSize()
                ||new HashSet<>(reservedPositions).size()!=reservedPositions.size())throw new IllegalArgumentException("cohort slots");
        List<BlockPosition> positions=reservedPositions.stream().sorted(Comparator.comparing((BlockPosition p)->p.dimension().value()).thenComparingInt(BlockPosition::x).thenComparingInt(BlockPosition::y).thenComparingInt(BlockPosition::z)).toList();
        List<EffectIntent> intents=new ArrayList<>();
        for(var position:positions){
            var chunk=new ChunkRef(position.dimension(),position.x()>>4,position.z()>>4);if(!loaded.contains(chunk))throw new IllegalArgumentException("unloaded spawn corridor");
            var operation=new OperationToken(UUID.nameUUIDFromBytes(("director-spawn-v1\0"+world.value()+"\0"+event.value()+"\0"+migration.species().value()+"\0"+position.dimension().value()+"\0"+position.x()+","+position.y()+","+position.z()).getBytes(StandardCharsets.UTF_8)));
            intents.add(new EffectIntent(operation,new PrimitiveId("proutlost:fauna_migrate"),1,new FaunaMigrate(migration.species(),1,migration.corridorTag(),migration.arrivalPolicy()),new Footprint(position.dimension(),Set.of(chunk),Set.of(position),List.of()),event));
        }
        return List.copyOf(intents);
    }
}
