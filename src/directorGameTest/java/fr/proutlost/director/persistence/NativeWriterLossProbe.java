package fr.proutlost.director.persistence;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.api.port.DurableStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Native GameTest source-set fixture ONLY; never install in the production store bootstrap.
 * Uses a real private JournalStore, normal blob forcing/WAL/fsync and its existing IoProbe.
 * No ACK, RESULT, ownership, physical effect or recovery evidence is manufactured here.
 *
 * Use THIS DurableStore for both CommitPermit.Authority and EventRunner. Never retain/expose
 * the underlying JournalStore. Every append must pass through this fixture; concurrent appends
 * are rejected as a fixture misuse, rather than injecting failure into an unrelated transaction.
 *
 * In EventRunner's real result builder, first build the exact batch from the actual SliceResult,
 * then return probe.armResult(batch, point). EventRunner appends that identical batch next.
 * Arming is permitted only after this fixture observed the corresponding real durable PREPARE.
 *
 * This proves a provider/EventRunner/J04 failure boundary. Existing full-disaster coordinator
 * constructors require JournalStore, so this wrapper alone does NOT qualify their real WARN,
 * affected-client receipts, human input, or all five complete catastrophe paths.
 */
public final class NativeWriterLossProbe implements DurableStore {
    public enum FailurePoint {
        BEFORE_RESULT_WAL_WRITE("BEFORE_WAL_WRITE"),
        AFTER_RESULT_FORCE_BEFORE_ACK("BEFORE_ACK");
        private final String ioPoint;
        FailurePoint(String ioPoint) { this.ioPoint = ioPoint; }
    }

    public record Injection(TransactionId resultTransaction, TransactionId prepareTransaction,
            OperationToken operation, FailurePoint point, StoreSession sessionBefore) {}

    private record Armed(DurableBatch batch, ResultRecord result,
            FailurePoint point, StoreSession sessionBefore) {}

    /** Only the real JournalStore writer invokes this probe; no world access occurs here. */
    private static final class Control implements JournalStore.IoProbe {
        final Map<TransactionId, OperationToken> durablePrepares = new HashMap<>();
        TransactionId inFlight;
        Armed armed;
        boolean targetActive;
        Injection injection;

        @Override public synchronized void at(String point) throws IOException {
            if (!targetActive || armed == null || injection != null ||
                    !point.equals(armed.point().ioPoint)) return;
            if (!armed.batch().transactionId().equals(inFlight))
                throw new IllegalStateException("private writer fixture transaction drift");
            injection = new Injection(armed.batch().transactionId(),
                    armed.result().prepareTransactionId(), armed.result().operationToken(),
                    armed.point(), armed.sessionBefore());
            System.out.println("DIRECTOR_NATIVE_PRIVATE_WRITER_LOSS_INJECTED result_tx=" +
                    injection.resultTransaction().value() + " prepare_tx=" +
                    injection.prepareTransaction().value() + " operation=" +
                    injection.operation().value() + " point=" + point +
                    " full_disaster=false client=false g5=false");
            throw new IOException("TEST_ONLY private writer loss at " + point +
                    " result_tx=" + injection.resultTransaction().value());
        }
    }

    private final JournalStore journal;
    private final Control control;

    private NativeWriterLossProbe(JournalStore journal, Control control) {
        this.journal = Objects.requireNonNull(journal);
        this.control = Objects.requireNonNull(control);
    }

    /**
     * Reopen this SAME private directory/world after close for recovery.
     * The directory is deliberately separate from the lifecycle's main Director store.
     * Caller owns Minecraft save/halt and stopped NBT verification; this class does neither.
     */
    public static CompletionStage<NativeWriterLossProbe> openPrivate(
            Path directory, WorldId world, StoreOptions options) {
        if (!Boolean.getBoolean("proutlost.director.nativeGameTests") ||
                !Boolean.getBoolean("proutlost.director.nativeLab"))
            throw new IllegalStateException("explicit native GameTest LAB required");
        Objects.requireNonNull(directory); Objects.requireNonNull(world);
        Objects.requireNonNull(options);
        Path normalized = directory.toAbsolutePath().normalize();
        if (normalized.getFileName() == null ||
                !normalized.getFileName().toString()
                        .matches("director-native-writer-loss-[a-z0-9-]+"))
            throw new IllegalArgumentException("explicit private writer-loss directory required");
        Control control = new Control();
        return JournalStore.open(normalized, world, options, control)
                .thenApply(store -> new NativeWriterLossProbe(store, control));
    }

    /** Return the ORIGINAL batch unchanged, from the real EventRunner result-builder callback. */
    public DurableBatch armResult(DurableBatch batch, FailurePoint point) {
        Objects.requireNonNull(batch); Objects.requireNonNull(point);
        synchronized (control) {
            if (control.inFlight != null || control.armed != null || control.injection != null)
                throw new IllegalStateException("private fixture must be idle and armed only once");
            if (journal.readOnlySafe() || batch.permitAttempt().isPresent() ||
                    !batch.expectedStoreGeneration().equals(journal.session().storeGeneration()))
                throw new IllegalArgumentException("live same-generation RESULT required");
            var rows = batch.records().stream().filter(DurableResult.class::isInstance)
                    .map(DurableResult.class::cast).toList();
            if (rows.size() != 1 ||
                    batch.records().stream().anyMatch(DurablePrepare.class::isInstance))
                throw new IllegalArgumentException("one actual RESULT, no new PREPARE");
            ResultRecord result = rows.getFirst().value();
            if (batch.transactionId().equals(result.prepareTransactionId()) ||
                    !result.operationToken().equals(
                            control.durablePrepares.get(result.prepareTransactionId())))
                throw new IllegalArgumentException("original durable PREPARE binding required");
            if (result.result().outcome() != Outcome.SUCCEEDED ||
                    !result.result().appliedOperationIds().equals(Set.of(result.operationToken())))
                throw new IllegalArgumentException("actual successful single physical operation required");
            control.armed = new Armed(batch, result, point, journal.session());
            return batch;
        }
    }

    @Override public CompletionStage<CommitAck> append(DurableBatch batch) {
        Objects.requireNonNull(batch);
        synchronized (control) {
            if (control.inFlight != null)
                return CompletableFuture.failedFuture(
                        new IllegalStateException("private fixture forbids concurrent append"));
            if (control.armed != null && control.injection == null &&
                    control.armed.batch() != batch)
                return CompletableFuture.failedFuture(
                        new IllegalStateException("armed RESULT must be the identical next batch"));
            control.inFlight = batch.transactionId();
            control.targetActive = control.armed != null && control.injection == null &&
                    control.armed.batch() == batch;
        }
        try {
            return journal.append(batch).whenComplete((ack, failure) -> {
                synchronized (control) {
                    if (failure == null && ack != null && ack.ackStatus() == AckStatus.DURABLE &&
                            ack.transactionId().equals(batch.transactionId()) &&
                            ack.transactionHash().equals(batch.transactionHash()) &&
                            ack.storeGeneration().equals(batch.expectedStoreGeneration()) &&
                            ack.durableSequence().value() > 0) {
                        for (var row : batch.records()) if (row instanceof DurablePrepare d &&
                                ack.forcedBlobs().containsAll(
                                        CommitPermit.Authority.references(d.value().durableBlobs())))
                            control.durablePrepares.put(d.value().transactionId(),
                                    d.value().operationToken());
                    }
                    control.targetActive = false;
                    control.inFlight = null;
                }
            });
        } catch (RuntimeException failure) {
            synchronized (control) {
                control.targetActive = false;
                control.inFlight = null;
            }
            throw failure;
        }
    }

    @Override public StoreSession session() { return journal.session(); }
    @Override public StoreReadResult view(StoreQuery query) { return journal.view(query); }
    @Override public CompletionStage<StoreReadResult> read(StoreQuery query) {
        return journal.read(query);
    }
    @Override public CompletionStage<BlobReadResult> readBlobs(BlobReadRequest request) {
        return journal.readBlobs(request);
    }
    public boolean readOnlySafe() { return journal.readOnlySafe(); }
    public String diagnostic() { return journal.diagnostic(); }
    public Optional<Injection> injection() {
        synchronized (control) { return Optional.ofNullable(control.injection); }
    }
    public boolean authoritySessionRevoked() {
        synchronized (control) {
            return control.injection != null &&
                    !journal.session().equals(control.injection.sessionBefore());
        }
    }
    /** Real asynchronous drain/release; graceful close itself is not a writer-loss injection. */
    public CompletionStage<Void> closeAsync() {
        synchronized (control) {
            if (control.inFlight != null)
                return CompletableFuture.failedFuture(
                        new IllegalStateException("wait for append completion before private close"));
        }
        return journal.closeAsync();
    }
}
