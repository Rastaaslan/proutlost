package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure conservative transfers for non-vanilla-propagating Director fluid cells.
 * Every Transfer is a two-cell pre/post mutation to journal; no fluid is placed here.
 */
public final class ControlledWaterPlanner {
    public record Cell(int volume,Epoch ownerEpoch,boolean yielded) {
        public Cell { if(volume<0)throw new IllegalArgumentException("negative volume");Objects.requireNonNull(ownerEpoch); }
    }
    public record Transfer(BlockPosition from,BlockPosition to,Cell beforeFrom,Cell beforeTo,Cell afterFrom,Cell afterTo,int units) {}
    public record Plan(Map<BlockPosition,Cell> after,List<Transfer> transfers,long remainingVolume) {
        public Plan { after=Map.copyOf(after);transfers=List.copyOf(transfers); }
    }
    private ControlledWaterPlanner() {}
    public static Plan plan(Map<BlockPosition,Cell> snapshot,Set<BlockPosition> mask,int maximumLevel,int transferUnits,long seed) {
        if(maximumLevel<1||transferUnits<0 || !mask.containsAll(snapshot.keySet()))throw new IllegalArgumentException("fluid mask/budget");
        if(snapshot.values().stream().anyMatch(c->c.volume()>maximumLevel))throw new IllegalArgumentException("fluid level");
        Set<DimensionId> dimensions=new HashSet<>();mask.forEach(p->dimensions.add(p.dimension()));
        if(dimensions.size()>1)throw new IllegalArgumentException("mixed dimension mask");
        // Missing cells remain unknown/impermeable; never fabricate an empty destination.
        Map<BlockPosition,Cell> after=new HashMap<>(snapshot);List<Transfer> transfers=new ArrayList<>();
        List<BlockPosition> sources=new ArrayList<>(snapshot.keySet());
        sources.sort(Comparator.comparingInt(BlockPosition::y).thenComparingInt(BlockPosition::x).thenComparingInt(BlockPosition::z));
        SplittableRandom random=new SplittableRandom(seed);int budget=transferUnits;
        for(BlockPosition from:sources) {
            if(budget==0)break;Cell source=after.get(from);if(source.yielded()||source.volume()==0)continue;
            List<BlockPosition> neighbors=new ArrayList<>();
            try {neighbors.add(new BlockPosition(from.dimension(),from.x(),Math.subtractExact(from.y(),1),from.z()));}
            catch(ArithmeticException edge){continue;}
            List<BlockPosition> horizontal=new ArrayList<>();
            int[][] dirs={{1,0},{-1,0},{0,1},{0,-1}};
            for(int[] d:dirs)try{horizontal.add(new BlockPosition(from.dimension(),Math.addExact(from.x(),d[0]),from.y(),Math.addExact(from.z(),d[1])));}catch(ArithmeticException edge){/* outside coordinate domain is impermeable */}
            for(int i=horizontal.size()-1;i>0;i--){int j=random.nextInt(i+1);Collections.swap(horizontal,i,j);}neighbors.addAll(horizontal);
            for(BlockPosition to:neighbors) {
                source=after.get(from);Cell destination=after.get(to);
                if(budget==0||source.volume()==0)break;
                if(!mask.contains(to)||destination==null||destination.yielded())continue;
                int capacity=maximumLevel-destination.volume();
                int gradient=to.y()<from.y()?source.volume():Math.max(0,(source.volume()-destination.volume())/2);
                int units=Math.min(budget,Math.min(capacity,gradient));if(units==0)continue;
                Cell changedSource=new Cell(source.volume()-units,source.ownerEpoch(),false);
                Cell changedDestination=new Cell(destination.volume()+units,destination.ownerEpoch(),false);
                transfers.add(new Transfer(from,to,source,destination,changedSource,changedDestination,units));
                after.put(from,changedSource);after.put(to,changedDestination);budget-=units;
            }
        }
        long original=snapshot.values().stream().mapToLong(Cell::volume).sum();long remaining=after.values().stream().mapToLong(Cell::volume).sum();
        if(original!=remaining)throw new IllegalStateException("nonconservative fluid transfer");
        return new Plan(after,transfers,remaining);
    }
}
