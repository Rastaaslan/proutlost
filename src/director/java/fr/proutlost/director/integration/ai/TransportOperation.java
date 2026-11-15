package fr.proutlost.director.integration.ai;

import fr.proutlost.director.api.model.AiTransportResponse;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local transport lifecycle, not a frozen port change. Cancellation requests cleanup; only
 * the underlying exchange's terminal callback completes this stage and releases admission.
 */
final class TransportOperation extends CompletableFuture<AiTransportResponse> {
    private final Runnable cancelExchange;
    private final AtomicBoolean requested = new AtomicBoolean();
    TransportOperation(Runnable cancelExchange) { this.cancelExchange = Objects.requireNonNull(cancelExchange); }
    void requestCancellation() {
        if (!isDone() && requested.compareAndSet(false, true)) cancelExchange.run();
    }
    @Override public boolean cancel(boolean mayInterruptIfRunning) {
        requestCancellation();
        return isCancelled();
    }
}
