package fr.proutlost.worldprep.persistence;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit ONCE-ONLY migration of a cold full-world SHA-verified clone,
 * never a production original, never an active Minecraft session.
 * Its two legacy metadata roots have been backed up and verified independently.
 */
class Run35OfflineColdMigrationTest {
    private static final Path ROOT=Path.of("/srv/storage/proutlost-s2-candidates/contra-20261027");
    private static final Path WORLD=Path.of("/home/damien/contra-ecology-run35-paged/Contra-worldprep-modern-run35");
    private static final Path STAGE=ROOT.resolve("CONTRA_RUN35_COLD_STAGE_PRE_MIGRATION_20261028.json");
    private static final Path PROOF=ROOT.resolve("CONTRA_RUN35_PAGED_OFFLINE_COLD_MIGRATION_PROOF_20261028.json");

    private static String sha(Path p)throws Exception{
        return ImmutableBucketedNbtCheckpoint.fileSha256(p);
    }
    private static void syncDir(Path p)throws Exception{
        try(var c=FileChannel.open(p,StandardOpenOption.READ)){c.force(true);}
    }
    private static void commit(CompoundTag compact,Path dest)throws Exception{
        Path tmp=Files.createTempFile(dest.getParent(),".run35-paged-root-",".partial");
        // Same NBT GZIP level=1 + tempfile fsync + atomic move + parent fsync.
        try(var raw=Files.newOutputStream(tmp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING);
            var buffered=new BufferedOutputStream(raw,256*1024);
            var zip=new GZIPOutputStream(buffered,256*1024){{def.setLevel(1);}};
            var data=new DataOutputStream(zip)){
            NbtIo.write(compact,data);
        }
        try(var c=FileChannel.open(tmp,StandardOpenOption.WRITE)){c.force(true);}
        Files.move(tmp,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        syncDir(dest.getParent());
    }

    @Test void exactlyColdClonedBothOriginalStoresMigrateWithoutAnyLostNBTRecord()throws Exception{
        Assumptions.assumeTrue(Boolean.getBoolean("contra.run35.explicitColdMigration"),
            "RUN35 migration has NO implicit/manual Gradle test invocation");
        assertTrue(Files.isRegularFile(STAGE)&&!Files.exists(PROOF));
        JsonObject stage=com.google.gson.JsonParser.parseString(Files.readString(STAGE)).getAsJsonObject();
        assertEquals("CONTRA_RUN35_COLD_CLONE_A_AND_B_VERIFIED_PRE_MIGRATION",
            stage.get("status").getAsString());
        assertEquals(WORLD.toRealPath(),Path.of(stage.get("candidateWorld").getAsString()).toRealPath());
        assertTrue(stage.get("fileCount").getAsInt()>60000);
        assertTrue(stage.get("sameOriginalWorldAndTwoBackupsSHA").getAsBoolean());
        for(String n:new String[]{"proutlost_worldprep","proutlost_worldprep_blocks"}){
            var p=WORLD.resolve("data/"+n+".dat");
            assertTrue(Files.isRegularFile(p));
            assertEquals(stage.getAsJsonObject("legacyRootsSha256").get(n).getAsString(),sha(p));
            assertFalse(ImmutableBucketedNbtCheckpoint.isPaged(
                NbtIo.readCompressed(p,NbtAccounter.unlimitedHeap())));
        }
        var stores=new com.google.gson.JsonArray();
        for(String n:new String[]{"proutlost_worldprep","proutlost_worldprep_blocks"}){
            Path p=WORLD.resolve("data/"+n+".dat");
            var original=NbtIo.readCompressed(p,NbtAccounter.unlimitedHeap());
            String legacySha=sha(p);
            var paged=ImmutableBucketedNbtCheckpoint.externalize(original,p,n);
            assertTrue(ImmutableBucketedNbtCheckpoint.isPaged(paged.compactRoot()));
            assertEquals(original,ImmutableBucketedNbtCheckpoint.expand(paged.compactRoot(),p,n));
            // Even if a process fails here, the old legacy root is authoritative.
            assertEquals(legacySha,sha(p));
            commit(paged.compactRoot(),p);
            var reopened=NbtIo.readCompressed(p,NbtAccounter.unlimitedHeap());
            assertTrue(ImmutableBucketedNbtCheckpoint.isPaged(reopened));
            assertEquals(original,ImmutableBucketedNbtCheckpoint.expand(reopened,p,n));
            var entry=new JsonObject();
            entry.addProperty("store",n);
            entry.addProperty("legacyRootSha256",legacySha);
            entry.addProperty("pagedRootSha256",sha(p));
            entry.addProperty("pagedIndexBytes",Files.size(p));
            entry.addProperty("newLeafPages",paged.metrics().newPages());
            entry.addProperty("referencePages",paged.metrics().referencedPages());
            entry.addProperty("losslessMinecraftNBTRoundTrip",true);
            stores.add(entry);
            System.out.println("RUN35_COLD_NATIVE_NBT_MIGRATION_PASS"+
                " store="+n+" rootBytes="+Files.size(p)+" pages="+
                paged.metrics().referencedPages());
        }
        var result=new JsonObject();
        result.addProperty("status","RUN35_BOTH_ORIGINAL_COLD_STORES_LOSSLESS_PAGED_AND_FSYNCED");
        result.addProperty("source",WORLD.toString());
        result.addProperty("beforeStageSHA256",sha(STAGE));
        result.add("stores",stores);
        result.addProperty("originalImmutableReceiptsAndAnvilNeverModified",true);
        result.addProperty("V5Certified",false);
        Files.writeString(PROOF,result.toString()+"\n",
            StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        try(var c=FileChannel.open(PROOF,StandardOpenOption.WRITE)){c.force(true);}
        syncDir(ROOT);
        System.out.println("RUN35_COLD_MIGRATION_BOTH_STORES_LOSSLESS_DONE "+sha(PROOF));
    }
}
