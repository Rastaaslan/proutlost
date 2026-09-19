package fr.proutlost.worldprep.ecology;
/** Positional deterministic density decisions, independent of batching and iteration order. */
public final class VegetationPlanner {
 public enum TreeMode{NO_TREES,SPARSE,NORMAL,DENSE,MANUAL_ONLY}
 public static double unit(long seed,int x,int z,long salt){long v=seed^salt^((long)x*341873128712L)^((long)z*132897987541L);v^=v>>>33;v*=0xff51afd7ed558ccdL;v^=v>>>33;return(v>>>11)*0x1.0p-53;}
 public static boolean flora(long seed,int x,int z,double macroDensity,double patch){return unit(seed,x,z,0x464c4f5241L)<Math.max(0,Math.min(1,macroDensity*patch));}
 public static boolean treeCandidate(long seed,int cellX,int cellZ,TreeMode mode,double forestDensity,double clearing){if(mode==TreeMode.NO_TREES||mode==TreeMode.MANUAL_ONLY)return false;double scale=switch(mode){case SPARSE->.25;case NORMAL->.6;case DENSE->1;default->0;};return clearing<.65&&unit(seed,cellX,cellZ,0x54524545L)<forestDensity*scale;}
 private VegetationPlanner(){}
}
