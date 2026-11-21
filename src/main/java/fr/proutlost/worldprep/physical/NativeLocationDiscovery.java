package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.util.*;

/** Complete bounded metadata survey, NOT physical validation of arbitrary structure pieces.
 * One existing FULL chunk per step, immutable chained receipts; restart revalidates each
 * retained receipt in order. No chunks loaded/generated, no whole-history serialization.
 */
final class NativeLocationDiscovery implements AutoCloseable {
    private final NativeChunkWindow window;
    public void close(){window.close();}
    record Observation(int version,int x,int z,String previous,String diskMetadataSha256,
                       int liveStarts,int liveReferences,boolean protectedOverlap,String disposition) {}
    private final ServerLevel level;private final WorldPrepSavedData.Area area;private final PhysicalStore store;
    private int cursor;private String chain="0".repeat(64);
    NativeLocationDiscovery(ServerLevel level,WorldPrepSavedData.Area area,PhysicalStore store)throws IOException {
        this.level=level;this.area=area;this.store=store;window=new NativeChunkWindow(level);
        long count=((long)(area.maxX()>>4)-(area.minX()>>4)+1)*((long)(area.maxZ()>>4)-(area.minZ()>>4)+1);
        boolean exactContra=ContraV5CompositeRoute.exactArea(area,level.getSeed());
        if(count<1||(count>4096&&!(exactContra&&count==64516)))
            throw new IOException("Location survey exceeds bounded perimeter");
        store.write("area",area);
    }
    boolean step()throws IOException {
        NativePhysicalRuntime.requireThread(level);
        int width=(area.maxX()>>4)-(area.minX()>>4)+1,count=width*((area.maxZ()>>4)-(area.minZ()>>4)+1);
        if(cursor==count){window.close();store.write("complete",List.of(Integer.toString(count),chain,"METADATA_ONLY_NOT_PHYSICAL_READY"));return true;}
        int cx=(area.minX()>>4)+cursor%width,cz=(area.minZ()>>4)+cursor/width;
        if(!window.blocks(cx<<4,cz<<4,cx<<4,cz<<4))return false;
        var live=ServerExistingChunkAccess.requireChunk(level,cx,cz);
        var folder=DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
        var nbt=ReadOnlyChunkNbt.readBounded(folder,cx,cz);var metadata=nbt.getCompound("structures");
        String text=metadata.toString();
        if(text.length()>65536||live.getAllStarts().size()>256||live.getAllReferences().size()>256)throw new IOException("Location metadata bound");
        var protections=WorldPrepRuntime.data(level).protections();
        if(protections.size()>256)throw new IOException("Location protection registry bound");
        boolean protectedOverlap=false;
        for(var p:protections.values())if(p.dimension().equals(area.dimension())&&p.minX()<=(cx<<4)+15&&p.maxX()>=(cx<<4)
            &&p.minZ()<=(cz<<4)+15&&p.maxZ()>=(cz<<4))protectedOverlap=true;
        boolean structure=!metadata.getCompound("starts").isEmpty()||!metadata.getCompound("References").isEmpty()
            ||!live.getAllStarts().isEmpty()||!live.getAllReferences().isEmpty();
        var observation=new Observation(1,cx,cz,chain,PhysicalStore.sha(text),live.getAllStarts().size(),live.getAllReferences().size(),protectedOverlap,
            protectedOverlap?"NOT_READY_PROTECTION_POLICIES_AND_VERTICAL_BOUNDS_MISSING":structure?"NOT_READY_STRUCTURE_PIECE_PHYSICAL_ADAPTER_REQUIRED":"NO_STRUCTURE_METADATA_OBSERVED");
        store.write("metadata-"+cursor,text);
        store.write("protections-"+cursor,protections.values().stream().filter(p->p.dimension().equals(area.dimension())&&p.minX()<=(cx<<4)+15&&p.maxX()>=(cx<<4)&&p.minZ()<=(cz<<4)+15&&p.maxZ()>=(cz<<4)).sorted(Comparator.comparing(WorldPrepSavedData.ProtectedZone::id)).toList());
        store.write("chunk-"+cursor,observation);chain=PhysicalStore.sha(store.readText("chunk-"+cursor));cursor++;return false;
    }
}
