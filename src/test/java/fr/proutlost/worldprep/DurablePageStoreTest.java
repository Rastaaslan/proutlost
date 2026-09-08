package fr.proutlost.worldprep;

import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.storage.DurablePageStore;
import fr.proutlost.worldprep.storage.PageCursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DurablePageStoreTest {
    @TempDir Path temp;
    @Test void planAndJournalPagesRoundTrip() throws Exception {
        for (var kind : DurablePageStore.StorageKind.values()) {
            var page = DurablePageStore.page(kind, UUID.randomUUID(), PassId.GEOLOGY, "minecraft:overworld", -2, 3, 7, 2, new byte[]{1,2,3});
            Path path=temp.resolve(kind.name()); DurablePageStore.publish(path,page);
            var loaded=DurablePageStore.read(path);
            assertEquals(page.storageKind(),loaded.storageKind()); assertEquals(page.ownerId(),loaded.ownerId()); assertEquals(page.checksum(),loaded.checksum()); assertArrayEquals(page.payload(),loaded.payload());
        }
    }
    @Test void corruptionAndTruncationFailClosed() throws Exception {
        Path path=temp.resolve("page");
        DurablePageStore.publish(path,DurablePageStore.page(DurablePageStore.StorageKind.PLAN,UUID.randomUUID(),PassId.BIOMES,"minecraft:overworld",0,0,0,1,new byte[]{4,5,6}));
        byte[] bytes=Files.readAllBytes(path); bytes[bytes.length-67]^=1; Files.write(path,bytes);
        assertThrows(IOException.class,()->DurablePageStore.read(path));
        Files.write(path,java.util.Arrays.copyOf(bytes,8));
        assertThrows(IOException.class,()->DurablePageStore.read(path));
    }
    @Test void pageCursorRejectsNegativeValues(){assertEquals(PageCursor.START,new PageCursor(0,0));assertThrows(IllegalArgumentException.class,()->new PageCursor(-1,0));}
}
