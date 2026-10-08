package fr.proutlost.worldprep.structure;

import fr.proutlost.worldprep.environment.EnvironmentalMutation.Position;
import java.util.*;

/** Authoritative immutable reservations consumed by natural planners. */
public final class StructureReservationMap {
    public enum Zone { FOOTPRINT, CLEARANCE, TERRAIN_ADAPTATION, OWNED_MUTATION }
    public record Reservation(String instanceId, StructureInstancePlan.Bounds bounds, Zone zone) {
        public Reservation { if(instanceId==null||instanceId.isBlank()||bounds==null||zone==null)throw new IllegalArgumentException(); }
        public boolean contains(Position p){return p.x()>=bounds.minX()&&p.x()<=bounds.maxX()&&p.y()>=bounds.minY()&&p.y()<=bounds.maxY()&&p.z()>=bounds.minZ()&&p.z()<=bounds.maxZ();}
    }
    private final List<Reservation> reservations;
    public StructureReservationMap(Collection<Reservation> reservations){this.reservations=reservations.stream().sorted(Comparator.comparing(Reservation::instanceId).thenComparing(r->r.zone().ordinal())).toList();}
    public static StructureReservationMap from(Collection<StructureInstancePlan> plans){var out=new ArrayList<Reservation>();for(var p:plans){out.add(new Reservation(p.instanceId(),p.footprint(),Zone.FOOTPRINT));out.add(new Reservation(p.instanceId(),p.reservation(),Zone.CLEARANCE));out.add(new Reservation(p.instanceId(),p.envelope(),Zone.TERRAIN_ADAPTATION));}return new StructureReservationMap(out);}
    public boolean reserved(Position position){return reservations.stream().anyMatch(r->r.contains(position));}
    public List<Reservation> reservations(){return reservations;}
}
