package fr.proutlost.worldprep;

import com.google.gson.Gson;
import fr.proutlost.worldprep.orchestration.MultiSpeciesFaunaPlan;
import fr.proutlost.worldprep.orchestration.MultiSpeciesStageExecution;
import fr.proutlost.worldprep.physical.PhysicalStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class MultiSpeciesAbruptExitTest {
    @TempDir Path scratch;

    private static String javaClasspath(){
        String path=System.getProperty("proutlost.forkedRuntimeClasspath");
        assertNotNull(path,"Gradle must pin the exact NeoForge JUnit runtime classpath");
        assertFalse(path.isBlank(),"Empty isolated crash test classpath refused");
        return path;
    }
    private int run(String phase,Path dir)throws Exception {
        var process=new ProcessBuilder(
            Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "-Xms32m","-Xmx128m","-cp",javaClasspath(),
            MultiSpeciesAbruptExitWorker.class.getName(),
            phase,dir.toString())
            .redirectErrorStream(true)
            .start();
        assertTrue(process.waitFor(20,TimeUnit.SECONDS),"Disposable child JVM deadline");
        int code=process.exitValue();
        String output=new String(process.getInputStream().readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        if(phase.equals("recover")){
            assertEquals(0,code,output);
            assertTrue(output.contains("ABRUPT_V5_RESTART_PROOF_PASS"),output);
        }
        if(code==1)fail("Forked test JVM failed before the requested crash boundary: "+output);
        return code;
    }

    @Test void trueAbruptDeathAfterParentIntentBeforeChildNeverInventsCompletion()
        throws Exception {
        Path dir=Files.createTempDirectory(scratch,"junit-abrupt-v5-before-open-");
        assertEquals(74,run("beforeOpen",dir));
        var parent=new PhysicalStore(dir.resolve("parent"));
        assertTrue(parent.exists("stage-0-intent"));
        assertFalse(parent.exists("stage-0"));
        assertFalse(parent.exists("aggregate"));
        assertFalse(Files.exists(dir.resolve("child-0/evidence.json")));
        assertEquals(0,run("recover",dir));
        assertEquals(2,parent.read("aggregate",MultiSpeciesStageExecution.Aggregate.class).stages());
    }

    @Test void trueAbruptDeathAfterBothReceiptsBeforeAggregateRestoresNoChildren()
        throws Exception {
        Path dir=Files.createTempDirectory(scratch,"junit-abrupt-v5-after-both-");
        assertEquals(75,run("afterBoth",dir));
        var parent=new PhysicalStore(dir.resolve("parent"));
        assertTrue(parent.exists("stage-0"));
        assertTrue(parent.exists("stage-1"));
        assertFalse(parent.exists("aggregate"));
        // All children already have their durable receipts. The new JVM may
        // publish the terminal aggregate but cannot re-run either child task.
        assertEquals(0,run("recover",dir));
        assertEquals(2,parent.read("aggregate",MultiSpeciesStageExecution.Aggregate.class).stages());
    }

    @Test void trueAbruptProcessDeathBeforeParentReceiptKeepsOnlyDurableChildIntent()
         throws Exception {
        Path dir=Files.createTempDirectory(scratch,"junit-abrupt-v5-before-");
        assertEquals(72,run("before",dir));
        var store=new PhysicalStore(dir.resolve("parent"));
        assertTrue(store.exists("stage-0-intent"));
        assertFalse(store.exists("stage-0"));
        assertFalse(store.exists("aggregate"));
        assertTrue(new PhysicalStore(dir.resolve("child-0")).exists("evidence"));
        assertEquals(0,run("recover",dir));
        assertEquals(2,store.read("aggregate",MultiSpeciesStageExecution.Aggregate.class).stages());
    }

    @Test void trueAbruptProcessDeathAfterParentReceiptDoesNotDuplicateStage()
         throws Exception {
        Path dir=Files.createTempDirectory(scratch,"junit-abrupt-v5-after-");
        assertEquals(73,run("after",dir));
        var store=new PhysicalStore(dir.resolve("parent"));
        assertTrue(store.exists("stage-0"));
        assertFalse(store.exists("stage-1"));
        assertFalse(store.exists("aggregate"));
        assertEquals(0,run("recover",dir));
        assertTrue(store.exists("stage-1"));
        assertEquals(2,store.read("aggregate",MultiSpeciesStageExecution.Aggregate.class).stages());
    }
}
