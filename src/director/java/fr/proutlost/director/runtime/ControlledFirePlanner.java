package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Bounded ignition proposals. Vanilla autonomous fire is not represented as controlled fire.
 * Ignition consumes source energy and requires a known, captureable, unyielded target in mask.
 */
public final class ControlledFirePlanner {
    public record Fuel(long energy,Epoch ownerEpoch,boolean captureable,boolean yielded) {
        public Fuel { if(energy<0)throw new IllegalArgumentException("fire energy");Objects.requireNonNull(ownerEpoch); }
    }
    public record Ignition(BlockPosition source,BlockPosition target,long transferredEnergy,Epoch targetEpoch) {}
    public record Plan(Map<BlockPosition,Long> remainingEnergy,List<Ignition> ignitions) {
        public Plan {remainingEnergy=Map.copyOf(remainingEnergy);ignitions=List.copyOf(ignitions);}
    }
    private ControlledFirePlanner() {}
    public static Plan plan(Map<BlockPosition,Fuel> cells,Set<BlockPosition> mask,DurationTicks elapsed,
                            double probability,int maximumIgnitions,long maximumIgnitionEnergy,long seed,boolean stabilizing) {
        if(!Double.isFinite(probability)||probability<0||probability>1||maximumIgnitions<0||maximumIgnitionEnergy<1||!mask.containsAll(cells.keySet()))throw new IllegalArgumentException("fire plan bounds");
        Map<BlockPosition,Long> energy=new HashMap<>();cells.forEach((p,c)->energy.put(p,Math.max(0,c.energy()-Math.min(c.energy(),elapsed.value()))));
        List<Ignition> ignitions=new ArrayList<>();if(stabilizing)return new Plan(energy,ignitions);
        List<BlockPosition> sources=cells.keySet().stream().sorted(Comparator.comparing((BlockPosition p)->p.dimension().value()).thenComparingInt(BlockPosition::x).thenComparingInt(BlockPosition::y).thenComparingInt(BlockPosition::z)).toList();
        SplittableRandom random=new SplittableRandom(seed);Set<BlockPosition> ignited=new HashSet<>();
        int[][] directions={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for(BlockPosition source:sources) {
            Fuel fuel=cells.get(source);if(fuel.yielded()||!fuel.captureable()||fuel.energy()==0)continue;
            for(int[] d:directions) {
                if(ignitions.size()>=maximumIgnitions||energy.get(source)==0)break;
                BlockPosition target;
                try{target=new BlockPosition(source.dimension(),Math.addExact(source.x(),d[0]),Math.addExact(source.y(),d[1]),Math.addExact(source.z(),d[2]));}catch(ArithmeticException edge){continue;}
                Fuel next=cells.get(target);
                if(!mask.contains(target)||next==null||!next.captureable()||next.yielded()||next.energy()>0||ignited.contains(target))continue;
                if(random.nextDouble()>=probability)continue;
                long transferred=Math.min(maximumIgnitionEnergy,energy.get(source));
                energy.put(source,energy.get(source)-transferred);energy.put(target,transferred);ignited.add(target);
                ignitions.add(new Ignition(source,target,transferred,next.ownerEpoch()));
            }
        }
        return new Plan(energy,ignitions);
    }
}
