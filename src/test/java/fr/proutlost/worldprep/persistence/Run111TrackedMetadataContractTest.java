package fr.proutlost.worldprep.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated semantic correctness checks. No benchmark and no world/server startup. */
class Run111TrackedMetadataContractTest {
    static final UUID FIRST=new UUID(12,34);
    private static final UUID SECOND=new UUID(13,35);

    static CompoundTag baseline(String state) {
        var root=new CompoundTag();
        root.putInt("schema",WorldPrepSavedData.SCHEMA);
        root.putInt("locationRegistrySchema",1);
        root.put("locations",fr.proutlost.worldprep.location.LocationRegistryNbt.save(
                new fr.proutlost.worldprep.location.LocationRegistry()));
        root.put("analyses",new ListTag());
        var areas=new ListTag();
        var a=new CompoundTag();a.putString("id","tile-a");
        a.putString("dimension","minecraft:overworld");
        a.putString("mask",WorldPrepSavedData.AreaMask.FULL_AREA.name());
        a.putString("treeMode",fr.proutlost.worldprep.ecology.VegetationPlanner.TreeMode.NORMAL.name());
        a.putInt("minX",0);a.putInt("minZ",0);
        a.putInt("maxX",15);a.putInt("maxZ",15);areas.add(a);
        root.put("areas",areas);
        var jobs=new ListTag();
        var j=new CompoundTag();j.putUUID("id",FIRST);
        j.putString("operation","BATCH_ECOLOGY");
        j.putString("area","tile-a");j.putString("state",state);
        j.putLong("cursor",7L);j.putString("error","");
        jobs.add(j);
        root.put("jobs",jobs);
        root.put("selections",new ListTag());
        root.put("protections",new ListTag());
        root.put("overrides",new ListTag());
        root.put("plans",new ListTag());
        root.put("snapshots",new ListTag());
        return root;
    }
    static ListTag patches(CompoundTag root,String key) {
        return root.getCompound("__run90_incremental_v1")
                .getList(key,Tag.TAG_COMPOUND);
    }
    @Test void coldLoadNormalizationIsExactlyIncludedInFirstTrackedCapture() {
        var data=WorldPrepSavedData.load(baseline("RUNNING"),null);
        assertEquals(WorldPrepSavedData.JobState.PAUSED,data.jobs().get(FIRST).state());
        var root=data.saveRun111TrackedDelta(new CompoundTag());
        assertEquals(1,patches(root,"jobs").size());
        assertEquals("PAUSED",patches(root,"jobs").getCompound(0).getString("state"));
        assertEquals(0,patches(root,"areas").size());
        assertEquals(1,root.getCompound("__run90_incremental_v1").getInt("jobsCount"));
        assertEquals(0,patches(data.saveRun111TrackedDelta(new CompoundTag()),"jobs").size());
    }
    @Test void trackedJobChangesAppendAndAreaChangesMatchLegacyFullScan() throws Exception {
        var tracked=WorldPrepSavedData.load(baseline("PAUSED"),null);
        var legacy=WorldPrepSavedData.load(baseline("PAUSED"),null);
        for(var data: new WorldPrepSavedData[]{tracked,legacy}) {
            data.jobs().put(FIRST,data.jobs().get(FIRST).withCursor(999));
            var a=new WorldPrepSavedData.Area("tile-b","minecraft:overworld",16,0,31,15);
            data.areas().put(a.id(),a);
            data.jobs().put(SECOND,new WorldPrepSavedData.Job(
                    SECOND,WorldPrepSavedData.Operation.PREVIEW_SOILS,"tile-b",
                    WorldPrepSavedData.JobState.PAUSED,1,"",null));
        }
        var fast=tracked.saveRun111TrackedDelta(new CompoundTag());
        Method old=WorldPrepSavedData.class.getDeclaredMethod("saveRun90Delta",CompoundTag.class);
        old.setAccessible(true);
        var canonical=(CompoundTag)old.invoke(legacy,new CompoundTag());
        assertEquals(canonical,fast,"Wire-format delta must match the existing exact full scan");
        assertEquals(2,patches(fast,"jobs").size());
        assertEquals(1,patches(fast,"areas").size());
        assertEquals(2,fast.getCompound("__run90_incremental_v1").getInt("jobsCount"));
        assertEquals(2,fast.getCompound("__run90_incremental_v1").getInt("areasCount"));
        assertEquals(0,patches(tracked.saveRun111TrackedDelta(new CompoundTag()),"jobs").size());
    }
    @Test void computedReplacedAndMergedRecordUpdatesCannotBeMissed() {
        var d=WorldPrepSavedData.load(baseline("PAUSED"),null);
        d.jobs().replaceAll((k,j)->j.withCursor(10));
        assertEquals(1,patches(d.saveRun111TrackedDelta(new CompoundTag()),"jobs").size());
        d.jobs().computeIfPresent(FIRST,(k,j)->j.withCursor(11));
        assertEquals(1,patches(d.saveRun111TrackedDelta(new CompoundTag()),"jobs").size());
        d.jobs().merge(FIRST,d.jobs().get(FIRST),(a,b)->a.withCursor(12));
        assertEquals(1,patches(d.saveRun111TrackedDelta(new CompoundTag()),"jobs").size());
        d.jobs().replace(FIRST,d.jobs().get(FIRST).withCursor(13));
        assertEquals(1,patches(d.saveRun111TrackedDelta(new CompoundTag()),"jobs").size());
        d.jobs().computeIfAbsent(SECOND,k->
                new WorldPrepSavedData.Job(SECOND,WorldPrepSavedData.Operation.PREVIEW_SOILS,
                    "tile-a",WorldPrepSavedData.JobState.PAUSED,0,"",null));
        assertEquals(1,patches(d.saveRun111TrackedDelta(new CompoundTag()),"jobs").size());
    }
    @Test void deletingOrReorderingProtectedMetadataFailsClosed() {
        var d=WorldPrepSavedData.load(baseline("PAUSED"),null);
        d.jobs().remove(FIRST);
        assertThrows(IllegalStateException.class,
                ()->d.saveRun111TrackedDelta(new CompoundTag()));
        var e=WorldPrepSavedData.load(baseline("PAUSED"),null);
        e.areas().clear();e.areas().put("tile-a",
            new WorldPrepSavedData.Area("tile-a","minecraft:overworld",0,0,15,15));
        assertThrows(IllegalStateException.class,
                ()->e.saveRun111TrackedDelta(new CompoundTag()));
    }
    @Test void mapViewMutationsAreForbiddenOnlyWhenStrictModeEnabled() {
        var old=System.getProperty(TrackedRecordMap.FLAG);
        try {
            System.setProperty(TrackedRecordMap.FLAG,"true");
            var d=WorldPrepSavedData.load(baseline("PAUSED"),null);
            assertThrows(UnsupportedOperationException.class,
                    ()->d.jobs().entrySet().iterator().next().setValue(d.jobs().get(FIRST)));
            assertThrows(UnsupportedOperationException.class,
                    ()->d.jobs().entrySet().iterator().remove());
            assertThrows(UnsupportedOperationException.class,()->d.jobs().keySet().remove(FIRST));
            assertThrows(UnsupportedOperationException.class,()->d.jobs().values().clear());
            assertEquals(0,patches(d.saveRun111TrackedDelta(new CompoundTag()),"jobs").size());
        } finally {
            if(old==null)System.clearProperty(TrackedRecordMap.FLAG);
            else System.setProperty(TrackedRecordMap.FLAG,old);
        }
    }
    @Test void nonDirtyReadOnlyMapAccessKeepsJournalEmpty() {
        var old=System.getProperty(TrackedRecordMap.FLAG);
        try {
            System.setProperty(TrackedRecordMap.FLAG,"true");
            var data=WorldPrepSavedData.load(baseline("PAUSED"),null);
            assertEquals(1,data.jobs().entrySet().size());
            assertEquals(1,data.jobs().keySet().size());
            assertEquals(1,data.jobs().values().size());
            assertEquals(0,patches(data.saveRun111TrackedDelta(new CompoundTag()),"jobs").size());
        } finally {
            if(old==null)System.clearProperty(TrackedRecordMap.FLAG);
            else System.setProperty(TrackedRecordMap.FLAG,old);
        }
    }
}
