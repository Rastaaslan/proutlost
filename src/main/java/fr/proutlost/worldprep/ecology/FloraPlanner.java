package fr.proutlost.worldprep.ecology;

import fr.proutlost.worldprep.environment.*;
import fr.proutlost.worldprep.environment.RegistryContentCatalog.Family;
import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.structure.StructureReservationMap;
import java.util.*;

/** Deterministic patch planner producing exact registered block mutations. */
public final class FloraPlanner {
 public Optional<EnvironmentalMutation> plan(long seed,int x,int surfaceY,int z,String before,double density,double patch,boolean compatible,RegistryContentCatalog catalog,StructureReservationMap reservations){
  var pos=new EnvironmentalMutation.Position(x,surfaceY+1,z);if(!compatible||reservations.reserved(pos)||!VegetationPlanner.flora(seed,x,z,density,patch))return Optional.empty();
  return catalog.resolve(Family.FLORA,mix(seed,x,z)).map(state->new EnvironmentalMutation(PassId.FLORA,x,surfaceY+1,z,before,state,null,null,null));
 }
 private static long mix(long seed,int x,int z){return seed^((long)x*341873128712L)^((long)z*132897987541L);}
}
