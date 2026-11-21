package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.storage.BatchManifestStore;

/**
 * Contra S2: **geometry-only** 17 nonuniform sectors (not a V5 certificate).
 * Every sector contains <=64 8x8-chunk perimeter groups and the 17 rectangles
 * partition the 64516 pre-existing core chunks exactly. Real native structure,
 * fauna, 645160 ecology receipts, A/B backup and nine V5 phases remain mandatory.
 * Other worlds keep their previous strict parent==child ecological area policy.
 * Plan provenance SHA256: 16c98581b8ed13882919f9f87ea2ec8ca961e4df9e12521a3b959f690787ba47
 */
public final class ContraV5SectorLineage {
    public static final String PARENT="proutlost:contra_s2_ecology";
    public static final long SEED=27594267L;
    public static final long PARENT_STAGES=645160L;
    public static final int MIN=16, MAX=4079;
    public static final int SECTOR_COUNT=17;
    public static final String PLAN_SHA256="16c98581b8ed13882919f9f87ea2ec8ca961e4df9e12521a3b959f690787ba47";

    public record Bounds(int minX,int minZ,int maxX,int maxZ) {
        public Bounds {
            if(minX>maxX||minZ>maxZ)throw new IllegalArgumentException("Invalid exact group bounds");
        }
    }
    private static final Bounds[] BOUNDS={
        new Bounds(16,16,527,2063),
        new Bounds(16,2064,527,4079),
        new Bounds(528,16,783,4079),
        new Bounds(784,16,1039,4079),
        new Bounds(1040,16,1295,4079),
        new Bounds(1296,16,1551,4079),
        new Bounds(1552,16,1807,4079),
        new Bounds(1808,16,2063,4079),
        new Bounds(2064,16,2575,2063),
        new Bounds(2576,16,3087,2063),
        new Bounds(3088,16,3599,2063),
        new Bounds(3600,16,4079,2063),
        new Bounds(2064,2064,3087,3087),
        new Bounds(3088,2064,4079,3087),
        new Bounds(2064,3088,2959,4079),
        new Bounds(2960,3088,4079,3471),
        new Bounds(2960,3472,4079,4079)
    };

    static {
        if(BOUNDS.length!=SECTOR_COUNT)throw new ExceptionInInitializerError("Changed 17 sector count");
        var covered=new boolean[32][32];
        int groups=0,chunks=0;
        for(var b:BOUNDS) {
            if(b.minX()<MIN||b.minZ()<MIN||b.maxX()>MAX||b.maxZ()>MAX
                ||(b.minX()-MIN)%128!=0||(b.minZ()-MIN)%128!=0)
                throw new ExceptionInInitializerError("Area not pinned to 8x8 chunk grid");
            int g0x=(b.minX()-MIN)/128,g0z=(b.minZ()-MIN)/128;
            int g1x=(b.maxX()-MIN)/128,g1z=(b.maxZ()-MIN)/128;
            if(g0x<0||g0z<0||g1x>=32||g1z>=32
                ||b.maxX()!=Math.min(MAX,MIN+g1x*128+127)
                ||b.maxZ()!=Math.min(MAX,MIN+g1z*128+127))
                throw new ExceptionInInitializerError("Area coverage coordinate changed");
            int size=(g1x-g0x+1)*(g1z-g0z+1);
            if(size<1||size>64)throw new ExceptionInInitializerError("Native V5 max64 groups exceeded");
            groups+=size;
            chunks+=((b.maxX()>>4)-(b.minX()>>4)+1)*((b.maxZ()>>4)-(b.minZ()>>4)+1);
            for(int x=g0x;x<=g1x;x++)for(int z=g0z;z<=g1z;z++){
                if(covered[x][z])throw new ExceptionInInitializerError("Overlapping V5 sectors");
                covered[x][z]=true;
            }
        }
        if(groups!=1024||chunks!=64516)throw new ExceptionInInitializerError("Core V5 disjoint coverage changed");
        for(var row:covered)for(var used:row)
            if(!used)throw new ExceptionInInitializerError("Uncovered V5 group");
    }

    public static String sectorId(int ordinal) {
        if(ordinal<0||ordinal>=SECTOR_COUNT)
            throw new IllegalArgumentException("Unpinned Contra sector ordinal");
        return "proutlost:contra_s2_partition_"+String.format(java.util.Locale.ROOT,"%02d",ordinal);
    }
    public static Bounds sectorBounds(int ordinal) {
        if(ordinal<0||ordinal>=SECTOR_COUNT)
            throw new IllegalArgumentException("Unpinned Contra sector ordinal");
        return BOUNDS[ordinal];
    }
    private static int ordinal(String id) {
        if(id==null||!id.matches("proutlost:contra_s2_partition_(0[0-9]|1[0-6])"))return -1;
        int n=Integer.parseInt(id.substring(id.length()-2));
        return n<SECTOR_COUNT?n:-1;
    }
    public static boolean wellFormedParent(BatchManifestStore.Header h,String child) {
        if(h==null||h.batchId()==null||ordinal(child)<0)return false;
        return h.schemaVersion()==1&&h.worldSeed()==SEED&&h.stages()==PARENT_STAGES
            &&"minecraft:overworld".equals(h.dimension())&&PARENT.equals(h.area())
            &&h.minX()==MIN&&h.minZ()==MIN&&h.maxX()==MAX&&h.maxZ()==MAX
            &&"ecology-batch-v3".equals(h.pipelineVersion());
    }
    public static boolean isExactOrPinnedSector(BatchManifestStore.Header h,
                                                 WorldPrepSavedData.Area a) {
        if(h==null||a==null||a.id()==null||a.dimension()==null)return false;
        // Historical strict path for non-Contra worlds: exact matching area only.
        if(h.area().equals(a.id()))return true;
        if(!wellFormedParent(h,a.id())||!h.dimension().equals(a.dimension()))return false;
        var b=BOUNDS[ordinal(a.id())];
        return a.minX()==b.minX()&&a.minZ()==b.minZ()
            &&a.maxX()==b.maxX()&&a.maxZ()==b.maxZ();
    }
    public static boolean typedIdentity(BatchManifestStore.Header h,String areaId) {
        return h!=null && (h.area().equals(areaId)||wellFormedParent(h,areaId));
    }
    private ContraV5SectorLineage(){}
}
