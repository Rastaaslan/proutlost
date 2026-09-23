package fr.proutlost.worldprep.structure;

import fr.proutlost.worldprep.environment.EnvironmentalMutation;
import fr.proutlost.worldprep.pipeline.PassId;
import java.util.*;

/** Converts a verified adapter's disposable-world result into a canonical exact diff. */
public final class StructureMaterializer {
    public record CapturedBlock(int x,int y,int z,String beforeState,String afterState,String blockEntityType,String blockEntityNbt){}
    public record Result(List<EnvironmentalMutation> mutations, Set<Long> chunks, StructureReservationMap reservations){}
    public Result materialize(StructureInstancePlan plan, StructureCapability capability, Collection<CapturedBlock> capture) {
        Objects.requireNonNull(plan); Objects.requireNonNull(capability); Objects.requireNonNull(capture);
        if(!capability.structureId().equals(plan.structureId())||!capability.status().productionSupported())throw new IllegalStateException("UNSUPPORTED_OPAQUE: "+plan.structureId());
        UUID group=UUID.nameUUIDFromBytes((plan.instanceId()+":"+plan.seed()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var positions=new HashSet<EnvironmentalMutation.Position>(); var mutations=new ArrayList<EnvironmentalMutation>(); var chunks=new TreeSet<Long>();
        for(var block:capture){var pos=new EnvironmentalMutation.Position(block.x(),block.y(),block.z());if(!inside(plan.envelope(),pos))throw new IllegalStateException("Structure materialization escaped approved envelope");if(!positions.add(pos))throw new IllegalStateException("Duplicate materialized position");mutations.add(new EnvironmentalMutation(PassId.STRUCTURES,block.x(),block.y(),block.z(),block.beforeState(),block.afterState(),block.blockEntityType(),block.blockEntityNbt(),group));chunks.add((((long)Math.floorDiv(block.x(),16))<<32)^(Math.floorDiv(block.z(),16)&0xffffffffL));}
        mutations.sort(Comparator.comparing(EnvironmentalMutation::position));
        return new Result(List.copyOf(mutations),Collections.unmodifiableSet(chunks),StructureReservationMap.from(List.of(plan)));
    }
    private static boolean inside(StructureInstancePlan.Bounds b,EnvironmentalMutation.Position p){return p.x()>=b.minX()&&p.x()<=b.maxX()&&p.y()>=b.minY()&&p.y()<=b.maxY()&&p.z()>=b.minZ()&&p.z()<=b.maxZ();}
}
