package fr.proutlost.worldprep.physical;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.SimpleBitStorage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

/** Pure persisted-data/bounds checks; native PoolElement movement requires a real NeoForge
 * loader and is checked per piece during the actual candidate import. */
class NativeStructureTerrainImportTest {
    @Test void persistedHeightmapDecodingHandlesNegativeWorldMinAndChunkCoordinates()throws Exception {
        var map=new SimpleBitStorage(9,256);
        map.set(3+14*16,129);
        var heights=new CompoundTag();heights.putLongArray("MOTION_BLOCKING_NO_LEAVES",map.getRaw());
        var chunk=new CompoundTag();chunk.put("Heightmaps",heights);
        assertEquals(65,NativeStructureTerrainImport.surfaceFromNbt(chunk,19,-2,-64,320));
        assertThrows(IOException.class,()->NativeStructureTerrainImport.surfaceFromNbt(chunk,20,-2,-64,320));
        heights.putLongArray("MOTION_BLOCKING_NO_LEAVES",new long[]{1});
        assertThrows(IOException.class,()->NativeStructureTerrainImport.surfaceFromNbt(chunk,19,-2,-64,320));
        assertThrows(IOException.class,()->NativeStructureTerrainImport.surfaceFromNbt(new CompoundTag(),19,-2,-64,320));
    }
    @Test void translatedBoundsRemainExactAndNeverClipAtWorldLimits()throws Exception {
        var before=new NativeRegistryStructurePass.Bounds(100,-20,200,120,30,240);
        var after=NativeStructureTerrainImport.translateBounds(before,55,-64,320);
        assertEquals(new NativeRegistryStructurePass.Bounds(100,35,200,120,85,240),after);
        assertThrows(IOException.class,()->NativeStructureTerrainImport.translateBounds(before,-45,-64,320));
        assertThrows(IOException.class,()->NativeStructureTerrainImport.translateBounds(before,290,-64,320));
    }
}
