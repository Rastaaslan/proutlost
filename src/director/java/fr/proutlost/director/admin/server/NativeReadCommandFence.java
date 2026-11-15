package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.util.*;

/**
 * J07 native F8 read-command adapter. No gameplay mutation, synthetic data,
 * admission, runtime permit, or WAL writer is reachable from this class.
 *
 * Only the authenticated server actor is allowed to invoke the resulting J04
 * query. This mapper is intentionally NOT a permission source: the wire
 * boundary must independently authenticate the live player, session and OP4.
 */
public final class NativeReadCommandFence {
    private static final Set<AdminOperation> SUPPORTED=Set.of(
        AdminOperation.STATUS,AdminOperation.EVENT_LIST,
        AdminOperation.EVENT_INSPECT,AdminOperation.MUTATION_INSPECT,AdminOperation.REGION,
        AdminOperation.PLAYER,AdminOperation.CHAIN);
    private NativeReadCommandFence() {}

    public static Set<AdminOperation> supported() { return SUPPORTED; }

    public static Optional<AdminQuery> query(AdminCommand command) {
        Objects.requireNonNull(command);
        if(!SUPPORTED.contains(command.operation()))
            return Optional.empty();
        AdminPanel panel;
        AdminFilter filter;
        switch(command.payload()) {
            case EmptyAdminPayload ignored -> {
                if(command.operation()==AdminOperation.STATUS) {
                    panel=AdminPanel.WORLD;
                    filter=new WorldFilter();
                } else if(command.operation()==AdminOperation.EVENT_LIST) {
                    panel=AdminPanel.ACTIVE;
                    filter=new EventFilter(Optional.empty(),Set.of());
                } else return Optional.empty();
            }
            case EventInspectionPayload p -> {
                panel=AdminPanel.ACTIVE;
                filter=new EventFilter(Optional.of(p.instanceId()),Set.of());
            }
            case MutationInspectionPayload p -> {
                panel=AdminPanel.ACTIVE;
                filter=new MutationFilter(p.mutationId());
            }
            case RegionInspectionPayload p -> {
                panel=AdminPanel.MEMORY;
                filter=new RegionFilter(Optional.of(p.regionId()),Set.of());
            }
            case PlayerInspectionPayload p -> {
                panel=AdminPanel.MEMORY;
                filter=new PlayerFilter(p.playerId());
            }
            case ChainInspectionPayload p -> {
                panel=AdminPanel.ACTIVE;
                filter=new ChainFilter(Optional.of(p.chainId()));
            }
            default -> { return Optional.empty(); }
        }
        return Optional.of(new AdminQuery(command.actorSession(),
            command.requestId(),panel,filter,Optional.empty(),
            Optional.of(command.expectedRevision()),1));
    }

    public static AdminCommandResult answer(AdminCommand command,
            Optional<AdminSnapshot> snapshot) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(snapshot);
        if(query(command).isEmpty())
            return rejected(command,AdminStatus.REJECTED,
                ReasonCode.CAPABILITY_MISSING);
        if(snapshot.isEmpty())
            // No async completion exists for this read; a terminal refusal
            // lets the client explicitly retry instead of hanging PENDING.
            return rejected(command,AdminStatus.REJECTED,
                ReasonCode.STALE_CONTEXT);
        var reply=snapshot.orElseThrow();
        if(!reply.requestId().equals(command.requestId()) ||
           !reply.snapshotRevision().equals(command.expectedRevision()) ||
           reply.rows().size()>1)
            return rejected(command,AdminStatus.FAILED_SAFE,
                ReasonCode.INTERNAL_FAULT);
        if(reply.rows().isEmpty()) {
            // A bounded discovery window with a continuation has not proved absence.
            if(reply.nextCursor().isPresent() && command.operation()==AdminOperation.EVENT_INSPECT)
                return rejected(command,AdminStatus.REJECTED,ReasonCode.STALE_CONTEXT);
            if(command.operation()!=AdminOperation.EVENT_LIST)
                return rejected(command,AdminStatus.REJECTED,
                    ReasonCode.NO_TARGET);
            return new AdminCommandResult(command.requestId(),
                AdminStatus.SUCCEEDED,List.of(),
                Optional.of(reply.snapshotRevision()),Optional.empty());
        }
        var row=reply.rows().getFirst();
        ScopeKind expected=switch(command.operation()) {
            case STATUS, CHAIN -> ScopeKind.WORLD;
            case EVENT_LIST, EVENT_INSPECT, MUTATION_INSPECT -> ScopeKind.EVENT;
            case REGION -> ScopeKind.REGION;
            case PLAYER -> ScopeKind.PLAYER;
            default -> throw new IllegalStateException("unqualified action");
        };
        if(row.scope().kind()!=expected ||
           (command.payload() instanceof EventInspectionPayload p &&
                !row.scope().id().equals(p.instanceId().value())) ||
           (command.payload() instanceof MutationInspectionPayload p &&
                (!(row.data() instanceof MutationAdminData data) ||
                 !data.mutation().mutationId().equals(p.mutationId()) ||
                 !row.scope().id().equals(data.mutation().instanceId().value()))) ||
           (command.payload() instanceof RegionInspectionPayload p &&
                !row.scope().id().equals(p.regionId().value())) ||
           (command.payload() instanceof PlayerInspectionPayload p &&
                !row.scope().id().equals(p.playerId().value())) ||
           (command.payload() instanceof ChainInspectionPayload p &&
                (!(row.data() instanceof ChainAdminData data) ||
                    !data.chain().chainId().equals(p.chainId()))))
            return rejected(command,AdminStatus.FAILED_SAFE,
                ReasonCode.STALE_CONTEXT);
        return new AdminCommandResult(command.requestId(),
            AdminStatus.SUCCEEDED,List.of(),
            Optional.of(reply.snapshotRevision()),Optional.of(row.scope()));
    }

    public static AdminCommandResult rejected(AdminCommand command,
            AdminStatus status,ReasonCode reason) {
        Objects.requireNonNull(command);
        if(status==AdminStatus.SUCCEEDED || status==AdminStatus.PENDING)
            throw new IllegalArgumentException("Only terminal/read refusal");
        return new AdminCommandResult(command.requestId(),status,
            List.of(Objects.requireNonNull(reason)),Optional.empty(),
            Optional.empty());
    }
}