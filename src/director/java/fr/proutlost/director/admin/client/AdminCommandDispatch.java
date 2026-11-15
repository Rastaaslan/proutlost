package fr.proutlost.director.admin.client;

import fr.proutlost.director.api.model.AdminCommand;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * J07 modifying-command transport fence. Unlike a read, a failed send may
 * already have reached the server. Keep the exact command and request ID in
 * PENDING until an authoritative result is received; never fabricate a
 * REJECTED/FAILED_SAFE receipt and never automatically generate a new ID.
 *
 * A user can resolve uncertain delivery against the durable J04 audit before
 * explicitly deciding whether to retry the identical command.
 */
public final class AdminCommandDispatch {
    private AdminCommandDispatch() {}

    public enum Outcome {
        NOT_ACCEPTED,
        SENT_UNCONFIRMED,
        DELIVERY_UNCERTAIN,
        READ_DELIVERY_FAILED
    }

    public static Outcome send(AdminCommandState state, AdminCommand command,
                               Consumer<AdminCommand> sender) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(command);
        Objects.requireNonNull(sender);
        if (!state.begin(command)) return Outcome.NOT_ACCEPTED;
        try {
            sender.accept(command);
            return Outcome.SENT_UNCONFIRMED;
        } catch (RuntimeException transportFailure) {
            // Do not leak transport details into chat/UI and DO NOT convert
            // an uncertain world write into an authoritative terminal result.
            return Outcome.DELIVERY_UNCERTAIN;
        }
    }

    /**
     * A failed inspection transport must not permanently consume one of F8's
     * bounded result slots. Unlike a potentially effective world write, the
     * six qualified inspection commands have no effects and may be explicitly
     * retried with a fresh request ID. We do NOT fabricate a server receipt.
     */
    public static Outcome sendReadOnly(AdminCommandState state, AdminCommand command,
                                       Consumer<AdminCommand> sender) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(command);
        Objects.requireNonNull(sender);
        if (!AdminClientReadPayloads.readOnly(command.operation()))
            throw new IllegalArgumentException("Unqualified F8 read");
        if (!state.begin(command)) return Outcome.NOT_ACCEPTED;
        try {
            sender.accept(command);
            return Outcome.SENT_UNCONFIRMED;
        } catch (RuntimeException transportFailure) {
            if (!state.abandonFailedRead(command.requestId()))
                throw new IllegalStateException("Failed to release read-only command");
            return Outcome.READ_DELIVERY_FAILED;
        }
    }
}
