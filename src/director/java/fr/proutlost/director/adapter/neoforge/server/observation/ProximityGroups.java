package fr.proutlost.director.adapter.neoforge.server.observation;

import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Ephemeral bounded spatial join. Only group DTOs leave this computation, never paths or relationships. */
public final class ProximityGroups {
    private record Cell(DimensionId dimension,int x,int z) {}
    public record Result(Map<UUID,GroupSnapshot> groups,Map<PlayerId,Set<UUID>> memberships,boolean complete,int comparisons) {
        public Result { groups=Map.copyOf(groups);Map<PlayerId,Set<UUID>> copied=new HashMap<>();memberships.forEach((k,v)->copied.put(k,Set.copyOf(v)));memberships=Map.copyOf(copied); }
    }
    private ProximityGroups(){}
    public static Result build(Map<PlayerId,PlayerSnapshot> players,int cellBlocks,int maxComparisons,Tick now) {
        return build(players,cellBlocks,maxComparisons,now,new ObservationBudget(Integer.MAX_VALUE,Double.MAX_VALUE,System::nanoTime));
    }
    public static Result build(Map<PlayerId,PlayerSnapshot> players,int cellBlocks,int maxComparisons,Tick now,ObservationBudget budget) {
        if(cellBlocks<=0||maxComparisons<0)throw new IllegalArgumentException("group budgets");
        Map<Cell,List<PlayerId>> cells=new HashMap<>();Map<PlayerId,PlayerId> parent=new HashMap<>();
        for(var id:players.keySet()) {
            if(!budget.take())return new Result(Map.of(),Map.of(),false,0);
            var p=players.get(id);if(p.eligibility()!=PlayerEligibility.ELIGIBLE)continue;
            var pos=p.position().position();cells.computeIfAbsent(new Cell(pos.dimension(),Math.floorDiv(pos.x(),cellBlocks),Math.floorDiv(pos.z(),cellBlocks)),k->new ArrayList<>()).add(id);parent.put(id,id);
        }
        int comparisons=0;boolean complete=true;
        outer:for(PlayerId id:parent.keySet()) {
            if(!budget.take())return new Result(Map.of(),Map.of(),false,comparisons);
            var p=players.get(id).position().position();int cx=Math.floorDiv(p.x(),cellBlocks),cz=Math.floorDiv(p.z(),cellBlocks);
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(PlayerId other:cells.getOrDefault(new Cell(p.dimension(),cx+dx,cz+dz),List.of())) {
                if(!budget.take())return new Result(Map.of(),Map.of(),false,comparisons);
                if(id.compareTo(other)>=0)continue;
                if(comparisons==maxComparisons){complete=false;break outer;}comparisons++;
                var q=players.get(other).position().position();double x=(double)p.x()-q.x(),y=(double)p.y()-q.y(),z=(double)p.z()-q.z();
                if(x*x+y*y+z*z<=(double)cellBlocks*cellBlocks) {
                    PlayerId a=root(parent,id),b=root(parent,other);if(a.compareTo(b)<0)parent.put(b,a);else parent.put(a,b);
                }
            }
        }
        // A partial dense-neighborhood join must not claim anyone is isolated.
        if(!complete)return new Result(Map.of(),Map.of(),false,comparisons);
        Map<PlayerId,Set<PlayerId>> components=new TreeMap<>();
        for(var id:parent.keySet()) {
            if(!budget.take())return new Result(Map.of(),Map.of(),false,comparisons);
            components.computeIfAbsent(root(parent,id),k->new TreeSet<>()).add(id);
        }
        Map<UUID,GroupSnapshot> groups=new HashMap<>();Map<PlayerId,Set<UUID>> memberships=new HashMap<>();
        for(var members:components.values()) {
            if(!budget.take())return new Result(Map.of(),Map.of(),false,comparisons);
            if(members.size()<2)continue;
            StringBuilder identity=new StringBuilder("proutlost_director:group");
            for(var id:members){if(!budget.take())return new Result(Map.of(),Map.of(),false,comparisons);identity.append('/').append(id.value());}
            UUID group=UUID.nameUUIDFromBytes(identity.toString().getBytes(StandardCharsets.UTF_8));Set<RegionId> regions=new HashSet<>();members.forEach(id->regions.addAll(players.get(id).regionIds()));
            groups.put(group,new GroupSnapshot(group,members,regions,now,new Revision(now.value())));members.forEach(id->memberships.put(id,Set.of(group)));
        }
        return new Result(groups,memberships,true,comparisons);
    }
    private static PlayerId root(Map<PlayerId,PlayerId> parents,PlayerId id){while(!parents.get(id).equals(id))id=parents.get(id);return id;}
}
