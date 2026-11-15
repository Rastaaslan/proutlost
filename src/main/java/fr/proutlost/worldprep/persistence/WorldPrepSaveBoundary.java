package fr.proutlost.worldprep.persistence;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Pinned ServerLevel.save posts Save after data serialization, before its final I/O wait. */
public final class WorldPrepSaveBoundary {
    public static void save(LevelEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // The structures-only session owns no ecology SavedData. Do not load millions of historical records on a save.
        if (fr.proutlost.worldprep.WorldPrepConfig.STRUCTURES_ONLY.get()) return;
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Save boundary requires server thread");
        var storage=level.getDataStorage();
        var jobs=PagedSavedDataRuntime.withServerLevel(level,WorldPrepSavedData.FILE_ID,()->storage.get(WorldPrepSavedData.FACTORY,WorldPrepSavedData.FILE_ID));
        var blocks=PagedSavedDataRuntime.withServerLevel(level,BlockWorldPrepData.FILE_ID,()->storage.get(BlockWorldPrepData.FACTORY,BlockWorldPrepData.FILE_ID));
        if(jobs!=null)jobs.flushNativeSave();
        if(blocks!=null)blocks.flushNativeSave();
    }
    private WorldPrepSaveBoundary() {}
}
