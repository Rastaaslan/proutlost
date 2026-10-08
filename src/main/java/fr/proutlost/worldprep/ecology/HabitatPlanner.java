package fr.proutlost.worldprep.ecology;

/** Final planned-state ecological classifier; output is compact metadata, never a world mutation. */
public final class HabitatPlanner {
 public record Input(int waterDepth,int coastDistance,double wetness,double slope,int altitude,int floraCount,int treeCount,boolean structureContext){}
 public HabitatMap.Habitat classify(Input in){
  if(in.waterDepth()>20)return HabitatMap.Habitat.DEEP_MARINE;if(in.waterDepth()>6)return HabitatMap.Habitat.MARINE;if(in.waterDepth()>0)return HabitatMap.Habitat.SHALLOW_MARINE;if(in.coastDistance()<=4)return HabitatMap.Habitat.COAST;if(in.wetness()>.75&&in.slope()<3)return HabitatMap.Habitat.WETLAND;if(in.altitude()>160||in.slope()>12)return HabitatMap.Habitat.MOUNTAIN;if(in.slope()>6)return HabitatMap.Habitat.ROCKY_HIGHLAND;if(in.treeCount()>=4)return in.treeCount()>=10?HabitatMap.Habitat.DENSE_FOREST:HabitatMap.Habitat.FOREST;return HabitatMap.Habitat.OPEN_GRASSLAND;
 }
}
