package fr.proutlost.worldprep.ecology;

import fr.proutlost.worldprep.environment.*;
import fr.proutlost.worldprep.environment.RegistryContentCatalog.Family;
import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.structure.StructureReservationMap;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Materializes the complete small-tree group before it can enter a production plan. */
public final class TreePlanner {
 public Optional<List<EnvironmentalMutation>> plan(long seed,int x,int y,int z,VegetationPlanner.TreeMode mode,double density,double clearing,int height,boolean terrainCompatible,RegistryContentCatalog catalog,StructureReservationMap reservations){
  if(height<3||height>16||!terrainCompatible||!VegetationPlanner.treeCandidate(seed,Math.floorDiv(x,4),Math.floorDiv(z,4),mode,density,clearing))return Optional.empty();
  var log=catalog.resolve(Family.LOG,seed^x^z);var leaves=catalog.resolve(Family.LEAVES,seed^~x^z);if(log.isEmpty()||leaves.isEmpty())return Optional.empty();
  UUID group=UUID.nameUUIDFromBytes((seed+":"+x+":"+y+":"+z).getBytes(StandardCharsets.UTF_8));var out=new ArrayList<EnvironmentalMutation>();
  for(int dy=1;dy<=height;dy++)out.add(mutation(x,y+dy,z,"minecraft:air",log.get(),group));
  int crown=y+height;for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=-1;dy<=1;dy++)if(Math.abs(dx)+Math.abs(dz)+(dy==1?1:0)<=3&&!(dx==0&&dz==0&&dy<=0))out.add(mutation(x+dx,crown+dy,z+dz,"minecraft:air",leaves.get(),group));
  if(out.stream().anyMatch(m->reservations.reserved(m.position())))return Optional.empty();out.sort(Comparator.comparing(EnvironmentalMutation::position));return Optional.of(List.copyOf(out));
 }
 private static EnvironmentalMutation mutation(int x,int y,int z,String before,String after,UUID group){return new EnvironmentalMutation(PassId.TREES,x,y,z,before,after,null,null,group);}
}
