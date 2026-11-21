package fr.proutlost.director.admin.client;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Bounded display state, not the server's idempotency ledger. Client-main-thread only. */
public final class AdminCommandState {
    private final int capacity;
    private SessionId session;
    private final Map<RequestId, AdminCommand> commands = new LinkedHashMap<>();
    private final Map<RequestId, AdminCommandResult> results = new LinkedHashMap<>();

    public AdminCommandState(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Positive capacity required");
        this.capacity = capacity;
    }

    public void reset(SessionId nextSession) {
        session = Objects.requireNonNull(nextSession);
        commands.clear();
        results.clear();
    }

    public void close() {
        session = null;
        commands.clear();
        results.clear();
    }

    /** Called before transmission. Retries preserve the exact command and request ID. */
    public boolean begin(AdminCommand command) {
        Objects.requireNonNull(command);
        if (!command.actorSession().equals(session)) return false;
        var previous = commands.get(command.requestId());
        if (previous != null) return previous.equals(command)&&!staleRefusal(results.get(command.requestId()));
        if (commands.size() >= capacity) return false;
        commands.put(command.requestId(), command);
        results.put(command.requestId(), new AdminCommandResult(command.requestId(), AdminStatus.PENDING,
                List.of(), Optional.empty(), Optional.empty()));
        return true;
    }

    /**
     * Local cancellation is legal ONLY for non-mutating inspection commands
     * whose transport failed before any authoritative result arrived.
     * The server may still have processed the read; a late reply is ignored.
     * This method cannot discard a potentially effective world write.
     */
    public boolean abandonFailedRead(RequestId requestId) {
        Objects.requireNonNull(requestId);
        var command=commands.get(requestId);
        var result=results.get(requestId);
        if(command==null || result==null
                || !AdminClientReadPayloads.readOnly(command.operation())
                || result.status()!=AdminStatus.PENDING) return false;
        commands.remove(requestId);
        results.remove(requestId);
        return true;
    }

    /** A transport ACK never calls this method: only an authoritative command result does. */
    public boolean receive(SessionId envelope, AdminCommandResult result) {
        Objects.requireNonNull(result);
        if (session == null || !session.equals(envelope)) return false;
        var previous = results.get(result.commandId());
        if (previous == null) return false;
        if (terminal(previous,commands.get(result.commandId()))) return previous.equals(result);
        if (previous.status() == AdminStatus.DEFERRED && result.status() == AdminStatus.PENDING) return false;
        results.put(result.commandId(), result);
        return true;
    }

    /** Explicit dismissal cannot discard an operation that may still complete. */
    public boolean dismiss(RequestId requestId) {
        var result = results.get(requestId);
        if (result == null || !terminal(result,commands.get(requestId))) return false;
        results.remove(requestId);
        commands.remove(requestId);
        return true;
    }

    public Optional<AdminCommandResult> result(RequestId requestId) { return Optional.ofNullable(results.get(requestId)); }

    public List<RequestId> requestIds() { return List.copyOf(commands.keySet()); }

    /** Navigation is bound to the original command, never the currently visible filter.
     * G0 ScopeRef has no DECISION or FIXTURE kind: replay/simulation use their typed
     * command selectors while retaining the returned scope reference verbatim. */
    public Optional<AdminQuery> resultQuery(RequestId requestId, RequestId queryId, int limit) {
        var command=commands.get(requestId);
        var result=results.get(requestId);
        // A terminal refusal is not a successful inspection. In particular,
        // REPLAY/SIMULATE/CHAIN carry a target in the original payload: using
        // that target after REJECTED/FAILED_SAFE would misleadingly expose a
        // navigable result even though J04 never authorized the operation.
        if(command==null || result==null || result.status()!=AdminStatus.SUCCEEDED
                &&!fr.proutlost.director.admin.server.PagedMemoryInspection.matches(command,result))
            return Optional.empty();
        AdminPanel panel;
        AdminFilter filter;
        if(command.payload() instanceof ReplayPayload replay) {
            panel=AdminPanel.DECISIONS;filter=new DecisionFilter(Optional.of(replay.decisionId()));
        } else if(command.payload() instanceof SimulationPayload simulation) {
            panel=AdminPanel.LABORATORY;filter=new LaboratoryFilter(Optional.of(simulation.fixtureId()));
        } else if(command.payload() instanceof MutationInspectionPayload mutation) {
            panel=AdminPanel.ACTIVE;filter=new MutationFilter(mutation.mutationId());
        } else if(command.payload() instanceof ChainInspectionPayload chain) {
            panel=AdminPanel.ACTIVE;filter=new ChainFilter(Optional.of(chain.chainId()));
        } else if(result.resultRef().isPresent()) {
            var ref=result.resultRef().get();
            switch(ref.kind()) {
                case WORLD -> {panel=AdminPanel.WORLD;filter=new WorldFilter();}
                case REGION -> {panel=AdminPanel.MEMORY;filter=new RegionFilter(Optional.of(new RegionId(ref.id())),Set.of());}
                case PLAYER -> {panel=AdminPanel.MEMORY;filter=new PlayerFilter(new PlayerId(ref.id()));}
                case EVENT -> {panel=AdminPanel.ACTIVE;filter=new EventFilter(Optional.of(new EventInstanceId(ref.id())),Set.of());}
                default -> {return Optional.empty();} // G0 has no location query filter.
            }
        } else return Optional.empty();
        return Optional.of(new AdminQuery(command.actorSession(),queryId,panel,filter,Optional.empty(),Optional.empty(),limit));
    }

    /** A server CAS refusal cannot complete later with its immutable old revision.
     * Other DEFERRED receipts, including uncertain delivery, retain the exact request. */
    private static boolean staleRefusal(AdminCommandResult result) {
        return result!=null&&result.status()==AdminStatus.DEFERRED&&
            result.reasonCodes().contains(fr.proutlost.director.api.error.ReasonCode.STALE_CONTEXT);
    }
    private static boolean terminal(AdminCommandResult result,AdminCommand command) {
        var status=result.status();
        return status==AdminStatus.SUCCEEDED||status==AdminStatus.REJECTED||
            status==AdminStatus.FAILED_SAFE||staleRefusal(result)||fr.proutlost.director.admin.server.PagedMemoryInspection.matches(command,result);
    }
}
