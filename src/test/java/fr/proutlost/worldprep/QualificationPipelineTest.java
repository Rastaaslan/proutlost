package fr.proutlost.worldprep;

import fr.proutlost.worldprep.orchestration.QualificationPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class QualificationPipelineTest {
    @TempDir Path root;
    final UUID batch = UUID.fromString("f1d00b2a-d490-466e-ae03-0cab665a2ceb");
    final String config = "a".repeat(64), evidence = "b".repeat(64);
    QualificationPipeline open() throws IOException { return new QualificationPipeline(root, batch, config); }
    @Test void twoPhasesAndCrashAtBoundaryResumeSameOperation() throws Exception {
        UUID operation;
        try (var pipeline = open()) {
            var first = pipeline.begin(); assertFalse(first.reconciliationRequired());
            pipeline.complete(first, evidence);
            var second = pipeline.begin(); operation = second.operation();
        }
        try (var pipeline = open()) {
            assertEquals(1, pipeline.cursor());
            var pending = pipeline.begin();
            assertTrue(pending.reconciliationRequired()); assertEquals(operation, pending.operation());
            pipeline.complete(pending, evidence); assertEquals(2, pipeline.cursor());
        }
    }
    @Test void concurrentOwnerRejectedAndClosedOwnerFenced() throws Exception {
        var owner = open();
        try { assertThrows(IOException.class, this::open); } finally { owner.close(); }
        assertThrows(IOException.class, owner::begin);
        try (var next = open()) { assertEquals(0, next.cursor()); }
    }
    @Test void configChangeRejected() throws Exception {
        try (var ignored = open()) {}
        assertThrows(IOException.class, () -> new QualificationPipeline(root, batch, "c".repeat(64)));
    }
    @Test void partialReceiptRefusesResume() throws Exception {
        try (var p = open()) { p.begin(); }
        Files.writeString(root.resolve("0-ECOLOGY_RECEIPTS.receipt"), "partial");
        assertThrows(IOException.class, this::open);
    }
    @Test void tamperedPredecessorRefusesResume() throws Exception {
        try (var p = open()) { p.complete(p.begin(), evidence); p.begin(); }
        Path receipt = root.resolve("0-ECOLOGY_RECEIPTS.receipt");
        Files.writeString(receipt, Files.readString(receipt).replace(evidence, "c".repeat(64)));
        assertThrows(IOException.class, this::open);
    }
    @Test void diskFailureCannotAdvanceCursor() throws Exception {
        try (var p = open()) {
            var pending = p.begin(); Files.createDirectory(root.resolve("0-ECOLOGY_RECEIPTS.receipt"));
            assertThrows(IOException.class, () -> p.complete(pending, evidence));
        }
    }
    @Test void staleCompletionAndUnprovenDigestRejected() throws Exception {
        try (var p = open()) {
            var pending = p.begin();
            assertThrows(IllegalArgumentException.class, () -> p.complete(pending, ""));
            p.complete(pending, evidence);
            assertThrows(IOException.class, () -> p.complete(pending, evidence));
        }
    }
    @Test void abandonedStagingIsNotCompletion() throws Exception {
        try (var p = open()) { p.begin(); }
        Files.writeString(root.resolve(".pending-abandoned.partial"), "incomplete");
        try (var p = open()) { assertEquals(0, p.cursor()); assertTrue(p.begin().reconciliationRequired()); }
    }
    public static class CrashWriter {
        public static void main(String[] args) throws Exception {
            try (var p = new QualificationPipeline(Path.of(args[0]), UUID.fromString(args[1]), args[2])) {
                p.complete(p.begin(), "b".repeat(64));
                p.begin();
                System.out.println("INTENT_DURABLE"); System.out.flush();
                System.in.read();
            }
        }
    }
    @Test void forciblyKilledProcessResumesAtSecondPhase() throws Exception {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m", "-XX:ActiveProcessorCount=1", "-cp", System.getProperty("java.class.path"),
                CrashWriter.class.getName(), root.toString(), batch.toString(), config)
                .redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!Files.readString(root.resolve("child.log")).contains("INTENT_DURABLE")
                    && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.readString(root.resolve("child.log")).contains("INTENT_DURABLE"));
            process.destroyForcibly();
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            try (var p = open()) {
                assertEquals(1, p.cursor());
                var pending = p.begin();
                assertEquals(QualificationPipeline.Phase.INTEGRITY_AND_BACKUP, pending.phase());
                assertTrue(pending.reconciliationRequired());
                p.complete(pending, evidence); assertEquals(2, p.cursor());
            }
        } finally {
            process.destroyForcibly(); process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

}
