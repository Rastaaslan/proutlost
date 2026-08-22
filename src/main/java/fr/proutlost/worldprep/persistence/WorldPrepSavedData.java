package fr.proutlost.worldprep.persistence;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Versioned, dimension-local authoritative state for the biome vertical slice. */
public final class WorldPrepSavedData extends SavedData {
    public static final int SCHEMA = 2;
    public static final String FILE_ID = "proutlost_worldprep";
    public static final Factory<WorldPrepSavedData> FACTORY = new Factory<>(WorldPrepSavedData::new, WorldPrepSavedData::load);

    public record Selection(Integer x1,Integer z1,Integer x2,Integer z2) {}
    public record Area(String id,String dimension,int minX,int minZ,int maxX,int maxZ) {
        public boolean overlaps(Area b){return dimension.equals(b.dimension)&&minX<=b.maxX&&maxX>=b.minX&&minZ<=b.maxZ&&maxZ>=b.minZ;}
    }
    public record ProtectedZone(String id,String dimension,int minX,int minZ,int maxX,int maxZ){
        public boolean intersectsQuart(int qx,int qz){int x=qx<<2,z=qz<<2;return dimension.equals(this.dimension)&&x<=maxX&&x+3>=minX&&z<=maxZ&&z+3>=minZ;}
    }
    public record OverrideCell(int quartX,int quartZ,String exactBiome,String palette){}
    public enum Operation{ANALYZE,PREVIEW_BIOMES,APPLY_BIOMES,ROLLBACK_BIOMES,PREVIEW_GEOLOGY,APPLY_GEOLOGY,ROLLBACK_GEOLOGY,PREVIEW_ORES,APPLY_ORES,ROLLBACK_ORES}
    public enum JobState{QUEUED,RUNNING,PAUSED,COMPLETED,CANCELLED,FAILED}
    public record Job(UUID id,Operation operation,String area,JobState state,long cursor,String error,UUID snapshotId){
        public Job withState(JobState value){return new Job(id,operation,area,value,cursor,error,snapshotId);}
        public Job withCursor(long value){return new Job(id,operation,area,state,value,error,snapshotId);}
        public Job failed(String value){return new Job(id,operation,area,JobState.FAILED,cursor,value,snapshotId);}
        public Job withSnapshot(UUID value){return new Job(id,operation,area,state,cursor,error,value);}
    }
    public record PlanInput(String dimension,int minX,int minZ,int maxX,int maxZ,long seed,String profile,String profileDigest,String configDigest,String registryDigest,String overrideDigest,String protectionDigest){}
    public record BiomeCell(int quartX,int quartZ,String biome,long terrainSignature){}
    public record SnapshotCell(int quartX,int quartY,int quartZ,String beforeBiome,String appliedBiome){}
    public static final class Plan{
        public final String area;public final PlanInput input;public String fingerprint;public final Map<Long,BiomeCell> cells=new LinkedHashMap<>();
        public Plan(String area,PlanInput input,String fingerprint){this.area=area;this.input=input;this.fingerprint=fingerprint;}
    }
    public static final class Snapshot{
        public final UUID id,jobId;public final String dimension,area,pass,planFingerprint;public final Map<String,SnapshotCell> cells=new LinkedHashMap<>();
        public Snapshot(UUID id,UUID jobId,String dimension,String area,String pass,String planFingerprint){this.id=id;this.jobId=jobId;this.dimension=dimension;this.area=area;this.pass=pass;this.planFingerprint=planFingerprint;}
    }

    private final Map<UUID,Selection> selections=new LinkedHashMap<>();
    private final Map<String,Area> areas=new LinkedHashMap<>();
    private final Map<String,ProtectedZone> protections=new LinkedHashMap<>();
    private final Map<Long,OverrideCell> overrides=new LinkedHashMap<>();
    private final Map<String,Plan> plans=new LinkedHashMap<>();
    private final Map<UUID,Job> jobs=new LinkedHashMap<>();
    private final Map<UUID,Snapshot> snapshots=new LinkedHashMap<>();
    public Map<UUID,Selection> selections(){return selections;}public Map<String,Area> areas(){return areas;}public Map<String,ProtectedZone> protections(){return protections;}public Map<Long,OverrideCell> overrides(){return overrides;}public Map<String,Plan> plans(){return plans;}public Map<UUID,Job> jobs(){return jobs;}public Map<UUID,Snapshot> snapshots(){return snapshots;}public void changed(){setDirty();}
    public boolean protectedBlock(String dimension,int x,int z){return protections.values().stream().anyMatch(p->p.dimension().equals(dimension)&&x>=p.minX()&&x<=p.maxX()&&z>=p.minZ()&&z<=p.maxZ());}

    public boolean protectedAt(String dimension,int qx,int qz){return protections.values().stream().anyMatch(p->p.dimension().equals(dimension)&&p.intersectsQuart(qx,qz));}

    public static WorldPrepSavedData load(CompoundTag root,HolderLookup.Provider registries){int schema=root.getInt("schema");if(schema>SCHEMA)throw new IllegalStateException("WorldPrep data schema "+schema+" is newer than supported "+SCHEMA);var d=new WorldPrepSavedData();
        for(Tag v:root.getList("areas",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;var a=new Area(t.getString("id"),t.getString("dimension"),t.getInt("minX"),t.getInt("minZ"),t.getInt("maxX"),t.getInt("maxZ"));d.areas.put(a.id(),a);}
        for(Tag v:root.getList("selections",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;d.selections.put(t.getUUID("player"),new Selection(nullableInt(t,"x1"),nullableInt(t,"z1"),nullableInt(t,"x2"),nullableInt(t,"z2")));}
        for(Tag v:root.getList("protections",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;var p=new ProtectedZone(t.getString("id"),t.getString("dimension"),t.getInt("minX"),t.getInt("minZ"),t.getInt("maxX"),t.getInt("maxZ"));d.protections.put(p.id(),p);}
        for(Tag v:root.getList("overrides",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;var o=new OverrideCell(t.getInt("x"),t.getInt("z"),blankNull(t.getString("exact")),blankNull(t.getString("palette")));d.overrides.put(cellKey(o.quartX(),o.quartZ()),o);}
        for(Tag v:root.getList("plans",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;var i=readInput(t.getCompound("input"));var p=new Plan(t.getString("area"),i,t.getString("fingerprint"));for(Tag cv:t.getList("cells",Tag.TAG_COMPOUND)){var c=(CompoundTag)cv;var cell=new BiomeCell(c.getInt("x"),c.getInt("z"),c.getString("biome"),c.getLong("terrain"));p.cells.put(cellKey(cell.quartX(),cell.quartZ()),cell);}d.plans.put(p.area,p);}
        for(Tag v:root.getList("jobs",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;var j=new Job(t.getUUID("id"),Operation.valueOf(t.getString("operation")),t.getString("area"),JobState.valueOf(t.getString("state")),t.getLong("cursor"),t.getString("error"),t.hasUUID("snapshot")?t.getUUID("snapshot"):null);d.jobs.put(j.id(),j);}
        for(Tag v:root.getList("snapshots",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;var s=new Snapshot(t.getUUID("id"),t.getUUID("job"),t.getString("dimension"),t.getString("area"),t.getString("pass"),t.getString("fingerprint"));for(Tag cv:t.getList("cells",Tag.TAG_COMPOUND)){var c=(CompoundTag)cv;var cell=new SnapshotCell(c.getInt("x"),c.getInt("y"),c.getInt("z"),c.getString("before"),c.getString("applied"));s.cells.put(snapshotKey(cell.quartX(),cell.quartY(),cell.quartZ()),cell);}d.snapshots.put(s.id,s);}return d;}
    @Override public CompoundTag save(CompoundTag root,HolderLookup.Provider registries){root.putInt("schema",SCHEMA);root.put("areas",areasTag());root.put("selections",selectionsTag());root.put("protections",protectionsTag());root.put("overrides",overridesTag());root.put("plans",plansTag());root.put("jobs",jobsTag());root.put("snapshots",snapshotsTag());return root;}
    private ListTag areasTag(){var l=new ListTag();for(var a:areas.values()){var t=new CompoundTag();t.putString("id",a.id());t.putString("dimension",a.dimension());t.putInt("minX",a.minX());t.putInt("minZ",a.minZ());t.putInt("maxX",a.maxX());t.putInt("maxZ",a.maxZ());l.add(t);}return l;}
    private ListTag selectionsTag(){var l=new ListTag();for(var e:selections.entrySet()){var t=new CompoundTag();t.putUUID("player",e.getKey());putNullable(t,"x1",e.getValue().x1());putNullable(t,"z1",e.getValue().z1());putNullable(t,"x2",e.getValue().x2());putNullable(t,"z2",e.getValue().z2());l.add(t);}return l;}
    private ListTag protectionsTag(){var l=new ListTag();for(var p:protections.values()){var t=new CompoundTag();t.putString("id",p.id());t.putString("dimension",p.dimension());t.putInt("minX",p.minX());t.putInt("minZ",p.minZ());t.putInt("maxX",p.maxX());t.putInt("maxZ",p.maxZ());l.add(t);}return l;}
    private ListTag overridesTag(){var l=new ListTag();for(var o:overrides.values()){var t=new CompoundTag();t.putInt("x",o.quartX());t.putInt("z",o.quartZ());t.putString("exact",nullBlank(o.exactBiome()));t.putString("palette",nullBlank(o.palette()));l.add(t);}return l;}
    private ListTag plansTag(){var l=new ListTag();for(var p:plans.values()){var t=new CompoundTag();t.putString("area",p.area);t.put("input",writeInput(p.input));t.putString("fingerprint",p.fingerprint);var cs=new ListTag();for(var c:p.cells.values()){var x=new CompoundTag();x.putInt("x",c.quartX());x.putInt("z",c.quartZ());x.putString("biome",c.biome());x.putLong("terrain",c.terrainSignature());cs.add(x);}t.put("cells",cs);l.add(t);}return l;}
    private ListTag jobsTag(){var l=new ListTag();for(var j:jobs.values()){var t=new CompoundTag();t.putUUID("id",j.id());t.putString("operation",j.operation().name());t.putString("area",j.area());t.putString("state",j.state().name());t.putLong("cursor",j.cursor());t.putString("error",nullBlank(j.error()));if(j.snapshotId()!=null)t.putUUID("snapshot",j.snapshotId());l.add(t);}return l;}
    private ListTag snapshotsTag(){var l=new ListTag();for(var s:snapshots.values()){var t=new CompoundTag();t.putUUID("id",s.id);t.putUUID("job",s.jobId);t.putString("dimension",s.dimension);t.putString("area",s.area);t.putString("pass",s.pass);t.putString("fingerprint",s.planFingerprint);var cs=new ListTag();for(var c:s.cells.values()){var x=new CompoundTag();x.putInt("x",c.quartX());x.putInt("y",c.quartY());x.putInt("z",c.quartZ());x.putString("before",c.beforeBiome());x.putString("applied",c.appliedBiome());cs.add(x);}t.put("cells",cs);l.add(t);}return l;}
    private static CompoundTag writeInput(PlanInput i){var t=new CompoundTag();t.putString("dimension",i.dimension());t.putInt("minX",i.minX());t.putInt("minZ",i.minZ());t.putInt("maxX",i.maxX());t.putInt("maxZ",i.maxZ());t.putLong("seed",i.seed());t.putString("profile",i.profile());t.putString("profileDigest",i.profileDigest());t.putString("configDigest",i.configDigest());t.putString("registryDigest",i.registryDigest());t.putString("overrideDigest",i.overrideDigest());t.putString("protectionDigest",i.protectionDigest());return t;}
    private static PlanInput readInput(CompoundTag t){return new PlanInput(t.getString("dimension"),t.getInt("minX"),t.getInt("minZ"),t.getInt("maxX"),t.getInt("maxZ"),t.getLong("seed"),t.getString("profile"),t.getString("profileDigest"),t.getString("configDigest"),t.getString("registryDigest"),t.getString("overrideDigest"),t.getString("protectionDigest"));}
    public static long cellKey(int x,int z){return((long)x<<32)^(z&0xffffffffL);}public static String snapshotKey(int x,int y,int z){return x+":"+y+":"+z;}private static void putNullable(CompoundTag t,String k,Integer v){if(v!=null)t.putInt(k,v);}private static Integer nullableInt(CompoundTag t,String k){return t.contains(k)?t.getInt(k):null;}private static String nullBlank(String s){return s==null?"":s;}private static String blankNull(String s){return s.isBlank()?null:s;}
}
