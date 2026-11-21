package fr.proutlost.worldprep;
import fr.proutlost.worldprep.physical.PhysicalStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PhysicalStoreTest {
    @TempDir Path root;
    record Intent(UUID operation,String state) {}
    @Test void durableIntentRoundTripsAndConflictingRewriteRefused()throws Exception {
        var store=new PhysicalStore(root);var intent=new Intent(new UUID(1,2),"before/after");
        String digest=store.write("plan",intent);
        assertEquals(intent,new PhysicalStore(root).read("plan",Intent.class));
        assertEquals(digest,store.write("plan",intent));
        assertThrows(IOException.class,()->store.write("plan",new Intent(intent.operation(),"different")));
    }
    @Test void checksumDetectsValidJsonPayloadCorruption()throws Exception {
        var store=new PhysicalStore(root);store.write("plan",new Intent(new UUID(1,2),"before"));
        var path=root.resolve("plan.json");Files.writeString(path,Files.readString(path).replace("before","after!"));
        assertThrows(IOException.class,()->store.read("plan",Intent.class));
    }
    @Test void partialAndOversizedRecordsRefused()throws Exception {
        var store=new PhysicalStore(root);Files.writeString(root.resolve("plan.json"),"{\"version\":1");
        assertThrows(IOException.class,()->store.readText("plan"));
        assertThrows(IOException.class,()->store.write("huge","x".repeat(PhysicalStore.MAX_BYTES)));
    }
    @Test void symlinkCannotRedirectJournal()throws Exception {
        var store=new PhysicalStore(root);var outside=root.resolve("outside");Files.writeString(outside,"original");
        Files.createSymbolicLink(root.resolve("plan.json"),outside);
        assertThrows(IOException.class,()->store.write("plan",Map.of("value","changed")));
        assertEquals("original",Files.readString(outside));
    }
    @org.junit.jupiter.api.Test void objectKeyOrderAcrossRestartsPreservesOriginalReceiptBytes()throws Exception {
        var directory=java.nio.file.Files.createDirectory(root.resolve("ordered"));
        var store=new fr.proutlost.worldprep.physical.PhysicalStore(directory);
        var first=new java.util.LinkedHashMap<String,Object>();first.put("operation","same");first.put("cursor",4);
        String digest=store.write("receipt",first),bytes=store.readText("receipt");
        var reversed=new java.util.LinkedHashMap<String,Object>();reversed.put("cursor",4);reversed.put("operation","same");
        var reopened=new fr.proutlost.worldprep.physical.PhysicalStore(directory);
        org.junit.jupiter.api.Assertions.assertEquals(digest,reopened.write("receipt",reversed));
        org.junit.jupiter.api.Assertions.assertEquals(bytes,reopened.readText("receipt"));
        reversed.put("cursor",5);
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->reopened.write("receipt",reversed));
    }
}
