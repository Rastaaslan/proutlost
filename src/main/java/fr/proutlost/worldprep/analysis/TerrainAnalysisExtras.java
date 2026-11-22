package fr.proutlost.worldprep.analysis;

import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Compact exact rectangular host-volume census and explicitly estimated surface suitability.
 * One volume cell is an aligned 4x4x4 biome cell, clipped to the selected area/build bounds.
 * No BlockState, chunk or terrain array survives a slice.
 */
public final class TerrainAnalysisExtras {
    public record Geometry(int minX,int minY,int minZ,int maxX,int maxYExclusive,int maxZ) {
        public Geometry {
            if(minX>maxX||minZ>maxZ||minY>=maxYExclusive)throw new IllegalArgumentException("Analysis volume bounds");
            Math.multiplyExact(Math.multiplyExact((long)maxX-minX+1,(long)maxZ-minZ+1),(long)maxYExclusive-minY);
        }
        public long width(){return (long)Math.floorDiv(maxX,4)-Math.floorDiv(minX,4)+1;}
        public long depth(){return (long)Math.floorDiv(maxZ,4)-Math.floorDiv(minZ,4)+1;}
        public long height(){return (long)Math.floorDiv(maxYExclusive-1,4)-Math.floorDiv(minY,4)+1;}
        public long surfaceCells(){return Math.multiplyExact(width(),depth());}
        public long cells(){return Math.multiplyExact(surfaceCells(),height());}
        public long blocks(){return Math.multiplyExact(Math.multiplyExact((long)maxX-minX+1,(long)maxZ-minZ+1),(long)maxYExclusive-minY);}
        public Cell at(long cursor) {
            if(cursor<0||cursor>=cells())throw new IndexOutOfBoundsException("Analysis volume cursor");
            long column=cursor/height();
            int x=Math.toIntExact((Math.floorDiv(minX,4)+column%width())*4);
            int y=Math.toIntExact((Math.floorDiv(minY,4)+cursor%height())*4);
            int z=Math.toIntExact((Math.floorDiv(minZ,4)+column/width())*4);
            return new Cell(Math.max(minX,x),Math.max(minY,y),Math.max(minZ,z),
                Math.min(maxX,x+3),Math.min(maxYExclusive-1,y+3),Math.min(maxZ,z+3));
        }
        /** Exact represented x/z area of the first n raster-ordered surface samples. */
        public long surfaceColumnsBefore(long n) {
            if(n<0||n>surfaceCells())throw new IllegalArgumentException("Surface prefix");
            long rows=n/width(),cols=n%width();
            long zDone=Math.max(0,Math.min((long)maxZ-minZ+1,(Math.floorDiv(minZ,4)+rows)*4-minZ));
            long xDone=Math.max(0,Math.min((long)maxX-minX+1,(Math.floorDiv(minX,4)+cols)*4-minX));
            long rowSize=rows==depth()?0:Math.min((long)maxZ+1,(Math.floorDiv(minZ,4)+rows+1)*4)-Math.max(minZ,(Math.floorDiv(minZ,4)+rows)*4);
            return Math.addExact(Math.multiplyExact((long)maxX-minX+1,zDone),Math.multiplyExact(xDone,rowSize));
        }
        public long blocksBefore(long n) {
            if(n<0||n>cells())throw new IllegalArgumentException("Volume prefix");
            long columns=n/height(),ys=n%height();
            long complete=Math.multiplyExact(surfaceColumnsBefore(columns),(long)maxYExclusive-minY);
            if(columns==surfaceCells())return complete;
            long weight=surfaceColumnsBefore(columns+1)-surfaceColumnsBefore(columns);
            long vertical=Math.max(0,Math.min((long)maxYExclusive-minY,(Math.floorDiv(minY,4)+ys)*4-minY));
            return Math.addExact(complete,Math.multiplyExact(weight,vertical));
        }
    }
    public record Cell(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        public int blocks(){return Math.multiplyExact(Math.multiplyExact(maxX-minX+1,maxZ-minZ+1),maxY-minY+1);}
    }
    public record Volume(long blocks,long geology,long ores,long eligibleGeology,long eligibleOres,long protectedBlocks) {}
    public record Surface(long representedColumns,boolean flora,boolean treeSubstrate,boolean aquatic,String habitat) {}
    public final Geometry geometry;
    public long volumeCells,volumeBlocks,geologyHosts,oreHosts,eligibleGeologyHosts,eligibleOreHosts,protectedVolumeBlocks;
    public long surfaceSamples,representedColumns,floraSamples,treeSubstrateSamples,aquaticSamples;
    public long estimatedFloraColumns,estimatedTreeSubstrateColumns,estimatedAquaticColumns;
    public final Map<String,Long> estimatedHabitatColumns=new TreeMap<>();
    public TerrainAnalysisExtras(Geometry geometry){this.geometry=Objects.requireNonNull(geometry);}
    public void appendSurface(Surface sample) {
        long expected=geometry.surfaceColumnsBefore(surfaceSamples+1)-geometry.surfaceColumnsBefore(surfaceSamples);
        if(sample.representedColumns()!=expected||sample.habitat()==null||sample.habitat().isBlank())throw new IllegalStateException("Exact surface prefix required");
        surfaceSamples++;representedColumns=Math.addExact(representedColumns,expected);
        if(sample.flora()){floraSamples++;estimatedFloraColumns=Math.addExact(estimatedFloraColumns,expected);}
        if(sample.treeSubstrate()){treeSubstrateSamples++;estimatedTreeSubstrateColumns=Math.addExact(estimatedTreeSubstrateColumns,expected);}
        if(sample.aquatic()){aquaticSamples++;estimatedAquaticColumns=Math.addExact(estimatedAquaticColumns,expected);}
        estimatedHabitatColumns.merge(sample.habitat(),expected,Math::addExact);
    }
    public void appendVolume(Volume sample) {
        if(sample.blocks()!=geometry.at(volumeCells).blocks()||!valid(sample))throw new IllegalStateException("Exact volume cell required");
        volumeCells++;volumeBlocks=Math.addExact(volumeBlocks,sample.blocks());geologyHosts=Math.addExact(geologyHosts,sample.geology());
        oreHosts=Math.addExact(oreHosts,sample.ores());eligibleGeologyHosts=Math.addExact(eligibleGeologyHosts,sample.eligibleGeology());
        eligibleOreHosts=Math.addExact(eligibleOreHosts,sample.eligibleOres());protectedVolumeBlocks=Math.addExact(protectedVolumeBlocks,sample.protectedBlocks());
    }
    private static boolean valid(Volume v) {
        return v.blocks()>=0&&v.geology()>=0&&v.geology()<=v.blocks()&&v.ores()>=0&&v.ores()<=v.blocks()
            &&v.eligibleGeology()>=0&&v.eligibleGeology()<=v.geology()&&v.eligibleOres()>=0&&v.eligibleOres()<=v.ores()
            &&v.protectedBlocks()>=0&&v.protectedBlocks()<=v.blocks()
            &&v.eligibleGeology()<=v.blocks()-v.protectedBlocks()&&v.eligibleOres()<=v.blocks()-v.protectedBlocks();
    }
    public void requirePrefix(long completedSurfaceSamples) {
        if(surfaceSamples!=completedSurfaceSamples||surfaceSamples<0||surfaceSamples>geometry.surfaceCells()
                ||representedColumns!=geometry.surfaceColumnsBefore(surfaceSamples)
                ||volumeCells<0||volumeCells>geometry.cells()||volumeBlocks!=geometry.blocksBefore(volumeCells)
                ||volumeCells>0&&surfaceSamples!=geometry.surfaceCells()
                ||!valid(new Volume(volumeBlocks,geologyHosts,oreHosts,eligibleGeologyHosts,eligibleOreHosts,protectedVolumeBlocks))
                ||floraSamples<0||floraSamples>surfaceSamples||treeSubstrateSamples<0||treeSubstrateSamples>surfaceSamples
                ||aquaticSamples<0||aquaticSamples>surfaceSamples||estimatedFloraColumns<0||estimatedFloraColumns>representedColumns
                ||estimatedTreeSubstrateColumns<0||estimatedTreeSubstrateColumns>representedColumns
                ||estimatedAquaticColumns<0||estimatedAquaticColumns>representedColumns
                ||estimatedHabitatColumns.size()>32||estimatedHabitatColumns.values().stream().anyMatch(v->v==null||v<=0)
                ||estimatedHabitatColumns.values().stream().reduce(0L,Math::addExact)!=representedColumns)
            throw new IllegalStateException("Invalid extended analysis prefix");
    }
    public CompoundTag save() {
        var t=new CompoundTag();t.putInt("schema",1);
        t.putInt("minX",geometry.minX());t.putInt("minY",geometry.minY());t.putInt("minZ",geometry.minZ());
        t.putInt("maxX",geometry.maxX());t.putInt("maxYExclusive",geometry.maxYExclusive());t.putInt("maxZ",geometry.maxZ());
        t.putLong("volumeCells",volumeCells);t.putLong("volumeBlocks",volumeBlocks);t.putLong("geologyHosts",geologyHosts);t.putLong("oreHosts",oreHosts);
        t.putLong("eligibleGeologyHosts",eligibleGeologyHosts);t.putLong("eligibleOreHosts",eligibleOreHosts);t.putLong("protectedVolumeBlocks",protectedVolumeBlocks);
        t.putLong("surfaceSamples",surfaceSamples);t.putLong("representedColumns",representedColumns);t.putLong("floraSamples",floraSamples);
        t.putLong("treeSubstrateSamples",treeSubstrateSamples);t.putLong("aquaticSamples",aquaticSamples);t.putLong("estimatedFloraColumns",estimatedFloraColumns);
        t.putLong("estimatedTreeSubstrateColumns",estimatedTreeSubstrateColumns);t.putLong("estimatedAquaticColumns",estimatedAquaticColumns);
        var habitats=new CompoundTag();estimatedHabitatColumns.forEach(habitats::putLong);t.put("estimatedHabitatColumns",habitats);return t;
    }
    public static TerrainAnalysisExtras load(CompoundTag t,long surfaceSamples) {
        if(t.getInt("schema")!=1)throw new IllegalStateException("Unknown extended analysis schema");
        for(String key:List.of("minX","minY","minZ","maxX","maxYExclusive","maxZ"))if(!t.contains(key,Tag.TAG_INT))throw new IllegalStateException("Missing extended analysis bounds");
        for(String key:List.of("volumeCells","volumeBlocks","geologyHosts","oreHosts","eligibleGeologyHosts","eligibleOreHosts","protectedVolumeBlocks","surfaceSamples","representedColumns","floraSamples","treeSubstrateSamples","aquaticSamples","estimatedFloraColumns","estimatedTreeSubstrateColumns","estimatedAquaticColumns"))if(!t.contains(key,Tag.TAG_LONG))throw new IllegalStateException("Missing extended analysis aggregate");
        if(!t.contains("estimatedHabitatColumns",Tag.TAG_COMPOUND))throw new IllegalStateException("Missing habitat estimate histogram");
        var v=new TerrainAnalysisExtras(new Geometry(t.getInt("minX"),t.getInt("minY"),t.getInt("minZ"),t.getInt("maxX"),t.getInt("maxYExclusive"),t.getInt("maxZ")));
        v.volumeCells=t.getLong("volumeCells");v.volumeBlocks=t.getLong("volumeBlocks");v.geologyHosts=t.getLong("geologyHosts");v.oreHosts=t.getLong("oreHosts");
        v.eligibleGeologyHosts=t.getLong("eligibleGeologyHosts");v.eligibleOreHosts=t.getLong("eligibleOreHosts");v.protectedVolumeBlocks=t.getLong("protectedVolumeBlocks");
        v.surfaceSamples=t.getLong("surfaceSamples");v.representedColumns=t.getLong("representedColumns");v.floraSamples=t.getLong("floraSamples");
        v.treeSubstrateSamples=t.getLong("treeSubstrateSamples");v.aquaticSamples=t.getLong("aquaticSamples");v.estimatedFloraColumns=t.getLong("estimatedFloraColumns");
        v.estimatedTreeSubstrateColumns=t.getLong("estimatedTreeSubstrateColumns");v.estimatedAquaticColumns=t.getLong("estimatedAquaticColumns");
        var habitats=t.getCompound("estimatedHabitatColumns");if(habitats.getAllKeys().size()>32)throw new IllegalStateException("Habitat estimate bound");
        for(String key:habitats.getAllKeys()){if(!habitats.contains(key,Tag.TAG_LONG))throw new IllegalStateException("Invalid habitat estimate count");v.estimatedHabitatColumns.put(key,habitats.getLong(key));}
        v.requirePrefix(surfaceSamples);return v;
    }
}
