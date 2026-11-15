package fr.proutlost.worldprep;

import com.google.gson.Gson;
import fr.proutlost.worldprep.orchestration.BackupAuditPair;
import fr.proutlost.worldprep.orchestration.PagedBackupVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real snapshot verification; not run in normal JUnit. No Minecraft process or map writes. */
class RealOfflineV4BackupVerificationTest {
    static double parseFullPressure(String content) throws java.io.IOException {
        for(String line:content.split("\\R"))if(line.startsWith("full ")) {
            for(String part:line.split(" "))if(part.startsWith("avg10=")) {
                try {
                    double value=Double.parseDouble(part.substring("avg10=".length()));
                    if(Double.isFinite(value) && value>=0)return value;
                }catch(NumberFormatException error){throw new java.io.IOException("Invalid PSI full avg10",error);}
            }
        }
        throw new java.io.IOException("Missing PSI full avg10: cannot safely run disk audit");
    }
    static double ioFullPressure() throws java.io.IOException {
        return parseFullPressure(Files.readString(Path.of("/proc/pressure/io")));
    }
    @Test void ioPressureParsingFailsClosed() throws Exception {
        assertEquals(12.5,parseFullPressure("some avg10=99.0 avg60=0.0\nfull avg10=12.5 avg60=0.2 total=5"),0.0001);
        assertThrows(java.io.IOException.class,()->parseFullPressure("some avg10=0.1"));
    }
    @Test void independentlyAuditBothRealBackupTreesAndRetainedPagination() throws Exception {
        String configured = System.getenv("WORLDPREP_V4_PREPARED_ROOT");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "External offline backup pair not requested");
        Path metadata=Path.of(configured).toRealPath();
        Gson json=new Gson();
        var a=json.fromJson(Files.readString(metadata.resolve("backup-a.json")),PagedBackupVerifier.Header.class);
        var b=json.fromJson(Files.readString(metadata.resolve("backup-b.json")),PagedBackupVerifier.Header.class);
        assertNotEquals(a.backupRoot(),b.backupRoot());
        assertEquals(a.sourceRoot(),b.sourceRoot());
        assertEquals(a.files(),b.files());
        Path source=Path.of(a.sourceRoot());
        Path audit=metadata.resolve("java-independent-audit");
        Set<Path> exclusions=Set.of(Path.of("worldprep-qualification.json"),Path.of("worldprep-v2/physical"));
        try(var pair=BackupAuditPair.open(a,b,source,metadata.resolve("backup-a.json.pages"),
                metadata.resolve("backup-b.json.pages"),audit,exclusions)) {
            boolean first=false,second=false;
            // HDD mode: finish one tree before switching to the second, to avoid
            // needless seek thrashing across 2 x ~47k small files. Integrity,
            // durable receipts and full coverage checks remain unchanged.
            boolean serial="true".equals(System.getenv("WORLDPREP_V4_SERIAL_HDD_AUDIT"));
            int maxBatches=Integer.parseInt(System.getenv().getOrDefault("WORLDPREP_V4_MAX_BATCHES","300000"));
            long last=System.nanoTime();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(
                Long.parseLong(System.getenv().getOrDefault("WORLDPREP_V4_MAX_SESSION_SECONDS","120")));
            int completedBatches=0;
            for(int i=0;i<maxBatches && (!first||!second);i++) {
                // Fail closed under storage contention: never spin through 94k files
                // while a player or other host workload needs the disk.
                while(ioFullPressure()>12.0) {
                    if(System.nanoTime()>deadline)break;
                    Thread.sleep(1500);
                }
                if(System.nanoTime()>deadline)break;
                if(!first)first=pair.first().stepBounded();
                if(!second && (!serial || first))second=pair.second().stepBounded();
                completedBatches++;
                // Metadata-only coverage needs far less pacing than content hashing.
                Thread.sleep(pair.first().completedPages()==a.pages()
                    &&pair.second().completedPages()==b.pages()?20:150);
            }
            System.out.println("WORLDPREP_V4_AUDIT_PROGRESS serialHdd="+serial+
                " batches="+completedBatches+
                " pagesA="+pair.first().completedPages()+"/"+a.pages()+
                " pagesB="+pair.second().completedPages()+"/"+b.pages()+
                " durationSeconds="+java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-last));
            // Incomplete bounded runs are clearly SKIPPED, never reported as PASS.
            Assumptions.assumeTrue(first && second,
                "PARTIAL_IO_GUARDED_AUDIT: cursors retained, not full backup qualification");
            assertEquals(a.pages(),pair.first().completedPages());
            assertEquals(b.pages(),pair.second().completedPages());
            assertTrue(Files.isRegularFile(audit.resolve("backup-a/cursor.json")));
            assertTrue(Files.isRegularFile(audit.resolve("backup-b/cursor.json")));
        }
        try(var resumed=BackupAuditPair.open(a,b,source,metadata.resolve("backup-a.json.pages"),
                metadata.resolve("backup-b.json.pages"),audit,exclusions)) {
            assertEquals(a.pages(),resumed.first().completedPages(),"First durable page cursor retained");
            assertEquals(b.pages(),resumed.second().completedPages(),"Second durable page cursor retained");
            // A new owner must replay its immutable page chain before returning a new
            // completion proof; a single step is NOT an immediate success receipt.
            assertFalse(resumed.first().stepBounded(),"First replay cannot skip retained evidence");
            assertFalse(resumed.second().stepBounded(),"Second replay cannot skip retained evidence");
        }
    }
}
