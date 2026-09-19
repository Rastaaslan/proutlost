package fr.proutlost.worldprep.structure;
public record StructureInstancePlan(String structureId,String instanceId,int x,int y,int z,Rotation rotation,Mirror mirror,long seed,Bounds footprint,Bounds reservation,Bounds envelope) {
 public enum Rotation { NONE,CLOCKWISE_90,CLOCKWISE_180,COUNTERCLOCKWISE_90 } public enum Mirror { NONE,LEFT_RIGHT,FRONT_BACK }
 public record Bounds(int minX,int minY,int minZ,int maxX,int maxY,int maxZ){public Bounds{if(minX>maxX||minY>maxY||minZ>maxZ)throw new IllegalArgumentException("Invalid bounds");} public boolean intersects(Bounds o){return minX<=o.maxX&&maxX>=o.minX&&minY<=o.maxY&&maxY>=o.minY&&minZ<=o.maxZ&&maxZ>=o.minZ;}}
}
