package fr.proutlost.worldprep.ecology;
import java.util.Arrays;
/** Immutable coarse habitat metadata. It never mutates or reads a Minecraft world. */
public final class HabitatMap {
 public enum Habitat{FOREST,DENSE_FOREST,OPEN_GRASSLAND,WETLAND,ROCKY_HIGHLAND,MOUNTAIN,COAST,RIVER,SHALLOW_MARINE,MARINE,DEEP_MARINE}
 public record Scores(float forest,float openness,float wetness,float rockiness,float marine){}
 public record Cell(Habitat habitat,Scores scores){}
 private final int originX,originZ,cellSize,width,depth;private final Cell[] cells;
 public HabitatMap(int originX,int originZ,int cellSize,int width,int depth,Cell[] cells){int count=Math.multiplyExact(width,depth);if(cellSize<1||width<1||depth<1||cells.length!=count)throw new IllegalArgumentException();this.originX=originX;this.originZ=originZ;this.cellSize=cellSize;this.width=width;this.depth=depth;this.cells=cells.clone();if(Arrays.stream(this.cells).anyMatch(java.util.Objects::isNull))throw new IllegalArgumentException();}
 public Cell atBlock(int x,int z){int cx=Math.floorDiv(x-originX,cellSize),cz=Math.floorDiv(z-originZ,cellSize);if(cx<0||cz<0||cx>=width||cz>=depth)throw new IndexOutOfBoundsException();return cells[cz*width+cx];}
 public int cellSize(){return cellSize;}
}
