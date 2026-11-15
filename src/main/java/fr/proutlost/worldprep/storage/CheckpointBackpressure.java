package fr.proutlost.worldprep.storage;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Single-owner checkpoint admission, before snapshot serialization. WorldPrep SavedData
 * supplies its writer through AcknowledgedSavedData; other callers must verify theirs.
 * Capture runs on the owner thread; the returned write must contain immutable data only.
 * One captured write remains admitted until its exact acknowledgement is consumed.
 * A completed write does NOT acknowledge terrain persistence.
 */
public final class CheckpointBackpressure {
    @FunctionalInterface public interface Capture { Write capture() throws Exception; }
    @FunctionalInterface public interface Write { void persist() throws Exception; }
    public enum State { IDLE, WRITING, WRITTEN, FAILED }
    public record Status(UUID id, State state, Throwable failure) {}
    private final Thread owner = Thread.currentThread();
    private final Executor executor;
    private UUID id;
    private CompletableFuture<Void> pending;
    private boolean closed;

    public CheckpointBackpressure(Executor executor) { this.executor = Objects.requireNonNull(executor); }

    /** false means backpressure; capture has not been called. Failure is latched. */
    public boolean submit(UUID checkpoint, Capture capture) {
        requireOwner();
        Objects.requireNonNull(checkpoint);
        Objects.requireNonNull(capture);
        if (closed) throw new IllegalStateException("Checkpoint admission closed");
        if (pending != null) return false;
        id = checkpoint;
        pending = new CompletableFuture<>();
        var completion = pending;
        try {
            Write write = Objects.requireNonNull(capture.capture());
            executor.execute(() -> {
                try { write.persist(); completion.complete(null); }
                catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                    if (failure instanceof Error error) throw error;
                }
            });
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
            if (failure instanceof Error error) throw error;
        }
        return true;
    }

    public Status status() {
        requireOwner();
        if (pending == null) return new Status(null, State.IDLE, null);
        if (!pending.isDone()) return new Status(id, State.WRITING, null);
        try { pending.join(); return new Status(id, State.WRITTEN, null); }
        catch (java.util.concurrent.CompletionException failure) {
            return new Status(id, State.FAILED, failure.getCause());
        }
    }

    /** Failure cannot be cleared or treated as a successful acknowledgement. */
    public void acknowledge(UUID checkpoint) {
        requireOwner();
        var status = status();
        if (!Objects.equals(id, checkpoint) || status.state() != State.WRITTEN)
            throw new IllegalStateException("Exact successful checkpoint required", status.failure());
        pending = null;
        id = null;
    }

    /** Final native save boundary only; ordinary ticks must use nonblocking status(). */
    public void awaitWrite(long timeoutMillis) {
        requireOwner();
        if (pending == null) return;
        try { pending.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Checkpoint flush interrupted; write not cancelled", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("Checkpoint flush failed; write not acknowledged", e);
        }
    }

    /** Stop admission without cancelling a possibly committed write or losing its result. */
    public void stop() { requireOwner(); closed = true; }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Checkpoint owner thread required");
    }
}
