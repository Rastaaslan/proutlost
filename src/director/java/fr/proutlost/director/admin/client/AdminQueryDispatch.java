package fr.proutlost.director.admin.client;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * J07 physical-client read dispatch fence. A failed C2S send must not leave
 * the five-tab F8 screen indefinitely PENDING. This is safe for a read-only
 * query: no world write is inferred. Only a separately marked, authoritatively
 * RATE_LIMITED fragment continuation has a bounded retry policy.
 * A modifying AdminCommand has different uncertain-delivery semantics.
 */
public final class AdminQueryDispatch {
    private AdminQueryDispatch() {}

    public static boolean send(AdminPageState page, SessionIdentity session,
                               AdminQuery query, Consumer<AdminQuery> sender,
                               long tick, long retryTicks) {
        Objects.requireNonNull(page);
        Objects.requireNonNull(session);
        Objects.requireNonNull(query);
        Objects.requireNonNull(sender);
        if (tick < 0 || retryTicks < 1)
            throw new IllegalArgumentException("retry clock/bound");
        page.begin(query);
        return deliver(page,session,query,sender,tick,retryTicks);
    }

    public static boolean sendContinuation(AdminPageState page, SessionIdentity session,
                               AdminQuery query, Consumer<AdminQuery> sender,
                               long tick, long retryTicks) {
        Objects.requireNonNull(page);Objects.requireNonNull(session);Objects.requireNonNull(query);Objects.requireNonNull(sender);
        if(tick<0||retryTicks<1)throw new IllegalArgumentException("retry clock/bound");
        if(!page.canRetry(tick)||!page.beginContinuation(query))return false;
        return deliver(page,session,query,sender,tick,retryTicks);
    }

    /** Separately marked loading-only directory continuation; same transport
     * failure fence and existing retry clocks as fragmented documents. */
    public static boolean sendDirectoryContinuation(AdminPageState page,SessionIdentity session,
            AdminQuery query,Consumer<AdminQuery> sender,long tick,long retryTicks){
        Objects.requireNonNull(page);Objects.requireNonNull(session);Objects.requireNonNull(query);Objects.requireNonNull(sender);
        if(tick<0||retryTicks<1)throw new IllegalArgumentException("retry clock/bound");
        // A form edit can occur while the first empty directory page waits
        // for automatic next. Cancel locally before constructing a mixed
        // filter/cursor dispatch; the exact page-state tuple guard stays strict.
        if(!page.matchesDocumentContext(query.panel(),query.filter())){
            page.cancelDocumentContinuation();return false;
        }
        if(!page.canRetry(tick)||!page.beginDirectoryContinuation(query))return false;
        return deliver(page,session,query,sender,tick,retryTicks);
    }

    private static boolean deliver(AdminPageState page,SessionIdentity session,AdminQuery query,Consumer<AdminQuery> sender,long tick,long retryTicks) {
        try {
            sender.accept(query);
            return true;
        } catch (RuntimeException transportFailure) {
            // The client never displays exception text or guesses a server
            // outcome. A new read is explicitly requested after the fence.
            page.reject(session, query.requestId(), ReasonCode.INTERNAL_FAULT,
                tick, retryTicks);
            return false;
        }
    }
}
