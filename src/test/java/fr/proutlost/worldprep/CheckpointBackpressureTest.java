package fr.proutlost.worldprep;

import fr.proutlost.worldprep.storage.CheckpointBackpressure;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CheckpointBackpressureTest {
    @Test void slowIoBlocksCaptureAndRequiresExactAcknowledgement() {
        var writes = new ArrayDeque<Runnable>();
        var gate = new CheckpointBackpressure(writes::add);
        var captures = new AtomicInteger();
        var id = UUID.randomUUID();
        assertTrue(gate.submit(id, () -> { captures.incrementAndGet(); return () -> {}; }));
        for (int i = 0; i < 22090; i++)
            assertFalse(gate.submit(UUID.randomUUID(), () -> { fail("capture before admission"); return () -> {}; }));
        assertEquals(1, captures.get());
        assertEquals(1, writes.size());
        assertEquals(CheckpointBackpressure.State.WRITING, gate.status().state());
        assertThrows(IllegalStateException.class, () -> gate.acknowledge(id));
        writes.remove().run();
        assertEquals(CheckpointBackpressure.State.WRITTEN, gate.status().state());
        assertThrows(IllegalStateException.class, () -> gate.acknowledge(UUID.randomUUID()));
        assertFalse(gate.submit(UUID.randomUUID(), () -> { fail("unconsumed ack"); return () -> {}; }));
        gate.acknowledge(id);
        assertEquals(CheckpointBackpressure.State.IDLE, gate.status().state());
        assertTrue(gate.submit(UUID.randomUUID(), () -> () -> {}));
    }

    @Test void writeFailureLatchesAndNeverAcknowledgesOrRecaptures() {
        var writes = new ArrayDeque<Runnable>();
        var gate = new CheckpointBackpressure(writes::add);
        var failure = new IOException("disk full");
        var id = UUID.randomUUID();
        gate.submit(id, () -> () -> { throw failure; });
        writes.remove().run();
        assertEquals(CheckpointBackpressure.State.FAILED, gate.status().state());
        assertSame(failure, gate.status().failure());
        assertThrows(IllegalStateException.class, () -> gate.acknowledge(id));
        assertFalse(gate.submit(UUID.randomUUID(), () -> { fail("failed write must block"); return () -> {}; }));
    }

    @Test void captureFailureAndRejectedWorkerRemainVisible() {
        var captureFailure = new IOException("serialization failed");
        var gate = new CheckpointBackpressure(task -> fail("must not submit"));
        gate.submit(UUID.randomUUID(), () -> { throw captureFailure; });
        assertSame(captureFailure, gate.status().failure());
        var rejected = new java.util.concurrent.RejectedExecutionException("stopping");
        var other = new CheckpointBackpressure(task -> { throw rejected; });
        other.submit(UUID.randomUUID(), () -> () -> {});
        assertSame(rejected, other.status().failure());
    }

    @Test void stopDuringWriteRetainsOutcomeAndCannotRestartAdmission() {
        var writes = new ArrayDeque<Runnable>();
        var gate = new CheckpointBackpressure(writes::add);
        var id = UUID.randomUUID();
        gate.submit(id, () -> () -> {});
        gate.stop();
        assertThrows(IllegalStateException.class, () -> gate.submit(UUID.randomUUID(), () -> () -> {}));
        assertEquals(CheckpointBackpressure.State.WRITING, gate.status().state());
        writes.remove().run();
        gate.acknowledge(id);
        assertThrows(IllegalStateException.class, () -> gate.submit(UUID.randomUUID(), () -> () -> {}));
    }

    @Test void workerCannotMutateOwnerState() throws Exception {
        var gate = new CheckpointBackpressure(Runnable::run);
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try { gate.submit(UUID.randomUUID(), () -> () -> {}); }
            catch (Throwable e) { error.set(e); }
        });
        thread.start(); thread.join(5000);
        assertFalse(thread.isAlive());
        assertInstanceOf(IllegalStateException.class, error.get());
        assertEquals(CheckpointBackpressure.State.IDLE, gate.status().state());
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    @Test void realWorkerKeepsCaptureOnOwnerAndStopDoesNotDiscardWrittenEvidence() throws Exception {
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var release = new java.util.concurrent.CountDownLatch(1);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var gate = new CheckpointBackpressure(executor);
        var owner = Thread.currentThread();
        var id = UUID.randomUUID();
        var file = directory.resolve("ownership-checkpoint");
        try {
            gate.submit(id, () -> {
                assertSame(owner, Thread.currentThread());
                // Immutable captured bytes only; no SavedData or Level crosses this boundary.
                var bytes = id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                return () -> {
                    assertNotSame(owner, Thread.currentThread());
                    entered.countDown();
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IOException("test worker timeout");
                    try (var out = java.nio.channels.FileChannel.open(file,
                            java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                        var buffer = java.nio.ByteBuffer.wrap(bytes);
                        while (buffer.hasRemaining()) out.write(buffer);
                        out.force(true);
                    }
                    try (var dir = java.nio.channels.FileChannel.open(directory, java.nio.file.StandardOpenOption.READ)) {
                        dir.force(true);
                    }
                };
            });
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(java.nio.file.Files.exists(file));
            assertThrows(IllegalStateException.class, () -> gate.acknowledge(id));
            gate.stop();
        } finally {
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(CheckpointBackpressure.State.WRITTEN, gate.status().state());
        assertEquals(id.toString(), java.nio.file.Files.readString(file));
        gate.acknowledge(id);
        assertTrue(java.nio.file.Files.exists(file));
        // An acknowledgement consumes metadata completion only; no terrain callback exists.
        assertThrows(IllegalStateException.class, () -> gate.submit(UUID.randomUUID(), () -> () -> {}));
    }

    @Test void failureAfterWritePreservesEvidenceWithoutSuccessfulAcknowledgement() throws Exception {
        var file = directory.resolve("unacknowledged");
        var gate = new CheckpointBackpressure(Runnable::run);
        var id = UUID.randomUUID();
        gate.submit(id, () -> () -> {
            java.nio.file.Files.writeString(file, id.toString());
            throw new IOException("injected directory sync failure");
        });
        assertEquals(CheckpointBackpressure.State.FAILED, gate.status().state());
        assertThrows(IllegalStateException.class, () -> gate.acknowledge(id));
        gate.stop();
        assertEquals(id.toString(), java.nio.file.Files.readString(file));
    }

}
