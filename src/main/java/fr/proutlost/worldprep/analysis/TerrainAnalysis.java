package fr.proutlost.worldprep.analysis;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Compact immutable analysis derived from real sampled heights and water surfaces. */
public final class TerrainAnalysis {
    public enum Kind { DEEP_MARINE, MARINE, SHALLOW_MARINE, COAST, LOWLAND, HIGHLAND, MOUNTAIN, CLIFF }
    private final int width,depth,seaLevel; private final short[] surface, waterDepth, coastDistance; private final float[] slope,roughness;
    private TerrainAnalysis(int w,int d,int sea,short[] s,short[] wd,short[] cd,float[] sl,float[] r){width=w;depth=d;seaLevel=sea;surface=s;waterDepth=wd;coastDistance=cd;slope=sl;roughness=r;}
    public static TerrainAnalysis analyze(int width,int depth,int seaLevel,int[] heights,boolean[] water) {
        if(width<=0||depth<=0||heights.length!=width*depth||water.length!=heights.length) throw new IllegalArgumentException("Invalid grid");
        short[] h=new short[heights.length], wd=new short[h.length], cd=new short[h.length]; float[] sl=new float[h.length], rough=new float[h.length];
        for(int i=0;i<h.length;i++){h[i]=(short)heights[i];wd[i]=(short)Math.max(0,seaLevel-heights[i]);}
        Arrays.fill(cd,Short.MAX_VALUE); ArrayDeque<Integer> q=new ArrayDeque<>();
        for(int z=0;z<depth;z++)for(int x=0;x<width;x++){int i=z*width+x;if(isBoundary(width,depth,water,x,z)){cd[i]=0;q.add(i);}}
        while(!q.isEmpty()){int i=q.remove(),x=i%width,z=i/width,n=cd[i]+1;int[] ns={i-1,i+1,i-width,i+width};for(int k=0;k<4;k++){int nx=k==0?x-1:k==1?x+1:x,nz=k==2?z-1:k==3?z+1:z;if(nx>=0&&nx<width&&nz>=0&&nz<depth&&n<cd[ns[k]]){cd[ns[k]]=(short)Math.min(n,Short.MAX_VALUE);q.add(ns[k]);}}}
        for(int z=0;z<depth;z++)for(int x=0;x<width;x++){int i=z*width+x;int lo=heights[i],hi=lo,maxDelta=0,count=0;double sum=0;for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){int nx=x+dx,nz=z+dz;if(nx>=0&&nx<width&&nz>=0&&nz<depth){int v=heights[nz*width+nx];lo=Math.min(lo,v);hi=Math.max(hi,v);sum+=v;count++;maxDelta=Math.max(maxDelta,Math.abs(v-heights[i]));}}sl[i]=maxDelta;rough[i]=(float)(hi-lo);}
        return new TerrainAnalysis(width,depth,seaLevel,h,wd,cd,sl,rough);
    }
    private static boolean isBoundary(int w,int d,boolean[] water,int x,int z){boolean v=water[z*w+x];return (x>0&&water[z*w+x-1]!=v)||(x+1<w&&water[z*w+x+1]!=v)||(z>0&&water[(z-1)*w+x]!=v)||(z+1<d&&water[(z+1)*w+x]!=v);}
    private int i(int x,int z){if(x<0||z<0||x>=width||z>=depth)throw new IndexOutOfBoundsException();return z*width+x;}
    public int surface(int x,int z){return surface[i(x,z)];} public int waterDepth(int x,int z){return waterDepth[i(x,z)];} public int coastDistance(int x,int z){return coastDistance[i(x,z)];} public float slope(int x,int z){return slope[i(x,z)];} public float roughness(int x,int z){return roughness[i(x,z)];}
    public Kind classify(int x,int z){int i=i(x,z);if(waterDepth[i]>20)return Kind.DEEP_MARINE;if(waterDepth[i]>6)return Kind.MARINE;if(waterDepth[i]>0)return Kind.SHALLOW_MARINE;if(surface[i]>=seaLevel+70)return Kind.MOUNTAIN;if(slope[i]>=8)return Kind.CLIFF;if(coastDistance[i]<=2)return Kind.COAST;if(surface[i]>=seaLevel+30)return Kind.HIGHLAND;return Kind.LOWLAND;}
    public int width(){return width;} public int depth(){return depth;}
}
