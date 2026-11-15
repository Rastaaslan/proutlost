package fr.proutlost.director.admin.client;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * J07 physical-client read dispatch fence. A failed C2S send must not leave
 * the five-tab F8 screen indefinitely PENDING. This is safe for a read-only
 * query: no world write is inferred and no automatic retry is performed.
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
