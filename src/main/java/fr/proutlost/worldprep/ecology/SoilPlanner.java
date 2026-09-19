package fr.proutlost.worldprep.ecology;
import fr.proutlost.worldprep.analysis.TerrainAnalysis.Kind;
/** Pure data result; block resolution remains registry/tag driven at integration time. */
public final class SoilPlanner {
 public enum Type{NONE,ROCK,GRAVEL,SAND,DIRT,MUD}
 public record Result(Type type,int depth){public Result{if(depth<0||depth>16)throw new IllegalArgumentException();}}
 public static Result plan(Kind terrain,double moisture,long noise){return switch(terrain){case CLIFF->new Result(Type.ROCK,0);case MOUNTAIN,ROCKY_HIGHLAND->new Result(Type.ROCK,(int)(noise&1));case BEACH->new Result(Type.SAND,2+(int)(noise&1));case STONY_COAST->new Result(Type.GRAVEL,1+(int)(noise&1));case WET_LOWLAND,RIVER_EDGE,LAKE_EDGE->new Result(moisture>.6?Type.MUD:Type.DIRT,3);default->new Result(Type.DIRT,2+(int)Math.floorMod(noise,3));};}
 private SoilPlanner(){}
}
