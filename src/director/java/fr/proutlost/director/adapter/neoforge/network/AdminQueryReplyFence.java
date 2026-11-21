package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Final fail-closed boundary for J07's native read-only C2S query.
 * A broken J04 projection, wrong request ID, or oversized result must never
 * unwind the Minecraft server actor or accidentally grant mutation rights.
 *
 * Authentication, operator permission 4, and query rate limits are checked
 * by the NeoForge caller BEFORE this boundary. This is not an auth substitute.
 */
public final class AdminQueryReplyFence {
    private static final int MAX_READ_WIRE_BYTES = 22000;

    private AdminQueryReplyFence() {}

    public record Reply(Optional<AdminSnapshot> snapshot,
                        Optional<AdminCommandResult> rejection) {
        public Reply {
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(rejection);
            if (snapshot.isPresent() == rejection.isPresent())
                throw new IllegalArgumentException("exactly one J07 reply");
        }
    }

    private static Reply deny(AdminQuery request, AdminStatus status,
                              ReasonCode reason) {
        return new Reply(Optional.empty(), Optional.of(
            new AdminCommandResult(request.requestId(), status,
                List.of(reason), Optional.empty(), Optional.empty())));
    }

    /**
     * The same AdminCommandResult DTO also transports J07 read rejections.
     * FAILED_SAFE and DEFERRED must release a pending F8 query just like
     * REJECTED; otherwise the visible five-tab panel hangs indefinitely.
     * Pending/successful command results are not read errors.
     */
    public static Optional<ReasonCode> readFailure(AdminCommandResult result) {
        Objects.requireNonNull(result);
        return switch (result.status()) {
            case REJECTED, DEFERRED, FAILED_SAFE ->
                // A malformed refusal with no reasons must still complete
                // the correlated pending F8 read instead of hanging forever.
                Optional.of(result.reasonCodes().stream().findFirst()
                    .orElse(ReasonCode.INTERNAL_FAULT));
            default -> Optional.empty();
        };
    }

    public static Reply read(AdminQuery request,
                             Supplier<Optional<AdminSnapshot>> projection) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(projection);
        // Global tag discovery is now served by the pinned J04 census reader;
        // projection unavailability still refuses rather than claiming absence.
        try {
            var maybe = Objects.requireNonNull(projection.get(), "null projection");
            if (maybe.isEmpty())
                return deny(request, AdminStatus.REJECTED, ReasonCode.STALE_CONTEXT);
            var page = Objects.requireNonNull(maybe.orElseThrow());
            // The native J07 bridge is read-only even when the underlying
            // generic AdminSnapshot API supports administrative capabilities.
            if (!page.requestId().equals(request.requestId())
                    || !fr.proutlost.director.admin.server.NativeReadCommandFence.supported().containsAll(page.capabilities())
                    || page.rows().size() > request.limit()
                    || page.nextCursor().filter(s -> s.length() > 512).isPresent())
                return deny(request, AdminStatus.FAILED_SAFE, ReasonCode.INTERNAL_FAULT);
            if (request.expectedSnapshotRevision()
                    .filter(revision -> !revision.equals(page.snapshotRevision()))
                    .isPresent())
                return deny(request, AdminStatus.REJECTED, ReasonCode.STALE_CONTEXT);
            if (new ModelCodec<>(AdminSnapshot.class).encode(page).length
                    > MAX_READ_WIRE_BYTES)
                return deny(request, AdminStatus.FAILED_SAFE, ReasonCode.INTERNAL_FAULT);
            return new Reply(Optional.of(page), Optional.empty());
        } catch (RuntimeException corruptProjection) {
            // Do not echo corrupt/stale WAL details or exception messages over
            // the network. One bounded error is correlated to the query ID.
            return deny(request, AdminStatus.FAILED_SAFE, ReasonCode.INTERNAL_FAULT);
        }
    }
}
