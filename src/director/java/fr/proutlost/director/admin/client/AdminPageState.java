package fr.proutlost.director.admin.client;

import fr.proutlost.director.api.model.*;
import java.util.Objects;
import java.util.Optional;

/** One bounded dashboard page. Called only on the client main thread.
 * Transport must authenticate the session envelope before delivering a DTO.
 * This class grants no server permission and performs no safety calculation.
 */
public final class AdminPageState {
    private SessionIdentity session;
    private AdminQuery pending;
    private AdminSnapshot snapshot;
    private boolean needsQuery = true;
    private fr.proutlost.director.api.error.ReasonCode failureReason;
    private long retryAfterTick;

    public void reset(SessionIdentity nextSession) {
        session = Objects.requireNonNull(nextSession);
        failureReason=null;retryAfterTick=0;
        pending = null;
        snapshot = null;
        needsQuery = true;
    }

    public void close() {
        session = null;
        failureReason=null;retryAfterTick=0;
        pending = null;
        snapshot = null;
        needsQuery = true;
    }

    /** A tab/filter/page change discards the old visible data immediately. */
    public void begin(AdminQuery query) {
        if (session == null) throw new IllegalStateException("No active session");
        pending = Objects.requireNonNull(query);
        failureReason=null;
        snapshot = null;
        needsQuery = false;
    }

    public boolean receive(SessionIdentity envelope, AdminSnapshot response) {
        Objects.requireNonNull(response);
        if (session == null || !session.equals(envelope) || pending == null
                || !pending.requestId().equals(response.requestId())) return false;
        if (response.rows().size() > pending.limit()
                || pending.expectedSnapshotRevision().filter(r -> !r.equals(response.snapshotRevision())).isPresent()
                || response.nextCursor().filter(c -> c.length() > 512).isPresent()) {
            invalidate();
            return false;
        }
        snapshot = response;
        pending = null;
        needsQuery = false;
        return true;
    }

    /** Reject only the outstanding request in the current connection. Retry is explicit:
     * repeated server refusals must not create an automatic refresh loop. */
    public boolean reject(SessionIdentity envelope, RequestId requestId, fr.proutlost.director.api.error.ReasonCode reason, long now, long delay) {
        if(session==null || !session.equals(envelope) || pending==null || !pending.requestId().equals(requestId)) return false;
        Objects.requireNonNull(reason);
        if(now<0||delay<1) throw new IllegalArgumentException("retry clock/bound");
        failureReason=reason;
        retryAfterTick=now>Long.MAX_VALUE-delay?Long.MAX_VALUE:now+delay;
        pending=null;
        snapshot=null;
        needsQuery=false;
        return true;
    }

    /** A delta invalidates the page, including its cursor, for a bounded requery.
     * G0 deltas have no removal keys; blindly merging them could retain stopped instances.
     */
    public void invalidate() {
        pending = null;
        snapshot = null;
        needsQuery = true;
    }

    public Optional<fr.proutlost.director.api.error.ReasonCode> failureReason() { return Optional.ofNullable(failureReason); }
    public boolean canRetry(long now) { return now>=retryAfterTick; }

    public Optional<AdminSnapshot> snapshot() { return Optional.ofNullable(snapshot); }
    public boolean pending() { return pending != null; }
    public boolean needsQuery() { return needsQuery; }

    /** Both ticks must be Director ticks; never subtract a Minecraft day-time value. */
    public Optional<Long> age(Tick currentDirectorTick) {
        Objects.requireNonNull(currentDirectorTick);
        if (snapshot == null || currentDirectorTick.compareTo(snapshot.capturedTick()) < 0) return Optional.empty();
        return Optional.of(currentDirectorTick.value() - snapshot.capturedTick().value());
    }
}
