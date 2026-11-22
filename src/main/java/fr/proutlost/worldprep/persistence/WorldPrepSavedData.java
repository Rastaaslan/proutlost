package fr.proutlost.worldprep.persistence;

import fr.proutlost.worldprep.ecology.VegetationPlanner;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Versioned, dimension-local authoritative state for the biome vertical slice. */
public final class WorldPrepSavedData extends AcknowledgedSavedData {
    public static final int SCHEMA = 7;
    public static final String FILE_ID = "proutlost_worldprep";
    public static final Factory<WorldPrepSavedData> FACTORY = new Factory<>(WorldPrepSavedData::new, WorldPrepSavedData::load);

    public record Selection(Integer x1,Integer z1,Integer x2,Integer z2) {}
    public enum AreaMask { FULL_AREA, LAND, COAST, MARINE, LAND_AND_COAST;
        public boolean includes(boolean water,boolean coast){return switch(this){case FULL_AREA->true;case LAND->!water;case COAST->coast;case MARINE->water;case LAND_AND_COAST->!water||coast;};}
    }
    public record Area(String id,String dimension,int minX,int minZ,int maxX,int maxZ,AreaMask mask,VegetationPlanner.TreeMode treeMode) {
        public Area {java.util.Objects.requireNonNull(mask);java.util.Objects.requireNonNull(treeMode);}
        public Area(String id,String dimension,int minX,int minZ,int maxX,int maxZ){this(id,dimension,minX,minZ,maxX,maxZ,AreaMask.FULL_AREA,VegetationPlanner.TreeMode.NORMAL);}
        public Area(String id,String dimension,int minX,int minZ,int maxX,int maxZ,AreaMask mask){this(id,dimension,minX,minZ,maxX,maxZ,mask,VegetationPlanner.TreeMode.NORMAL);}
        public Area withMask(AreaMask value){return new Area(id,dimension,minX,minZ,maxX,maxZ,value,treeMode);}
        public Area withTreeMode(VegetationPlanner.TreeMode value){return new Area(id,dimension,minX,minZ,maxX,maxZ,mask,value);}
        public boolean overlaps(Area b){return dimension.equals(b.dimension)&&minX<=b.maxX&&maxX>=b.minX&&minZ<=b.maxZ&&maxZ>=b.minZ;}
    }
    public record ProtectedZone(String id,String dimension,int minX,int minZ,int maxX,int maxZ){
        public boolean intersectsQuart(int qx,int qz){int x=qx<<2,z=qz<<2;return dimension.equals(this.dimension)&&x<=maxX&&x+3>=minX&&z<=maxZ&&z+3>=minZ;}
    }
    public record OverrideCell(int quartX,int quartZ,String exactBiome,String palette){}
    public enum Operation{ANALYZE,PREVIEW_BIOMES,APPLY_BIOMES,ROLLBACK_BIOMES,PREVIEW_GEOLOGY,APPLY_GEOLOGY,ROLLBACK_GEOLOGY,PREVIEW_ORES,APPLY_ORES,ROLLBACK_ORES,PREVIEW_SOILS,APPLY_SOILS,ROLLBACK_SOILS,PREVIEW_FLORA,APPLY_FLORA,ROLLBACK_FLORA,PREVIEW_TREES,APPLY_TREES,ROLLBACK_TREES,PREVIEW_AQUATIC,APPLY_AQUATIC,ROLLBACK_AQUATIC,PREVIEW_HABITATS,BATCH_ECOLOGY}
    public enum JobState{QUEUED,RUNNING,PAUSED,COMPLETED,CANCELLED,FAILED}
    public record Job(UUID id,Operation operation,String area,JobState state,long cursor,String error,UUID snapshotId){

        /** Functional phase derived solely from the persisted operation and execution state.
         * Legacy schema-7 state names and cold PAUSED revalidation remain authoritative. */
        public fr.proutlost.worldprep.job.WorldPrepJob.State functionalState() {
            return switch (state) {
                case QUEUED -> fr.proutlost.worldprep.job.WorldPrepJob.State.QUEUED;
                case PAUSED -> fr.proutlost.worldprep.job.WorldPrepJob.State.PAUSED;
                case CANCELLED -> fr.proutlost.worldprep.job.WorldPrepJob.State.CANCELLED;
                case FAILED -> fr.proutlost.worldprep.job.WorldPrepJob.State.FAILED;
                case RUNNING -> switch (operation) {
                    case ANALYZE -> fr.proutlost.worldprep.job.WorldPrepJob.State.ANALYZING;
                    case PREVIEW_BIOMES, PREVIEW_GEOLOGY, PREVIEW_ORES, PREVIEW_SOILS,
                            PREVIEW_FLORA, PREVIEW_TREES, PREVIEW_AQUATIC, PREVIEW_HABITATS ->
                            fr.proutlost.worldprep.job.WorldPrepJob.State.PREVIEWING;
                    case APPLY_BIOMES, APPLY_GEOLOGY, APPLY_ORES, APPLY_SOILS,
                            APPLY_FLORA, APPLY_TREES, APPLY_AQUATIC, BATCH_ECOLOGY ->
                            fr.proutlost.worldprep.job.WorldPrepJob.State.APPLYING;
                    case ROLLBACK_BIOMES, ROLLBACK_GEOLOGY, ROLLBACK_ORES, ROLLBACK_SOILS,
                            ROLLBACK_FLORA, ROLLBACK_TREES, ROLLBACK_AQUATIC ->
                            fr.proutlost.worldprep.job.WorldPrepJob.State.ROLLING_BACK;
                };
                case COMPLETED -> switch (operation) {
                    case PREVIEW_BIOMES, PREVIEW_GEOLOGY, PREVIEW_ORES, PREVIEW_SOILS,
                            PREVIEW_FLORA, PREVIEW_TREES, PREVIEW_AQUATIC, PREVIEW_HABITATS ->
                            fr.proutlost.worldprep.job.WorldPrepJob.State.PREVIEW_READY;
                    case ROLLBACK_BIOMES, ROLLBACK_GEOLOGY, ROLLBACK_ORES, ROLLBACK_SOILS,
                            ROLLBACK_FLORA, ROLLBACK_TREES, ROLLBACK_AQUATIC ->
                            fr.proutlost.worldprep.job.WorldPrepJob.State.ROLLED_BACK;
                    case ANALYZE, APPLY_BIOMES, APPLY_GEOLOGY, APPLY_ORES, APPLY_SOILS,
                            APPLY_FLORA, APPLY_TREES, APPLY_AQUATIC, BATCH_ECOLOGY ->
                            fr.proutlost.worldprep.job.WorldPrepJob.State.COMPLETED;
                };
            };
        }
        public Job withState(JobState value){return new Job(id,operation,area,value,cursor,error,snapshotId);}
        public Job withCursor(long value){return new Job(id,operation,area,state,value,error,snapshotId);}
        public Job failed(String value){return new Job(id,operation,area,JobState.FAILED,cursor,value,snapshotId);}
        public Job withSnapshot(UUID value){return new Job(id,operation,area,state,cursor,error,value);}
    }
    public record PlanInput(String dimension,int minX,int minZ,int maxX,int maxZ,long seed,String profile,String profileDigest,String configDigest,String registryDigest,String overrideDigest,String protectionDigest){}
    public record BiomeCell(int quartX,int quartZ,String biome,long terrainSignature){}
    public record SnapshotCell(int quartX,int quartY,int quartZ,String beforeBiome,String appliedBiome){}
    /** Compact, read-only terrain observations. The cursor and aggregates are saved in the same SavedData root. */
    public static final class Analysis {
        public final PlanInput input;public final long totalCells;public final int minBuildHeight,maxBuildHeight,registryEntries,protectedZones;
        public long terrainSamples,inlandDistanceSamples,inlandUnresolvedSamples,inlandNoWaterSamples;public double inlandDistanceSum,noiseSum,noiseMin,noiseMax;
        public long samples,waterSamples,protectedSamples;public double surfaceSum,slopeSum,roughnessSum,waterDepthSum,coastSum;
        public int minSurface=Integer.MAX_VALUE,maxSurface=Integer.MIN_VALUE,maxWaterDepth;public boolean complete;
        public String terrainDigest;
        /** Null only for retained legacy analyses; never report missing measurements as computed zero. */
        public fr.proutlost.worldprep.analysis.TerrainAnalysisExtras extended;
        public final Map<String,Long> altitudeBands=new java.util.TreeMap<>(),slopeBands=new java.util.TreeMap<>(),biomes=new java.util.TreeMap<>(),missingBiomes=new java.util.TreeMap<>();
        public Analysis(PlanInput input,long totalCells,int minBuildHeight,int maxBuildHeight,int registryEntries,int protectedZones,String terrainDigest){
            this.input=java.util.Objects.requireNonNull(input);if(totalCells<1||maxBuildHeight<=minBuildHeight||registryEntries<0||protectedZones<0)throw new IllegalArgumentException("Invalid analysis geometry");
            this.totalCells=totalCells;this.minBuildHeight=minBuildHeight;this.maxBuildHeight=maxBuildHeight;this.registryEntries=registryEntries;this.protectedZones=protectedZones;this.terrainDigest=terrainDigest;
        }
    }
    public static final class Plan{
        public final String area;public final PlanInput input;public String fingerprint;public UUID planId;public long entryCount;
        /** Test/authoring staging only; production preview clears it after page publication and it is never persisted. */
        public final transient Map<Long,BiomeCell> cells=new LinkedHashMap<>();
        public Plan(String area,PlanInput input,String fingerprint){this(area,input,fingerprint,null,0);}
        public Plan(String area,PlanInput input,String fingerprint,UUID planId,long entryCount){this.area=area;this.input=input;this.fingerprint=fingerprint;this.planId=planId;this.entryCount=entryCount;}
    }
    public static final class Snapshot{
        public final UUID id,jobId,planId;public final String dimension,area,pass,planFingerprint;
        public long pageCount,entryCount;public String journalRoot="";
        public Snapshot(UUID id,UUID jobId,UUID planId,String dimension,String area,String pass,String planFingerprint){this.id=id;this.jobId=jobId;this.planId=planId;this.dimension=dimension;this.area=area;this.pass=pass;this.planFingerprint=planFingerprint;}
    }

    private fr.proutlost.worldprep.location.LocationRegistry locations=new fr.proutlost.worldprep.location.LocationRegistry();
    public fr.proutlost.worldprep.location.LocationRegistry locations(){return locations;}
    public String protectionFingerprint(){var parts=new java.util.ArrayList<String>();protections.values().stream().sorted(java.util.Comparator.comparing(ProtectedZone::id)).forEach(p->parts.add(p.toString()));if(!locations.isEmpty())parts.add(locations.protectionFingerprint());return fr.proutlost.worldprep.plan.PlanFingerprint.of(parts.toArray(String[]::new)).value();}
    private final Map<UUID,Analysis> analyses=new LinkedHashMap<>();
    public Map<UUID,Analysis> analyses(){return analyses;}

    // RUN90 isolated DEV: a cold-loaded reference snapshot of immutable Area/Job records.
    // Comparing immutable record VALUES detects all mutations, including entry.setValue,
    // map.replaceAll and map.put, without relying on fragile map hooks.
    // This is NOT a persisted cache: the old atomic NBT root remains authoritative.
    private final Map<String,Area> lastCapturedAreas=new LinkedHashMap<>();
    private final Map<UUID,Job> lastCapturedJobs=new LinkedHashMap<>();
    private boolean loadedFromPagedColdRoot;
    private final Map<UUID,Selection> selections=new LinkedHashMap<>();
    private final TrackedRecordMap<String,Area> areas=new TrackedRecordMap<>();
    private final Map<String,ProtectedZone> protections=new LinkedHashMap<>();
    private final Map<Long,OverrideCell> overrides=new LinkedHashMap<>();
    private final java.util.NavigableMap<String,Plan> plans=new java.util.TreeMap<>();
    private final TrackedRecordMap<UUID,Job> jobs=new TrackedRecordMap<>();
    private final java.util.NavigableMap<UUID,Snapshot> snapshots=new java.util.TreeMap<>();
    public Map<UUID,Selection> selections(){return selections;}public Map<String,Area> areas(){return areas;}public Map<String,ProtectedZone> protections(){return protections;}public Map<Long,OverrideCell> overrides(){return overrides;}public java.util.NavigableMap<String,Plan> plans(){return plans;}public Map<UUID,Job> jobs(){return jobs;}public java.util.NavigableMap<UUID,Snapshot> snapshots(){return snapshots;}public void changed(){setDirty();}
    public boolean protectedBlock(String dimension,int x,int z){return locations.protectedBlock(dimension,x,z)||protections.values().stream().anyMatch(p->p.dimension().equals(dimension)&&x>=p.minX()&&x<=p.maxX()&&z>=p.minZ()&&z<=p.maxZ());}

    public boolean protectedAt(String dimension,int qx,int qz){return locations.protectedQuart(dimension,qx,qz)||protections.values().stream().anyMatch(p->p.dimension().equals(dimension)&&p.intersectsQuart(qx,qz));}

    // SavedData completion can reach disk before dirty chunk contents. Never resume
    // automatically: an operator must explicitly reconcile the exact saved journal.
    private static boolean requiresTerrainRecheck(Operation operation) {
        return switch (operation) {
            case BATCH_ECOLOGY, ROLLBACK_SOILS, ROLLBACK_TREES,
                    ROLLBACK_FLORA, ROLLBACK_AQUATIC -> true;
            default -> false;
        };
    }

    public static WorldPrepSavedData load(CompoundTag root,HolderLookup.Provider registries){
        boolean pagedColdRoot=root.contains("__worldprep_bucket_index_v1",Tag.TAG_COMPOUND);
        root=PagedSavedDataRuntime.expandLoad(root,FILE_ID);int schema=root.getInt("schema");if(schema!=0&&schema!=4&&schema!=5&&schema!=6&&schema!=SCHEMA)throw new IllegalStateException("WorldPrep data schema "+schema+" is incompatible with paged production schema "+SCHEMA);var d=new WorldPrepSavedData();
        d.loadedFromPagedColdRoot=pagedColdRoot;
        // Earlier schema6 mask-only data has no authored registry. Once the marker is saved, its nested data is mandatory.
        if(root.contains("locationRegistrySchema")&&(!root.contains("locationRegistrySchema",Tag.TAG_INT)||root.getInt("locationRegistrySchema")!=1||!root.contains("locations",Tag.TAG_COMPOUND)))throw new IllegalStateException("Missing/invalid marked location registry");
        if(root.contains("locations")){if(!root.contains("locations",Tag.TAG_COMPOUND))throw new IllegalStateException("Invalid location registry tag");d.locations=fr.proutlost.worldprep.location.LocationRegistryNbt.load(root.getCompound("locations"));}

        for(Tag v:root.getList("areas",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;var a=new Area(t.getString("id"),t.getString("dimension"),t.getInt("minX"),t.getInt("minZ"),t.getInt("maxX"),t.getInt("maxZ"),t.contains("mask",Tag.TAG_STRING)?AreaMask.valueOf(t.getString("mask")):AreaMask.FULL_AREA,t.contains("treeMode",Tag.TAG_STRING)?VegetationPlanner.TreeMode.valueOf(t.getString("treeMode")):VegetationPlanner.TreeMode.NORMAL);d.areas.put(a.id(),a);d.lastCapturedAreas.put(a.id(),a);}
        for(Tag v:root.getList("selections",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;d.selections.put(t.getUUID("player"),new Selection(nullableInt(t,"x1"),nullableInt(t,"z1"),nullableInt(t,"x2"),nullableInt(t,"z2")));}
        for(Tag v:root.getList("protections",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;var p=new ProtectedZone(t.getString("id"),t.getString("dimension"),t.getInt("minX"),t.getInt("minZ"),t.getInt("maxX"),t.getInt("maxZ"));d.protections.put(p.id(),p);}
        for(Tag v:root.getList("overrides",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;var o=new OverrideCell(t.getInt("x"),t.getInt("z"),blankNull(t.getString("exact")),blankNull(t.getString("palette")));d.overrides.put(cellKey(o.quartX(),o.quartZ()),o);}
        for(Tag v:root.getList("plans",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;var i=readInput(t.getCompound("input"));if(!t.hasUUID("planId"))throw new IllegalStateException("Legacy inline biome plan is incompatible; preview again");var p=new Plan(t.getString("area"),i,t.getString("fingerprint"),t.getUUID("planId"),t.getLong("entryCount"));d.plans.put(p.area,p);}
        for(Tag v:root.getList("jobs",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;var j=new Job(t.getUUID("id"),Operation.valueOf(t.getString("operation")),t.getString("area"),JobState.valueOf(t.getString("state")),t.getLong("cursor"),t.getString("error"),t.hasUUID("snapshot")?t.getUUID("snapshot"):null);d.lastCapturedJobs.put(j.id(),j);
            d.jobs.put(j.id(),j.state()==JobState.RUNNING||j.state()==JobState.QUEUED||(requiresTerrainRecheck(j.operation())&&j.state()==JobState.COMPLETED)?j.withState(JobState.PAUSED):j);}
        for(Tag value:root.getList("analyses",Tag.TAG_COMPOUND)){var tag=(CompoundTag)value;d.analyses.put(tag.getUUID("job"),readAnalysis(tag));}
        for(Tag v:root.getList("snapshots",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;if(!t.hasUUID("planId"))throw new IllegalStateException("Legacy mirrored biome journal is incompatible; recovery requires the original runtime");var s=new Snapshot(t.getUUID("id"),t.getUUID("job"),t.getUUID("planId"),t.getString("dimension"),t.getString("area"),t.getString("pass"),t.getString("fingerprint"));s.pageCount=t.getLong("pageCount");s.entryCount=t.getLong("entryCount");s.journalRoot=t.getString("journalRoot");d.snapshots.put(s.id,s);}
        // The baseline represents the EXACT cold persisted values. Runtime load
        // can normalize RUNNING jobs to PAUSED. Preserve those differences.
        d.areas.resetJournal();
        d.jobs.resetJournal();
        for(var previous:d.lastCapturedJobs.entrySet())
            if(!java.util.Objects.equals(previous.getValue(),d.jobs.get(previous.getKey())))
                d.jobs.markChanged(previous.getKey());
        return d;}
    /** RUN90 only. The delta contains all and ONLY changed immutable records.
     * Page refs and atomically published root are reconstructed by the separate
     * SHA-verifying paged writer; a missing/changed parent root must refuse.
     * Other sections are emitted in full for exact parity, never silently dropped.
     */
    private CompoundTag saveRun90Delta(CompoundTag root) {
        if(jobs.size()<lastCapturedJobs.size()||areas.size()<lastCapturedAreas.size())
            throw new IllegalStateException("RUN90 deletion requires full cold migration");
        if(!plans.isEmpty()||!snapshots.isEmpty())
            throw new IllegalStateException("RUN90 no sparse writer admitted for biome plans/snapshots");
        var changedAreas=new ListTag();
        var changedAreaValues=new java.util.LinkedHashMap<String,Area>();
        int newAreas=0;
        for(var e:areas.entrySet()) {
            Area previous=lastCapturedAreas.get(e.getKey());
            if(previous==null)newAreas++;
            if(!java.util.Objects.equals(previous,e.getValue())){
                var a=e.getValue();
                var t=new CompoundTag();t.putString("id",a.id());t.putString("dimension",a.dimension());
                t.putString("mask",a.mask().name());t.putString("treeMode",a.treeMode().name());
                t.putInt("minX",a.minX());t.putInt("minZ",a.minZ());t.putInt("maxX",a.maxX());t.putInt("maxZ",a.maxZ());
                changedAreas.add(t);
                changedAreaValues.put(e.getKey(),a);
            }
        }
        var changedJobs=new ListTag();
        var changedJobValues=new java.util.LinkedHashMap<UUID,Job>();
        int newJobs=0;
        for(var e:jobs.entrySet()) {
            Job previous=lastCapturedJobs.get(e.getKey());
            if(previous==null)newJobs++;
            if(!java.util.Objects.equals(previous,e.getValue())){
                var j=e.getValue();var t=new CompoundTag();
                t.putUUID("id",j.id());t.putString("operation",j.operation().name());
                t.putString("area",j.area());t.putString("state",j.state().name());
                t.putLong("cursor",j.cursor());t.putString("error",nullBlank(j.error()));
                if(j.snapshotId()!=null)t.putUUID("snapshot",j.snapshotId());
                changedJobs.add(t);
                changedJobValues.put(e.getKey(),j);
            }
        }
        if(lastCapturedJobs.size()+newJobs!=jobs.size()||
           lastCapturedAreas.size()+newAreas!=areas.size())
            throw new IllegalStateException("RUN90 untracked removal/reordering; refuse");
        var marker=new CompoundTag();
        marker.putInt("version",1);
        marker.putString("store",FILE_ID);
        marker.put("areas",changedAreas);marker.putInt("areasCount",areas.size());
        marker.put("jobs",changedJobs);marker.putInt("jobsCount",jobs.size());
        root.putInt("schema",SCHEMA);
        root.putInt("locationRegistrySchema",1);
        root.put("locations",fr.proutlost.worldprep.location.LocationRegistryNbt.save(locations));
        root.put("analyses",analysesTag());
        root.put("selections",selectionsTag());root.put("protections",protectionsTag());
        root.put("overrides",overridesTag());root.put("__run90_incremental_v1",marker);
        // Captured records are immutable Java records; later mutations replace objects.
        // The one-inflight checkpoint/ACK gate will refuse later snapshots before completion.
        lastCapturedAreas.putAll(changedAreaValues);
        lastCapturedJobs.putAll(changedJobValues);
        areas.resetJournal();
        jobs.resetJournal();
        return root;
    }
    /**
     * RUN111 isolated opt-in ONLY: exact changed immutable record keys.
     * The official RUN90 path above is unchanged and selected by default.
     * The cold schema, SHA page identity, WAL, ACK and fsync guards are intact.
     */
    CompoundTag saveRun111TrackedDelta(CompoundTag root) {
        if(areas.wasRemoved() || jobs.wasRemoved()
                || jobs.size()<lastCapturedJobs.size() || areas.size()<lastCapturedAreas.size())
            throw new IllegalStateException("RUN111 removal requires cold migration; refusing sparse capture");
        if(!plans.isEmpty() || !snapshots.isEmpty())
            throw new IllegalStateException("RUN111 no sparse writer for plans/snapshots");

        var changedAreas=new ListTag();
        var changedAreaValues=new java.util.LinkedHashMap<String,Area>();
        int newAreas=0;
        for(var key:areas.changedKeysSnapshot()){
            var a=areas.get(key);
            if(a==null)throw new IllegalStateException("RUN111 dirty area missing");
            var previous=lastCapturedAreas.get(key);
            if(previous==null&&!lastCapturedAreas.containsKey(key))newAreas++;
            if(!java.util.Objects.equals(previous,a)){
                var t=new CompoundTag();
                t.putString("id",a.id());t.putString("dimension",a.dimension());
                t.putString("mask",a.mask().name());t.putString("treeMode",a.treeMode().name());
                t.putInt("minX",a.minX());t.putInt("minZ",a.minZ());
                t.putInt("maxX",a.maxX());t.putInt("maxZ",a.maxZ());
                changedAreas.add(t);
                changedAreaValues.put(key,a);
            }
        }
        var changedJobs=new ListTag();
        var changedJobValues=new java.util.LinkedHashMap<UUID,Job>();
        int newJobs=0;
        for(var key:jobs.changedKeysSnapshot()){
            var j=jobs.get(key);
            if(j==null)throw new IllegalStateException("RUN111 dirty job missing");
            var previous=lastCapturedJobs.get(key);
            if(previous==null&&!lastCapturedJobs.containsKey(key))newJobs++;
            if(!java.util.Objects.equals(previous,j)){
                var t=new CompoundTag();
                t.putUUID("id",j.id());t.putString("operation",j.operation().name());
                t.putString("area",j.area());t.putString("state",j.state().name());
                t.putLong("cursor",j.cursor());t.putString("error",nullBlank(j.error()));
                if(j.snapshotId()!=null)t.putUUID("snapshot",j.snapshotId());
                changedJobs.add(t);
                changedJobValues.put(key,j);
            }
        }
        // Exact count and immutable keys; no silent deletion or reorder.
        if(lastCapturedJobs.size()+newJobs!=jobs.size()
                ||lastCapturedAreas.size()+newAreas!=areas.size())
            throw new IllegalStateException("RUN111 untracked count/reorder; refuse");
        var marker=new CompoundTag();
        marker.putInt("version",1);marker.putString("store",FILE_ID);
        marker.put("areas",changedAreas);marker.putInt("areasCount",areas.size());
        marker.put("jobs",changedJobs);marker.putInt("jobsCount",jobs.size());
        root.putInt("schema",SCHEMA);
        root.putInt("locationRegistrySchema",1);
        root.put("locations",fr.proutlost.worldprep.location.LocationRegistryNbt.save(locations));
        root.put("analyses",analysesTag());
        root.put("selections",selectionsTag());
        root.put("protections",protectionsTag());root.put("overrides",overridesTag());
        root.put("__run90_incremental_v1",marker);
        // Exact same on-disk wire format, prior cold pages remain authoritative.
        lastCapturedAreas.putAll(changedAreaValues);
        lastCapturedJobs.putAll(changedJobValues);
        areas.resetJournal();jobs.resetJournal();
        return root;
    }
    @Override public CompoundTag save(CompoundTag root,HolderLookup.Provider registries){
        if(Boolean.getBoolean("proutlost.run90.strictIncrementalMetadata")
                && PagedSavedDataRuntime.configured() && loadedFromPagedColdRoot) {
            if(Boolean.getBoolean(TrackedRecordMap.FLAG)) {
                if(!areas.strictAtConstruction() || !jobs.strictAtConstruction())
                    throw new IllegalStateException(
                            "RUN111 strict change tracking must be enabled BEFORE SavedData construction");
                return saveRun111TrackedDelta(root);
            }
            return saveRun90Delta(root);
        }
        root.putInt("schema",SCHEMA);root.putInt("locationRegistrySchema",1);root.put("locations",fr.proutlost.worldprep.location.LocationRegistryNbt.save(locations));root.put("analyses",analysesTag());root.put("areas",areasTag());root.put("selections",selectionsTag());root.put("protections",protectionsTag());root.put("overrides",overridesTag());root.put("plans",plansTag());root.put("jobs",jobsTag());root.put("snapshots",snapshotsTag());return root;}
    private ListTag analysesTag(){var list=new ListTag();for(var entry:analyses.entrySet()){
        var a=entry.getValue();var t=new CompoundTag();t.putUUID("job",entry.getKey());t.put("input",writeInput(a.input));t.putLong("totalCells",a.totalCells);
        t.putInt("minBuildHeight",a.minBuildHeight);t.putInt("maxBuildHeight",a.maxBuildHeight);t.putInt("registryEntries",a.registryEntries);t.putInt("protectedZones",a.protectedZones);
        t.putLong("terrainSamples",a.terrainSamples);t.putLong("inlandDistanceSamples",a.inlandDistanceSamples);t.putLong("inlandUnresolvedSamples",a.inlandUnresolvedSamples);t.putLong("inlandNoWaterSamples",a.inlandNoWaterSamples);t.putDouble("inlandDistanceSum",a.inlandDistanceSum);t.putDouble("noiseSum",a.noiseSum);t.putDouble("noiseMin",a.noiseMin);t.putDouble("noiseMax",a.noiseMax);
        t.putLong("samples",a.samples);t.putLong("waterSamples",a.waterSamples);t.putLong("protectedSamples",a.protectedSamples);
        t.putDouble("surfaceSum",a.surfaceSum);t.putDouble("slopeSum",a.slopeSum);t.putDouble("roughnessSum",a.roughnessSum);t.putDouble("waterDepthSum",a.waterDepthSum);t.putDouble("coastSum",a.coastSum);
        t.putInt("minSurface",a.minSurface);t.putInt("maxSurface",a.maxSurface);t.putInt("maxWaterDepth",a.maxWaterDepth);t.putBoolean("complete",a.complete);t.putString("terrainDigest",a.terrainDigest);
        t.put("altitudeBands",countsTag(a.altitudeBands));t.put("slopeBands",countsTag(a.slopeBands));t.put("biomes",countsTag(a.biomes));t.put("missingBiomes",countsTag(a.missingBiomes));
        if(a.extended!=null){a.extended.requirePrefix(a.samples);t.putInt("extendedAnalysisSchema",1);t.put("extendedAnalysis",a.extended.save());}
        list.add(t);
    }return list;}
    private static CompoundTag countsTag(Map<String,Long> values){var tag=new CompoundTag();values.forEach(tag::putLong);return tag;}
    private static void readCounts(CompoundTag tag,Map<String,Long> values){if(tag.getAllKeys().size()>4096)throw new IllegalStateException("Analysis histogram bound");for(var key:tag.getAllKeys()){long count=tag.getLong(key);if(count<1)throw new IllegalStateException("Invalid analysis count");values.put(key,count);}}
    private static Analysis readAnalysis(CompoundTag t){var a=new Analysis(readInput(t.getCompound("input")),t.getLong("totalCells"),t.getInt("minBuildHeight"),t.getInt("maxBuildHeight"),t.getInt("registryEntries"),t.getInt("protectedZones"),t.getString("terrainDigest"));
        a.terrainSamples=t.getLong("terrainSamples");a.inlandDistanceSamples=t.getLong("inlandDistanceSamples");a.inlandUnresolvedSamples=t.getLong("inlandUnresolvedSamples");a.inlandNoWaterSamples=t.getLong("inlandNoWaterSamples");a.inlandDistanceSum=t.getDouble("inlandDistanceSum");a.noiseSum=t.getDouble("noiseSum");a.noiseMin=t.getDouble("noiseMin");a.noiseMax=t.getDouble("noiseMax");
        a.samples=t.getLong("samples");a.waterSamples=t.getLong("waterSamples");a.protectedSamples=t.getLong("protectedSamples");a.surfaceSum=t.getDouble("surfaceSum");a.slopeSum=t.getDouble("slopeSum");a.roughnessSum=t.getDouble("roughnessSum");a.waterDepthSum=t.getDouble("waterDepthSum");a.coastSum=t.getDouble("coastSum");
        a.minSurface=t.getInt("minSurface");a.maxSurface=t.getInt("maxSurface");a.maxWaterDepth=t.getInt("maxWaterDepth");a.complete=t.getBoolean("complete");
        if(a.samples<0||a.samples>a.totalCells||a.waterSamples<0||a.waterSamples>a.samples||a.protectedSamples<0||a.protectedSamples>a.samples||a.complete&&a.samples!=a.totalCells
            ||!Double.isFinite(a.surfaceSum)||!Double.isFinite(a.slopeSum)||!Double.isFinite(a.roughnessSum)||!Double.isFinite(a.waterDepthSum)||!Double.isFinite(a.coastSum))throw new IllegalStateException("Invalid persisted analysis prefix");
        if(a.terrainSamples<0||a.terrainSamples>a.samples||a.inlandDistanceSamples<0||a.inlandDistanceSamples>a.terrainSamples||a.inlandUnresolvedSamples<0||a.inlandUnresolvedSamples>a.terrainSamples||a.inlandNoWaterSamples<0||a.inlandNoWaterSamples>a.terrainSamples
            ||!Double.isFinite(a.inlandDistanceSum)||a.inlandDistanceSum<0||a.inlandDistanceSum>4.0*a.inlandDistanceSamples||!Double.isFinite(a.noiseSum)||!Double.isFinite(a.noiseMin)||!Double.isFinite(a.noiseMax)||a.terrainSamples>0&&a.noiseMin>a.noiseMax)throw new IllegalStateException("Invalid persisted local terrain measures");
        boolean marked=t.contains("extendedAnalysisSchema"),payload=t.contains("extendedAnalysis");
        if(marked!=payload)throw new IllegalStateException("Missing marked extended analysis");
        if(marked){
            if(!t.contains("extendedAnalysisSchema",Tag.TAG_INT)||t.getInt("extendedAnalysisSchema")!=1||!t.contains("extendedAnalysis",Tag.TAG_COMPOUND))
                throw new IllegalStateException("Unknown marked extended analysis");
            a.extended=fr.proutlost.worldprep.analysis.TerrainAnalysisExtras.load(t.getCompound("extendedAnalysis"),a.samples);
            var expected=new fr.proutlost.worldprep.analysis.TerrainAnalysisExtras.Geometry(a.input.minX(),a.minBuildHeight,a.input.minZ(),a.input.maxX(),a.maxBuildHeight,a.input.maxZ());
            if(!expected.equals(a.extended.geometry)||a.complete&&a.extended.volumeCells!=expected.cells())
                throw new IllegalStateException("Extended analysis geometry/completion mismatch");
        }
        new fr.proutlost.worldprep.plan.PlanFingerprint(a.terrainDigest);readCounts(t.getCompound("altitudeBands"),a.altitudeBands);readCounts(t.getCompound("slopeBands"),a.slopeBands);readCounts(t.getCompound("biomes"),a.biomes);readCounts(t.getCompound("missingBiomes"),a.missingBiomes);
        if(a.altitudeBands.values().stream().reduce(0L,Math::addExact)!=a.samples||a.slopeBands.values().stream().reduce(0L,Math::addExact)!=a.samples||a.biomes.values().stream().reduce(0L,Math::addExact)!=a.samples)throw new IllegalStateException("Analysis histogram/prefix mismatch");return a;
    }
    private ListTag areasTag(){var l=new ListTag();for(var a:areas.values()){var t=new CompoundTag();t.putString("id",a.id());t.putString("dimension",a.dimension());t.putString("mask",a.mask().name());t.putString("treeMode",a.treeMode().name());t.putInt("minX",a.minX());t.putInt("minZ",a.minZ());t.putInt("maxX",a.maxX());t.putInt("maxZ",a.maxZ());l.add(t);}return l;}
    private ListTag selectionsTag(){var l=new ListTag();for(var e:selections.entrySet()){var t=new CompoundTag();t.putUUID("player",e.getKey());putNullable(t,"x1",e.getValue().x1());putNullable(t,"z1",e.getValue().z1());putNullable(t,"x2",e.getValue().x2());putNullable(t,"z2",e.getValue().z2());l.add(t);}return l;}
    private ListTag protectionsTag(){var l=new ListTag();for(var p:protections.values()){var t=new CompoundTag();t.putString("id",p.id());t.putString("dimension",p.dimension());t.putInt("minX",p.minX());t.putInt("minZ",p.minZ());t.putInt("maxX",p.maxX());t.putInt("maxZ",p.maxZ());l.add(t);}return l;}
    private ListTag overridesTag(){var l=new ListTag();for(var o:overrides.values()){var t=new CompoundTag();t.putInt("x",o.quartX());t.putInt("z",o.quartZ());t.putString("exact",nullBlank(o.exactBiome()));t.putString("palette",nullBlank(o.palette()));l.add(t);}return l;}
    private ListTag plansTag(){var l=new ListTag();for(var p:plans.values()){if(p.planId==null)continue; // transient GameTest/authoring staging is deliberately non-authoritative
        var t=new CompoundTag();t.putString("area",p.area);t.put("input",writeInput(p.input));t.putString("fingerprint",p.fingerprint);t.putUUID("planId",p.planId);t.putLong("entryCount",p.entryCount);l.add(t);}return l;}
    private ListTag jobsTag(){var l=new ListTag();for(var j:jobs.values()){var t=new CompoundTag();t.putUUID("id",j.id());t.putString("operation",j.operation().name());t.putString("area",j.area());t.putString("state",j.state().name());t.putLong("cursor",j.cursor());t.putString("error",nullBlank(j.error()));if(j.snapshotId()!=null)t.putUUID("snapshot",j.snapshotId());l.add(t);}return l;}
    private ListTag snapshotsTag(){var l=new ListTag();for(var s:snapshots.values()){var t=new CompoundTag();t.putUUID("id",s.id);t.putUUID("job",s.jobId);t.putUUID("planId",s.planId);t.putString("dimension",s.dimension);t.putString("area",s.area);t.putString("pass",s.pass);t.putString("fingerprint",s.planFingerprint);t.putLong("pageCount",s.pageCount);t.putLong("entryCount",s.entryCount);t.putString("journalRoot",s.journalRoot);l.add(t);}return l;}
    private static CompoundTag writeInput(PlanInput i){var t=new CompoundTag();t.putString("dimension",i.dimension());t.putInt("minX",i.minX());t.putInt("minZ",i.minZ());t.putInt("maxX",i.maxX());t.putInt("maxZ",i.maxZ());t.putLong("seed",i.seed());t.putString("profile",i.profile());t.putString("profileDigest",i.profileDigest());t.putString("configDigest",i.configDigest());t.putString("registryDigest",i.registryDigest());t.putString("overrideDigest",i.overrideDigest());t.putString("protectionDigest",i.protectionDigest());return t;}
    private static PlanInput readInput(CompoundTag t){return new PlanInput(t.getString("dimension"),t.getInt("minX"),t.getInt("minZ"),t.getInt("maxX"),t.getInt("maxZ"),t.getLong("seed"),t.getString("profile"),t.getString("profileDigest"),t.getString("configDigest"),t.getString("registryDigest"),t.getString("overrideDigest"),t.getString("protectionDigest"));}
    public static long cellKey(int x,int z){return((long)x<<32)^(z&0xffffffffL);}public static String snapshotKey(int x,int y,int z){return x+":"+y+":"+z;}private static void putNullable(CompoundTag t,String k,Integer v){if(v!=null)t.putInt(k,v);}private static Integer nullableInt(CompoundTag t,String k){return t.contains(k)?t.getInt(k):null;}private static String nullBlank(String s){return s==null?"":s;}private static String blankNull(String s){return s.isBlank()?null:s;}
}
