package fr.proutlost.worldprep.physical;

import static org.junit.jupiter.api.Assertions.*;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.storage.BatchManifestStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContraV5CompositeRouteTest {
    private static final UUID ECO=UUID.fromString("5cefa3c9-7c11-495e-946f-bef26149902e");
    private static WorldPrepSavedData.Area area(){
        return new WorldPrepSavedData.Area("proutlost:contra_s2_ecology","minecraft:overworld",16,16,4079,4079);
    }
    private static BatchManifestStore.Header header(){
        return new BatchManifestStore.Header(1,ECO,"minecraft:overworld",
            "proutlost:contra_s2_ecology",27594267L,16,16,4079,4079,645160L,
            "ecology-batch-v3");
    }
    @Test void onlyExactNativeGlobalContraParentMayEnterSeventeenChildren(){
        assertTrue(ContraV5CompositeRoute.exactArea(area(),27594267L));
        assertFalse(ContraV5CompositeRoute.exactArea(area(),27594268L));
        assertTrue(ContraV5CompositeRoute.exactParent(area(),header(),27594267L));
        assertFalse(ContraV5CompositeRoute.exactParent(area(),header(),27594268L));
        assertFalse(ContraV5CompositeRoute.exactParent(
            new WorldPrepSavedData.Area("proutlost:another","minecraft:overworld",16,16,4079,4079),
            header(),27594267L));
        assertFalse(ContraV5CompositeRoute.exactParent(
            new WorldPrepSavedData.Area("proutlost:contra_s2_ecology","minecraft:overworld",0,0,4079,4079),
            header(),27594267L));
        assertFalse(ContraV5CompositeRoute.exactParent(area(),
            new BatchManifestStore.Header(1,ECO,"minecraft:overworld",
                "proutlost:contra_s2_ecology",27594267L,16,16,4079,4079,
                645159L,"ecology-batch-v3"),27594267L));
        assertFalse(ContraV5CompositeRoute.exactParent(null,header(),27594267L));
        assertFalse(ContraV5CompositeRoute.exactParent(area(),null,27594267L));
    }
    @Test void everyOneOf1024NativeGroupsMapsToOneOf17DisjointAtMost64SectorWals()throws IOException{
        var m=ContraV5SegmentedManifest.create(
            UUID.fromString("abbbdcee-4675-40e7-b8fd-d261f1dd49a0"),ECO,
            "a".repeat(64),List.of("minecraft:cow","minecraft:cod"));
        Path root=Path.of("/separate-qualification-artifacts");
        Set<Path> seen=new HashSet<>();
        for(int n=0;n<1024;n++){
            Path group=ContraV5CompositeRoute.structureGroup(root,m,n);
            assertTrue(seen.add(group),"Repeated native sector/group WAL path");
            assertTrue(group.toString().contains("/contra-v5-segmented-structures/sector-"));
        }
        assertEquals(1024,seen.size());
        assertEquals(17,m.children().size());
        assertEquals(1024,m.totalGroups());
        assertEquals(64516,m.totalChunks());
        for(var c:m.children())assertTrue(c.groups()>=1&&c.groups()<=64);
        assertThrows(IOException.class,()->ContraV5CompositeRoute.structureGroup(root,m,-1));
        assertThrows(IOException.class,()->ContraV5CompositeRoute.structureGroup(root,m,1024));
        assertThrows(IOException.class,()->ContraV5CompositeRoute.structureGroup(root,null,0));
    }
}
