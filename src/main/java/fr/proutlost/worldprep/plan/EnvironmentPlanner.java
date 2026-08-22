package fr.proutlost.worldprep.plan;

import fr.proutlost.worldprep.analysis.TerrainAnalysis;
import java.util.SplittableRandom;

/** Pure deterministic semantic planner; runtime registry resolution happens at apply validation. */
public final class EnvironmentPlanner {
 public enum Biome { MARINE_DEEP,MARINE,MARINE_SHALLOW,STONY_COAST,COASTAL,OPEN_LOWLAND,LOWLAND_FOREST,HIGHLAND,ROCKY_HIGHLAND,MOUNTAIN }
 public enum Geology { MIXED_STONE,GRANITIC,TUFF_RICH,VOLCANIC }
 public enum Soil { NONE,SAND,GRAVEL,DIRT,COARSE_DIRT,PODZOL,MUD,STONE }
 public enum Habitat { DEEP_MARINE,MARINE,SHALLOW_MARINE,COAST,OPEN_GRASSLAND,FOREST,ROCKY_HIGHLAND,MOUNTAIN }
 public record Cell(Biome biome,Geology geology,Soil soil,int floraDensity,int treeDensity,Habitat habitat){}
 public static Cell plan(TerrainAnalysis a,int x,int z,long seed){long mixed=mix(seed,x,z);double n=new SplittableRandom(mixed).nextDouble();var k=a.classify(x,z);Biome b=switch(k){case DEEP_MARINE->Biome.MARINE_DEEP;case MARINE->Biome.MARINE;case SHALLOW_MARINE->Biome.MARINE_SHALLOW;case COAST->a.slope(x,z)>3?Biome.STONY_COAST:Biome.COASTAL;case HIGHLAND->n<.45?Biome.ROCKY_HIGHLAND:Biome.HIGHLAND;case MOUNTAIN,CLIFF->Biome.MOUNTAIN;default->n<.52?Biome.OPEN_LOWLAND:Biome.LOWLAND_FOREST;};Geology g=n<.18?Geology.GRANITIC:n>.88?Geology.TUFF_RICH:Geology.MIXED_STONE;Soil s=switch(k){case DEEP_MARINE,MARINE->Soil.GRAVEL;case SHALLOW_MARINE,COAST->n<.55?Soil.SAND:Soil.GRAVEL;case CLIFF,MOUNTAIN->Soil.STONE;case HIGHLAND->Soil.COARSE_DIRT;default->b==Biome.LOWLAND_FOREST?Soil.PODZOL:Soil.DIRT;};int flora=(k==TerrainAnalysis.Kind.LOWLAND?(int)(35+n*40):k==TerrainAnalysis.Kind.HIGHLAND?20:0);int trees=b==Biome.LOWLAND_FOREST?(int)(35+n*45):b==Biome.HIGHLAND?12:0;Habitat h=switch(b){case MARINE_DEEP->Habitat.DEEP_MARINE;case MARINE->Habitat.MARINE;case MARINE_SHALLOW->Habitat.SHALLOW_MARINE;case COASTAL,STONY_COAST->Habitat.COAST;case LOWLAND_FOREST->Habitat.FOREST;case ROCKY_HIGHLAND->Habitat.ROCKY_HIGHLAND;case MOUNTAIN->Habitat.MOUNTAIN;default->Habitat.OPEN_GRASSLAND;};return new Cell(b,g,s,flora,trees,h);}
 private static long mix(long s,int x,int z){long v=s^((long)x*0x9E3779B97F4A7C15L)^((long)z*0xC2B2AE3D27D4EB4FL);v^=v>>>30;v*=0xBF58476D1CE4E5B9L;v^=v>>>27;v*=0x94D049BB133111EBL;return v^(v>>>31);}
 private EnvironmentPlanner(){}
}
