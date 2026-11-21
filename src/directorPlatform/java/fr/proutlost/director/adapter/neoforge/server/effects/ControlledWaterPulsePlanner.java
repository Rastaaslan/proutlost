package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** One explicitly requested LAB displacement, not autonomous flow.
 * The native binding seals original import credit and enforces one durable
 * operation token per event. This pure planner has no execution authority.
 */
public final class ControlledWaterPulsePlanner {
 private ControlledWaterPulsePlanner(){}
 public static ControlledWaterPlanner.Plan plan(Map<BlockPosition,ControlledWaterPlanner.Cell> snapshot,
   Set<BlockPosition> mask,int maximumLevel,int transferUnits){
  Objects.requireNonNull(snapshot);Objects.requireNonNull(mask);
  if(maximumLevel!=8||transferUnits!=1||!mask.containsAll(snapshot.keySet()))
   throw new IllegalArgumentException("one-unit controlled water pulse mask/budget");
  var dimensions=new HashSet<DimensionId>();mask.forEach(p->dimensions.add(p.dimension()));
  if(dimensions.size()>1||snapshot.values().stream().anyMatch(c->c.volume()<0||c.volume()>8))
   throw new IllegalArgumentException("controlled water pulse dimension/level");
  var ordered=snapshot.keySet().stream().sorted(Comparator.comparingInt(BlockPosition::x)
   .thenComparingInt(BlockPosition::y).thenComparingInt(BlockPosition::z)).toList();
  long total=snapshot.values().stream().mapToLong(ControlledWaterPlanner.Cell::volume).sum();
  for(var from:ordered){
   var source=snapshot.get(from);if(source.yielded()||source.volume()!=8)continue;
   for(var step:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
    BlockPosition to;
    try{to=new BlockPosition(from.dimension(),Math.addExact(from.x(),step[0]),from.y(),Math.addExact(from.z(),step[1]));}
    catch(ArithmeticException boundary){continue;}
    var destination=snapshot.get(to);
    if(!mask.contains(to)||destination==null||destination.yielded()||destination.volume()!=7)continue;
    var debited=new ControlledWaterPlanner.Cell(7,source.ownerEpoch(),false);
    var credited=new ControlledWaterPlanner.Cell(8,destination.ownerEpoch(),false);
    var after=new HashMap<>(snapshot);after.put(from,debited);after.put(to,credited);
    long remaining=after.values().stream().mapToLong(ControlledWaterPlanner.Cell::volume).sum();
    if(remaining!=total)throw new IllegalStateException("nonconservative manual water pulse");
    return new ControlledWaterPlanner.Plan(after,List.of(new ControlledWaterPlanner.Transfer(
     from,to,source,destination,debited,credited,1)),remaining);
   }
  }
  return new ControlledWaterPlanner.Plan(snapshot,List.of(),total);
 }
}
