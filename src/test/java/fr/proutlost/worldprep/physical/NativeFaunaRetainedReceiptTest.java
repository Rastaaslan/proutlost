package fr.proutlost.worldprep.physical;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Crash-window WAL inspection without a server, world mutation or entity respawn. */
class NativeFaunaRetainedReceiptTest {
    @TempDir Path scratch;
    private static final NativeFaunaPass.Animal ANIMAL = new NativeFaunaPass.Animal(
        UUID.fromString("d370d082-ef03-5aa4-9b7c-875598370121"),4956,81,-1364);
    private static final String KEY = ANIMAL.uuid().toString();
    private PhysicalStore reopen()throws IOException {
        return new PhysicalStore(scratch.resolve("fauna-wal"));
    }
    @Test void emptyWalIsNotMistakenForAnIssuedAnimal()throws Exception {
        assertEquals(NativeFaunaPass.RetainedSpawn.NEW,
            NativeFaunaPass.retainedSpawn(reopen(),ANIMAL));
    }
    @Test void durableIntentWithoutIssueRequiresConservativeReconciliation()throws Exception {
        reopen().write("intent-"+KEY,ANIMAL);
        assertEquals(NativeFaunaPass.RetainedSpawn.INTENT_ONLY,
            NativeFaunaPass.retainedSpawn(reopen(),ANIMAL));
    }
    @Test void durableIssuedAnimalNeverBecomesFreshAfterReopening()throws Exception {
        reopen().write("intent-"+KEY,ANIMAL);
        reopen().write("issued-"+KEY,ANIMAL);
        assertEquals(NativeFaunaPass.RetainedSpawn.ISSUED,
            NativeFaunaPass.retainedSpawn(reopen(),ANIMAL));
    }
    @Test void issuedReceiptWithoutPriorWalIntentIsRejected()throws Exception {
        reopen().write("issued-"+KEY,ANIMAL);
        var error=assertThrows(IOException.class,()->NativeFaunaPass.retainedSpawn(reopen(),ANIMAL));
        assertTrue(error.getMessage().contains("ORPHAN_ISSUED"));
    }
    @Test void validChecksumCannotHideDifferentIntentCoordinates()throws Exception {
        reopen().write("intent-"+KEY,new NativeFaunaPass.Animal(ANIMAL.uuid(),4957,81,-1364));
        var error=assertThrows(IOException.class,()->NativeFaunaPass.retainedSpawn(reopen(),ANIMAL));
        assertTrue(error.getMessage().contains("FOREIGN_FAUNA_INTENT"));
    }
    @Test void validChecksumCannotHideDifferentIssuedCoordinates()throws Exception {
        reopen().write("intent-"+KEY,ANIMAL);
        reopen().write("issued-"+KEY,new NativeFaunaPass.Animal(ANIMAL.uuid(),4957,81,-1364));
        var error=assertThrows(IOException.class,()->NativeFaunaPass.retainedSpawn(reopen(),ANIMAL));
        assertTrue(error.getMessage().contains("FOREIGN_FAUNA_ISSUED"));
    }
    private static String forkedRuntimeClasspath() {
        var value=System.getProperty("proutlost.forkedRuntimeClasspath");
        if(value==null||value.isBlank())throw new IllegalStateException("Gradle must pin the exact NeoForge JUnit runtime classpath");
        return value;
    }
    private int crashInChildJVM(String window)throws Exception {
        var java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        var builder=new ProcessBuilder(java,"-Xmx128m","-cp",
            forkedRuntimeClasspath(),
            NativeFaunaCrashChild.class.getName(),window,scratch.toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(scratch.resolve("crash-"+window+".log").toFile());
        var child=builder.start();
        if(!child.waitFor(20,TimeUnit.SECONDS)) {
            child.destroyForcibly();
            fail("Disposable crash child hung");
        }
        return child.exitValue();
    }
    @Test void actualAbruptJVMExitAfterIntentLeavesReconciliationRequired()throws Exception {
        assertEquals(72,crashInChildJVM("intent"));
        assertEquals(NativeFaunaPass.RetainedSpawn.INTENT_ONLY,
            NativeFaunaPass.retainedSpawn(reopen(),ANIMAL));
        assertFalse(reopen().exists("issued-"+KEY));
    }
    @Test void actualAbruptJVMExitAfterIssuedNeverAuthorizesAnotherSpawn()throws Exception {
        assertEquals(73,crashInChildJVM("issued"));
        assertEquals(NativeFaunaPass.RetainedSpawn.ISSUED,
            NativeFaunaPass.retainedSpawn(reopen(),ANIMAL));
    }
    @Test void damagedOrTruncatedWalEnvelopeCannotBeAccepted()throws Exception {
        reopen().write("intent-"+KEY,ANIMAL);
        var wal=scratch.resolve("fauna-wal/intent-"+KEY+".json");
        Files.writeString(wal,"{",java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(IOException.class,()->NativeFaunaPass.retainedSpawn(reopen(),ANIMAL));
    }
}
