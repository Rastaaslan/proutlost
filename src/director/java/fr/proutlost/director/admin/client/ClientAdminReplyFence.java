package fr.proutlost.director.admin.client;

import fr.proutlost.director.api.model.*;
import java.util.*;

/**
 * J07 real-client connection/request correlation.
 * AdminSnapshot and AdminCommandResult carry only a RequestId, not the J06
 * world/boot/connection envelope. Never accept a late response from another
 * connection, a closed F8 screen or a request that failed to send.
 *
 * The server remains the sole authority for permissions and WAL reads.
 */
public final class ClientAdminReplyFence {
    private enum Kind { QUERY, COMMAND }
    private final Thread actor=Thread.currentThread();
    private final int capacity;
    private SessionIdentity session;
    private final Map<RequestId,Kind> pending=new LinkedHashMap<>();

    public ClientAdminReplyFence(int capacity) {
        if(capacity<1 || capacity>256)throw new IllegalArgumentException("reply fence capacity");
        this.capacity=capacity;
    }
    private void actor() {
        if(Thread.currentThread()!=actor)throw new IllegalStateException("J07 client actor required");
    }
    public void reset(SessionIdentity negotiated) {
        actor();
        session=Objects.requireNonNull(negotiated);
        pending.clear();
    }
    public void revoke() {
        actor();session=null;pending.clear();
    }
    private void track(SessionId claimed,RequestId id,Kind kind) {
        actor();Objects.requireNonNull(claimed);Objects.requireNonNull(id);
        if(session==null || !DirectorAdminSessionKey.of(session).equals(claimed))
            throw new IllegalStateException("J07 request without current J06 identity");
        if(pending.containsKey(id) || pending.size()>=capacity)
            throw new IllegalStateException("J07 pending request capacity/duplicate");
        pending.put(id,kind);
    }
    public void track(AdminQuery query) {
        Objects.requireNonNull(query);
        track(query.actorSession(),query.requestId(),Kind.QUERY);
    }
    public void track(AdminCommand command) {
        Objects.requireNonNull(command);
        track(command.actorSession(),command.requestId(),Kind.COMMAND);
    }
    /** An uncertain/failed C2S dispatch is not a valid pending response. */
    public void cancel(RequestId id) {
        actor();pending.remove(Objects.requireNonNull(id));
    }
    public boolean accept(AdminSnapshot snapshot) {
        actor();Objects.requireNonNull(snapshot);
        if(session==null || pending.get(snapshot.requestId())!=Kind.QUERY)return false;
        pending.remove(snapshot.requestId());
        return true;
    }
    public boolean accept(AdminCommandResult reply) {
        actor();Objects.requireNonNull(reply);
        if(session==null || !pending.containsKey(reply.commandId()))return false;
        // A command or query may receive PENDING before a final result.
        // Keep provenance until the terminal result, never on an arbitrary TTL.
        if(reply.status()!=AdminStatus.PENDING)pending.remove(reply.commandId());
        return true;
    }
    public int pendingCount(){actor();return pending.size();}
}
