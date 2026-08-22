package fr.proutlost.worldprep.geology;

/** Pure deterministic broad-province planner; 64-block cells avoid geological confetti. */
public final class GeologyPlanner {
    public enum Province { MIXED_STONE,GRANITIC,TUFF_RICH,VOLCANIC }
    public static Province province(long seed,int x,int y,int z){long h=mix(seed^((long)Math.floorDiv(x,64)*341873128712L)^((long)Math.floorDiv(z,64)*132897987541L));int n=(int)Math.floorMod(h,100);if(y<-32&&n<30)return Province.VOLCANIC;if(n<25)return Province.GRANITIC;if(n<50)return Province.TUFF_RICH;return Province.MIXED_STONE;}
    public static String replacement(long seed,int x,int y,int z,Province p){long n=Math.floorMod(mix(seed^x*31L^y*131L^z*8191L),100);if(y<0&&n<55)return "minecraft:deepslate";return switch(p){case GRANITIC->n<65?"minecraft:granite":"minecraft:stone";case TUFF_RICH->n<60?"minecraft:tuff":"minecraft:stone";case VOLCANIC->n<45?"minecraft:tuff":n<75?"minecraft:deepslate":"minecraft:andesite";case MIXED_STONE->n<12?"minecraft:diorite":n<24?"minecraft:andesite":"minecraft:stone";};}
    public static long mix(long v){v^=v>>>33;v*=0xff51afd7ed558ccdL;v^=v>>>33;v*=0xc4ceb9fe1a85ec53L;return v^(v>>>33);} private GeologyPlanner(){}
}
