package fr.proutlost.worldprep;

import com.google.gson.*;
import fr.proutlost.worldprep.orchestration.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.*;
import java.util.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in true 47,331-file x3 integrity test on independently backed up S2.
 * Never modifies original, laboratory, backups or player servers.
 */
class RealV5FrozenBackupContinuityTest {
    private static final Path ROOT=Path.of(
        "/home/damien/worldprep-s2-integration-20261024");
    private static final UUID BATCH=UUID.fromString(
        "46fd7fe3-8ecc-3256-b4c9-08a26b4f42c8");
    private static final Path WORLD=ROOT.resolve("dragon_island_s2_v5_prebound_dev");
    private static final Path QUAL=WORLD.resolve(
        "worldprep-v2/physical/3f60de212b487e6204ac185257284bcd73de9a987006a6622704f5fe54409bcc/qualification-"+BATCH);
    private static final Path CONTINUITY=ROOT.resolve(
        "S2_CB20159_STRICT_COLD_SOURCE_CONTINUITY_WITH_BYTES.json");
    private static void optIn(){
        Assumptions.assumeTrue("1".equals(System.getenv("PROUTLOST_S2_REAL_COLD_AUDIT")),
            "Real 3-copy inventory is opt-in and read-only");
    }
    private static PagedBackupVerifier.Header header(String side)throws IOException{
        var file=QUAL.resolve("backup-"+side+".json");
        return new Gson().fromJson(Files.readString(file),PagedBackupVerifier.Header.class);
    }
    @Test void actualTwoColdBackupsAndLiveSemanticContinuityAreFullyRechecked()
            throws Exception {
        optIn();
        try(var verifier=new FrozenBackupPairVerifier(
            WORLD,header("a"),header("b"),
            QUAL.resolve("backup-a.json.pages"),QUAL.resolve("backup-b.json.pages"),
            CONTINUITY,BATCH)) {
            int ticks=0;
            while(!verifier.stepBounded()){
                assertTrue(++ticks<200_000,"Strict bounded proof must terminate");
            }
            assertEquals(47331L,verifier.verifiedFiles());
            assertTrue(verifier.semanticProofSha256().matches("[0-9a-f]{64}"));
        }
    }
    @Test void forgedSourceContinuityBatchIdentityIsRejectedBeforeReadingFiles()
            throws Exception {
        optIn();
        var source=Files.readString(CONTINUITY);
        var changed=source.replace(BATCH.toString(),
            "00000000-0000-0000-0000-000000000000");
        assertNotEquals(source,changed);
        var temp=Files.createTempFile("s2-v5-invalid-continuity-",".json");
        try {
            Files.writeString(temp,changed);
            assertThrows(IOException.class,()->new FrozenBackupPairVerifier(
                WORLD,header("a"),header("b"),
                QUAL.resolve("backup-a.json.pages"),QUAL.resolve("backup-b.json.pages"),
                temp,BATCH));
        }finally{Files.deleteIfExists(temp);}
    }
}
