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
    public static final int SCHEMA = 1;
    public static final String FILE_ID = "proutlost_worldprep";
    public static final Factory<WorldPrepSavedData> FACTORY = new Factory<>(WorldPrepSavedData::new, WorldPrepSavedData::load);

    public record Selection(Integer x1, Integer z1, Integer x2, Integer z2) {}
    public record Area(String id, String dimension, int minX, int minZ, int maxX, int maxZ) {
        public boolean overlaps(Area b) { return dimension.equals(b.dimension) && minX <= b.maxX && maxX >= b.minX && minZ <= b.maxZ && maxZ >= b.minZ; }
    }
    public enum Operation { ANALYZE, PREVIEW_BIOMES, APPLY_BIOMES, ROLLBACK_BIOMES }
    public enum JobState { QUEUED, RUNNING, PAUSED, COMPLETED, CANCELLED, FAILED }
    public record Job(UUID id, Operation operation, String area, JobState state, long cursor, String error) {
        public Job withState(JobState state) { return new Job(id, operation, area, state, cursor, error); }
        public Job withCursor(long cursor) { return new Job(id, operation, area, state, cursor, error); }
        public Job failed(String error) { return new Job(id, operation, area, JobState.FAILED, cursor, error); }
    }
    public record BiomeCell(int quartX, int quartZ, String biome, long terrainSignature) {}
    public record SnapshotCell(int quartX, int quartY, int quartZ, String biome) {}
    public static final class Plan {
        public final String area, profile; public String fingerprint; public final long seed; public final Map<Long, BiomeCell> cells = new LinkedHashMap<>();
        public Plan(String area, String profile, long seed, String fingerprint) { this.area=area;this.profile=profile;this.seed=seed;this.fingerprint=fingerprint; }
    }
    public static final class Snapshot {
        public final String area, planFingerprint; public final Map<String, SnapshotCell> cells = new LinkedHashMap<>();
        public Snapshot(String area,String planFingerprint){this.area=area;this.planFingerprint=planFingerprint;}
    }

    private final Map<UUID, Selection> selections = new LinkedHashMap<>();
    private final Map<String, Area> areas = new LinkedHashMap<>();
    private final Map<String, Plan> plans = new LinkedHashMap<>();
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    public Map<UUID, Selection> selections(){return selections;} public Map<String,Area> areas(){return areas;} public Map<String,Plan> plans(){return plans;} public Map<UUID,Job> jobs(){return jobs;} public Map<String,Snapshot> snapshots(){return snapshots;}
    public void changed(){setDirty();}

    public static WorldPrepSavedData load(CompoundTag root, HolderLookup.Provider registries) {
        int schema=root.getInt("schema"); if(schema>SCHEMA) throw new IllegalStateException("WorldPrep data schema "+schema+" is newer than supported "+SCHEMA);
        var d=new WorldPrepSavedData();
        for(Tag value:root.getList("areas",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;var a=new Area(t.getString("id"),t.getString("dimension"),t.getInt("minX"),t.getInt("minZ"),t.getInt("maxX"),t.getInt("maxZ"));d.areas.put(a.id(),a);}
        for(Tag value:root.getList("selections",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;d.selections.put(t.getUUID("player"),new Selection(getNullableInt(t,"x1"),getNullableInt(t,"z1"),getNullableInt(t,"x2"),getNullableInt(t,"z2")));}
        for(Tag value:root.getList("plans",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;var p=new Plan(t.getString("area"),t.getString("profile"),t.getLong("seed"),t.getString("fingerprint"));for(Tag cv:t.getList("cells",Tag.TAG_COMPOUND)){var c=(CompoundTag)cv;var cell=new BiomeCell(c.getInt("x"),c.getInt("z"),c.getString("biome"),c.getLong("terrain"));p.cells.put(cellKey(cell.quartX(),cell.quartZ()),cell);}d.plans.put(p.area,p);}
        for(Tag value:root.getList("jobs",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;var j=new Job(t.getUUID("id"),Operation.valueOf(t.getString("operation")),t.getString("area"),JobState.valueOf(t.getString("state")),t.getLong("cursor"),t.getString("error"));d.jobs.put(j.id(),j);}
        for(Tag value:root.getList("snapshots",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;var s=new Snapshot(t.getString("area"),t.getString("fingerprint"));for(Tag cv:t.getList("cells",Tag.TAG_COMPOUND)){var c=(CompoundTag)cv;var cell=new SnapshotCell(c.getInt("x"),c.getInt("y"),c.getInt("z"),c.getString("biome"));s.cells.put(snapshotKey(cell.quartX(),cell.quartY(),cell.quartZ()),cell);}d.snapshots.put(s.area,s);}
        return d;
    }
    @Override public CompoundTag save(CompoundTag root, HolderLookup.Provider registries){root.putInt("schema",SCHEMA);var as=new ListTag();for(var a:areas.values()){var t=new CompoundTag();t.putString("id",a.id());t.putString("dimension",a.dimension());t.putInt("minX",a.minX());t.putInt("minZ",a.minZ());t.putInt("maxX",a.maxX());t.putInt("maxZ",a.maxZ());as.add(t);}root.put("areas",as);var ss=new ListTag();for(var e:selections.entrySet()){var t=new CompoundTag();t.putUUID("player",e.getKey());putNullableInt(t,"x1",e.getValue().x1());putNullableInt(t,"z1",e.getValue().z1());putNullableInt(t,"x2",e.getValue().x2());putNullableInt(t,"z2",e.getValue().z2());ss.add(t);}root.put("selections",ss);var ps=new ListTag();for(var p:plans.values()){var t=new CompoundTag();t.putString("area",p.area);t.putString("profile",p.profile);t.putLong("seed",p.seed);t.putString("fingerprint",p.fingerprint);var cs=new ListTag();for(var c:p.cells.values()){var x=new CompoundTag();x.putInt("x",c.quartX());x.putInt("z",c.quartZ());x.putString("biome",c.biome());x.putLong("terrain",c.terrainSignature());cs.add(x);}t.put("cells",cs);ps.add(t);}root.put("plans",ps);var js=new ListTag();for(var j:jobs.values()){var t=new CompoundTag();t.putUUID("id",j.id());t.putString("operation",j.operation().name());t.putString("area",j.area());t.putString("state",j.state().name());t.putLong("cursor",j.cursor());t.putString("error",j.error()==null?"":j.error());js.add(t);}root.put("jobs",js);var snaps=new ListTag();for(var s:snapshots.values()){var t=new CompoundTag();t.putString("area",s.area);t.putString("fingerprint",s.planFingerprint);var cs=new ListTag();for(var c:s.cells.values()){var x=new CompoundTag();x.putInt("x",c.quartX());x.putInt("y",c.quartY());x.putInt("z",c.quartZ());x.putString("biome",c.biome());cs.add(x);}t.put("cells",cs);snaps.add(t);}root.put("snapshots",snaps);return root;}
    public static long cellKey(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}public static String snapshotKey(int x,int y,int z){return x+":"+y+":"+z;}private static void putNullableInt(CompoundTag t,String k,Integer v){if(v!=null)t.putInt(k,v);}private static Integer getNullableInt(CompoundTag t,String k){return t.contains(k)?t.getInt(k):null;}
}
