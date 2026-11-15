package fr.proutlost.director.integration.ai;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Bounded asynchronous advisor. The supplied scheduler is an isolated single-thread executor.
 * Consumers must still revalidate live admission; an accepted proposal grants no world authority.
 */
public final class OptionalAiAdvisor implements AiAdvisorPort, AutoCloseable {
    public record Policy(long deadlineNanos, long minimumGapTicks, int failureThreshold, long resetNanos, long deduplicationWindowTicks, int maximumDeduplicationKeys) {
        public Policy {
            if (deadlineNanos <= 0 || minimumGapTicks < 0 || failureThreshold < 1 || resetNanos <= 0 || deduplicationWindowTicks <= minimumGapTicks || maximumDeduplicationKeys < 1)
                throw new IllegalArgumentException("AI policy");
        }
    }
    private final AiTransport transport;
    private final ScheduledExecutorService executor;
    private final LongSupplier nanoTime, simTick;
    private final Supplier<Fingerprint> currentContext;
    private final Policy policy;
    private final ResourceId adapter;
    private final String modelVersion;
    private final AtomicBoolean occupied = new AtomicBoolean(), closed = new AtomicBoolean();
    // Result timeout is not transport termination. Unknown transports retain their slot until
    // their original completion signal; cancelling an arbitrary future would not prove cleanup.
    private final AtomicBoolean transportBusy = new AtomicBoolean();
    private final AtomicReference<TransportOperation> cancellable = new AtomicReference<>();
    private record ContextKey(Fingerprint context, Fingerprint candidates) {}
    private final Map<ContextKey, Long> dispatched = new HashMap<>();
    private final AtomicReference<CompletableFuture<AiProposalResult>> active = new AtomicReference<>();
    // Accessed only by the isolated executor.
    private long lastTick, openedAt;
    private boolean called, open;
    private int failures;

    public OptionalAiAdvisor(AiTransport transport, ScheduledExecutorService executor,
            LongSupplier nanoTime, LongSupplier simTick, Supplier<Fingerprint> currentContext,
            Policy policy, ResourceId adapter, String modelVersion) {
        this.transport = Objects.requireNonNull(transport); this.executor = Objects.requireNonNull(executor);
        this.nanoTime = Objects.requireNonNull(nanoTime); this.simTick = Objects.requireNonNull(simTick);
        this.currentContext = Objects.requireNonNull(currentContext); this.policy = Objects.requireNonNull(policy);
        this.adapter = Objects.requireNonNull(adapter);
        if (!modelVersion.matches("[a-zA-Z0-9._-]{1,80}")) throw new IllegalArgumentException("model version");
        this.modelVersion = modelVersion;
    }
    public CompletionStage<AiProposalResult> propose(AiInput input) {
        Objects.requireNonNull(input);
        if (closed.get() || transportBusy.get() || !occupied.compareAndSet(false, true)) return immediate(ReasonCode.RATE_LIMITED);
        var result = new CompletableFuture<AiProposalResult>(); active.set(result);
        var delivered = result.whenComplete((v, e) -> {
            var operation = cancellable.get();
            if (operation != null) operation.requestCancellation();
            active.compareAndSet(result, null); occupied.set(false);
        });
        try { executor.execute(() -> start(input, result)); }
        catch (RejectedExecutionException e) { result.complete(DisabledAiAdvisor.fallback(ReasonCode.CAPABILITY_MISSING)); }
        return delivered.minimalCompletionStage();
    }
    private CompletionStage<AiProposalResult> immediate(ReasonCode code) {
        return CompletableFuture.completedFuture(DisabledAiAdvisor.fallback(code));
    }
    private void start(AiInput input, CompletableFuture<AiProposalResult> result) {
        if (result.isDone() || closed.get()) { result.complete(DisabledAiAdvisor.fallback(ReasonCode.CAPABILITY_MISSING)); return; }
        long now = nanoTime.getAsLong(), tick = simTick.getAsLong();
        if (now - input.expiresMonotonicDeadline() >= 0) { result.complete(DisabledAiAdvisor.fallback(ReasonCode.AI_TIMEOUT)); return; }
        if ((open && now - openedAt < policy.resetNanos()) ||
                (called && (tick < lastTick || tick - lastTick < policy.minimumGapTicks()))) {
            result.complete(DisabledAiAdvisor.fallback(ReasonCode.RATE_LIMITED)); return;
        }
        // Rolling per-key window starts at dispatch, not at a rejected duplicate. Never evict
        // an unexpired key to make room: saturation falls back instead of permitting replay.
        dispatched.entrySet().removeIf(e -> tick >= e.getValue() && tick - e.getValue() >= policy.deduplicationWindowTicks());
        var key = new ContextKey(input.contextHash(), input.candidateSetHash());
        if (dispatched.containsKey(key) || dispatched.size() >= policy.maximumDeduplicationKeys()) {
            result.complete(DisabledAiAdvisor.fallback(ReasonCode.RATE_LIMITED)); return;
        }
        called = true; lastTick = tick;
        dispatched.put(key, tick);
        long delay = Math.min(policy.deadlineNanos(), input.expiresMonotonicDeadline() - now);
        long deadline = now + delay;
        final ScheduledFuture<?> timeout;
        try { timeout = executor.schedule(() -> fail(result, ReasonCode.AI_TIMEOUT), delay, TimeUnit.NANOSECONDS); }
        catch (RejectedExecutionException e) { result.complete(DisabledAiAdvisor.fallback(ReasonCode.CAPABILITY_MISSING)); return; }
        result.whenComplete((v,e) -> timeout.cancel(false));
        try {
            transportBusy.set(true);
            var operation = Objects.requireNonNull(transport.propose(new AiTransportRequest(input, adapter)));
            if (operation instanceof TransportOperation cancel) {
                cancellable.set(cancel);
                if (result.isDone()) cancel.requestCancellation();
            }
            operation.whenComplete((response, error) -> {
                if (operation instanceof TransportOperation cancel) cancellable.compareAndSet(cancel, null);
                transportBusy.set(false);
                try { executor.execute(() -> accept(input, result, deadline, response, error)); }
                catch (RejectedExecutionException e) { result.complete(DisabledAiAdvisor.fallback(ReasonCode.CAPABILITY_MISSING)); }
            });
        } catch (RuntimeException e) { transportBusy.set(false); fail(result, ReasonCode.AI_INVALID); }
    }
    private void accept(AiInput input, CompletableFuture<AiProposalResult> result, long deadline,
            AiTransportResponse response, Throwable error) {
        if (result.isDone()) return; // Token is permanently invalid after timeout/close.
        long now = nanoTime.getAsLong();
        if (closed.get() || now - deadline >= 0) { fail(result, ReasonCode.AI_TIMEOUT); return; }
        if (error != null || response == null) { fail(result, ReasonCode.AI_INVALID); return; }
        try {
            if (response.receivedMonotonicNanos() - deadline >= 0) throw new IllegalArgumentException("late response");
            var proposal = AiProposalContract.decode(response.body(), input, now, true, currentContext.get());
            var hash = BlobContent.hash(new ModelCodec<>(AiProposal.class).encode(proposal));
            now = nanoTime.getAsLong();
            if (now - deadline >= 0 || now - input.expiresMonotonicDeadline() >= 0) { fail(result, ReasonCode.AI_TIMEOUT); return; }
            if (!input.contextHash().equals(currentContext.get())) { fail(result, ReasonCode.AI_INVALID); return; }
            var accepted = new AiAcceptedProposal(proposal, hash, input.catalogHash(), input.contextHash(),
                    input.decisionId(), adapter, modelVersion, now);
            failures = 0; open = false;
            result.complete(new AiProposalResult(AiStatus.VALID_PROPOSAL, Optional.of(accepted), Optional.empty()));
        } catch (RuntimeException e) { fail(result, ReasonCode.AI_INVALID); }
    }
    private void fail(CompletableFuture<AiProposalResult> result, ReasonCode code) {
        if (result.isDone()) return;
        if (++failures >= policy.failureThreshold()) { open = true; openedAt = nanoTime.getAsLong(); }
        result.complete(DisabledAiAdvisor.fallback(code));
    }
    public void close() {
        closed.set(true);
        var result = active.get();
        if (result != null) result.complete(DisabledAiAdvisor.fallback(ReasonCode.CAPABILITY_MISSING));
    }
}
