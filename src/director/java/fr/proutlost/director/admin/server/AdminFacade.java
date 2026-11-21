package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.api.error.ReasonCode;
import java.util.*;
import java.util.concurrent.*;

/** Actor-owned facade. Bootstrap calls tick() on the server actor and installs the
 * rate-limiting AdminAccessGuard outside it. No join, IO, or world access occurs here. */
public final class AdminFacade implements AdminPort,ProviderProvenance {
    static final int MAX_READ_BYTES=24576;
    private static final class Pending {
        final AdminCommand command; final ActorIdentity actor;
        final CompletableFuture<AdminResult> future=new CompletableFuture<>();
        volatile AdminResult completion;
        boolean runtime;
        Pending(AdminCommand command,ActorIdentity actor){this.command=command;this.actor=actor;}
    }
    private final AdminProjectionPort projections;
    private final RuntimeAdminPort runtime;
    private final ActorAuthorityPort authority;
    private final WorldId world; private final Epoch boot;
    private final int capacity;
    private final AdminReadOnlyCommands readOnly;
    private final ProviderMode mode;
    private final Map<RuntimeAdminKey,Pending> requests=new LinkedHashMap<>();
    private final Map<SessionId,AdminRequestHistory> histories=new HashMap<>();
    private static final int MAX_SESSIONS=256;

    public AdminFacade(AdminProjectionPort projections,RuntimeAdminPort runtime,ActorAuthorityPort authority,
            WorldId world,Epoch boot,int capacity,AdminReadOnlyCommands readOnly) {
        this(projections,runtime,authority,world,boot,capacity,readOnly,ProviderMode.TEST_ONLY);
    }
    public AdminFacade(AdminProjectionPort projections,RuntimeAdminPort runtime,ActorAuthorityPort authority,
            WorldId world,Epoch boot,int capacity,AdminReadOnlyCommands readOnly,ProviderMode mode) {
        this.projections=Objects.requireNonNull(projections);this.runtime=Objects.requireNonNull(runtime);
        this.mode=Objects.requireNonNull(mode);provenance(runtime,mode);
        this.authority=Objects.requireNonNull(authority);this.world=Objects.requireNonNull(world);this.boot=Objects.requireNonNull(boot);
        if(capacity<1) throw new IllegalArgumentException("capacity");this.capacity=capacity;
        this.readOnly=Objects.requireNonNull(readOnly);
    }
    /** Runtime-only bootstrap; no read/calculation/export capability is constructed. */
    public AdminFacade(RuntimeAdminPort runtime,ActorAuthorityPort authority,
            WorldId world,Epoch boot,int capacity) {
        this(runtime,authority,world,boot,capacity,ProviderMode.TEST_ONLY);
    }
    public AdminFacade(RuntimeAdminPort runtime,ActorAuthorityPort authority,
            WorldId world,Epoch boot,int capacity,ProviderMode mode) {
        this.projections=null;this.readOnly=null;
        this.runtime=Objects.requireNonNull(runtime);this.authority=Objects.requireNonNull(authority);
        this.mode=Objects.requireNonNull(mode);provenance(runtime,mode);
        this.world=Objects.requireNonNull(world);this.boot=Objects.requireNonNull(boot);
        if(capacity<1)throw new IllegalArgumentException("capacity");this.capacity=capacity;
    }
    public Set<ProviderMode> supportedModes(){return Set.of(mode);}
    private static void provenance(RuntimeAdminPort runtime,ProviderMode mode) {
        if(mode==ProviderMode.PRODUCTION&&(!(runtime instanceof ProviderProvenance provenance)||!provenance.supportedModes().contains(mode)))
            throw new IllegalArgumentException("unprovenanced runtime admin provider");
    }
    private Optional<ActorIdentity> current(ActorIdentity supplied) {
        return authority.current(supplied.session()).filter(a->a.serverAuthenticated()&&supplied.serverAuthenticated()
                &&a.session().equals(supplied.session())&&a.kind()==supplied.kind()
                &&a.playerId().equals(supplied.playerId())&&a.systemModule().equals(supplied.systemModule()));
    }
    @Override public AdminReadResult query(AdminQuery query,ActorIdentity supplied) {
        var actor=current(supplied);
        if(actor.isEmpty()||actor.get().permissionLevel()<4||!query.actorSession().equals(actor.get().session()))
            return readError(AdminStatus.REJECTED,ReasonCode.AUTH_DENIED);
        // Session-private calculation results remain queryable by their original typed target.
        // Projection cursors may become stale between requests. Never let a typed
        // rejection or a corrupted projection escape into the Minecraft server actor.
        final AdminReadResult result;
        try {
            if(readOnly==null)return readError(AdminStatus.REJECTED,ReasonCode.CAPABILITY_MISSING);
            result=Objects.requireNonNull(readOnly.query(query).orElseGet(()->projections.query(query)));
        } catch(AdminProjectionPages.Rejected rejected) {
            var reason=rejected.reason();
            return readError(reason==ReasonCode.STALE_CONTEXT?AdminStatus.DEFERRED:AdminStatus.REJECTED,reason);
        } catch(RuntimeException unexpected) {
            return readError(AdminStatus.FAILED_SAFE,ReasonCode.INTERNAL_FAULT);
        }
        if(result.status()!=AdminStatus.SUCCEEDED) return result;
        if(result.snapshot().isEmpty()) return readError(AdminStatus.DEFERRED,ReasonCode.STALE_CONTEXT);
        var stamp=result.snapshot().get();
        if(!stamp.worldId().equals(world)||!stamp.bootEpoch().equals(boot)
                ||query.expectedSnapshotRevision().filter(r->!r.equals(stamp.snapshotRevision())).isPresent())
            return readError(AdminStatus.DEFERRED,ReasonCode.STALE_CONTEXT);
        if(result.data().size()>query.limit()||result.nextCursor().filter(c->c.length()>512).isPresent())
            return readError(AdminStatus.REJECTED,ReasonCode.BUDGET_EXHAUSTED);
        try {
            if(new fr.proutlost.director.api.codec.ModelCodec<>(AdminReadResult.class).encode(result).length>MAX_READ_BYTES)
                return readError(AdminStatus.REJECTED,ReasonCode.BUDGET_EXHAUSTED);
        } catch(IllegalArgumentException oversized) {return readError(AdminStatus.REJECTED,ReasonCode.BUDGET_EXHAUSTED);}
        return result;
    }
    /** Closed wire v1 uses the existing snapshot DTO; metadata comes only from the stamp. */
    public static AdminSnapshot snapshot(AdminQuery query,AdminReadResult result,Set<AdminOperation> capabilities) {
        if(result.status()!=AdminStatus.SUCCEEDED) throw new IllegalArgumentException("unsuccessful query");
        var stamp=result.snapshot().orElseThrow(()->new IllegalArgumentException("unavailable publication"));
        return new AdminSnapshot(query.requestId(),stamp.snapshotRevision(),stamp.capturedTick(),stamp.lastDurableSequence(),
                result.data(),result.nextCursor(),capabilities);
    }
    /** Mapping for J06's existing closed command-result payload, preserving correlation/ref. */
    public static AdminCommandResult commandResult(AdminResult result) {
        return new AdminCommandResult(result.requestId(),result.status(),result.error().map(e->List.of(e.code())).orElseGet(List::of),
                Optional.of(result.revision()),result.data().map(AdminRow::scope));
    }
    @Override public CompletionStage<AdminResult> submit(AdminCommand command,ActorIdentity supplied) {
        var actor=current(supplied);
        if(actor.isEmpty()||!AdminContracts.authorized(command,actor.get())) return completed(failure(command,AdminStatus.REJECTED,ReasonCode.AUTH_DENIED));
        var key=new RuntimeAdminKey(command.actorSession(),command.requestId());var old=requests.get(key);
        if(old!=null) {
            if(!old.command.equals(command)) return completed(failure(command,AdminStatus.REJECTED,ReasonCode.IDEMPOTENCY_CONFLICT));
            if(actor.get().permissionLevel()<old.actor.permissionLevel()) return completed(failure(command,AdminStatus.REJECTED,ReasonCode.AUTH_DENIED));
            advance(old);return old.future.minimalCompletionStage();
        }
        var history=histories.get(command.actorSession());
        if(history==null) {
            if(histories.size()>=MAX_SESSIONS)return completed(failure(command,AdminStatus.DEFERRED,ReasonCode.RATE_LIMITED));
            history=new AdminRequestHistory();histories.put(command.actorSession(),history);
        }
        if(history.seen(command.requestId()))return completed(failure(command,AdminStatus.DEFERRED,ReasonCode.DELIVERY_UNCERTAIN));
        var retained=requests.entrySet().stream().filter(e->e.getKey().actorSession().equals(command.actorSession())).toList();
        if(retained.size()>=capacity) {
            var completed=retained.stream().filter(e->e.getValue().future.isDone()).findFirst();
            if(completed.isEmpty())return completed(failure(command,AdminStatus.DEFERRED,ReasonCode.RATE_LIMITED));
            requests.remove(completed.get().getKey());
        }
        history.remember(command.requestId());
        var pending=new Pending(command,actor.get());requests.put(key,pending);
        try {
            if(readOnly!=null&&readOnly.handles(command.operation())) {
                var context=query(new AdminQuery(command.actorSession(),command.requestId(),AdminPanel.WORLD,new WorldFilter(),
                        Optional.empty(),Optional.of(command.expectedRevision()),1),actor.get());
                if(context.status()!=AdminStatus.SUCCEEDED) pending.completion=new AdminResult(context.status(),command.requestId(),command.expectedRevision(),Optional.empty(),context.error());
                else readOnly.submit(command,context.snapshot().orElseThrow()).whenComplete((r,e)->pending.completion=e==null&&r!=null?r:failure(command,AdminStatus.FAILED_SAFE,ReasonCode.INTERNAL_FAULT));
            } else if(inspection(command).isPresent()) {
                var filter=inspection(command).orElseThrow();
                var panel=filter instanceof RegionFilter||filter instanceof PlayerFilter?AdminPanel.MEMORY:AdminPanel.ACTIVE;
                var result=query(new AdminQuery(command.actorSession(),command.requestId(),panel,filter,Optional.empty(),Optional.of(command.expectedRevision()),1),actor.get());
                pending.completion=result.status()!=AdminStatus.SUCCEEDED?
                        new AdminResult(result.status(),command.requestId(),command.expectedRevision(),Optional.empty(),result.error()):
                        result.nextCursor().isPresent()?
                            (panel==AdminPanel.MEMORY&&!result.data().isEmpty()?new AdminResult(AdminStatus.DEFERRED,command.requestId(),
                                result.snapshot().orElseThrow().snapshotRevision(),Optional.of(result.data().getFirst()),
                                failure(command,AdminStatus.DEFERRED,ReasonCode.BUDGET_EXHAUSTED).error()):failure(command,AdminStatus.DEFERRED,ReasonCode.BUDGET_EXHAUSTED)):
                        result.data().isEmpty()?failure(command,AdminStatus.REJECTED,ReasonCode.NO_TARGET):
                        new AdminResult(AdminStatus.SUCCEEDED,command.requestId(),result.snapshot().orElseThrow().snapshotRevision(),Optional.of(result.data().getFirst()),Optional.empty());
            } else if(command.operation()==AdminOperation.UI) {
                pending.completion=new AdminResult(AdminStatus.SUCCEEDED,command.requestId(),command.expectedRevision(),Optional.empty(),Optional.empty());
            } else {
                pending.runtime=true;pending.completion=Objects.requireNonNull(runtime.submit(command));
            }
        } catch(RejectedExecutionException full) {pending.completion=failure(command,AdminStatus.DEFERRED,ReasonCode.RATE_LIMITED);}
        catch(RuntimeException error) {pending.completion=failure(command,pending.runtime?AdminStatus.DEFERRED:AdminStatus.FAILED_SAFE,pending.runtime?ReasonCode.DELIVERY_UNCERTAIN:ReasonCode.INTERNAL_FAULT);}
        finish(pending);return pending.future.minimalCompletionStage();
    }
    private static Optional<AdminFilter> inspection(AdminCommand command) {
        return switch(command.payload()) {
            case RegionInspectionPayload p -> Optional.of(new RegionFilter(Optional.of(p.regionId()),Set.of()));
            case PlayerInspectionPayload p -> Optional.of(new PlayerFilter(p.playerId()));
            case EventInspectionPayload p -> Optional.of(new EventFilter(Optional.of(p.instanceId()),Set.of()));
            case MutationInspectionPayload p -> Optional.of(new MutationFilter(p.mutationId()));
            case ChainInspectionPayload p -> Optional.of(new ChainFilter(Optional.of(p.chainId())));
            default -> Optional.empty();
        };
    }
    /** Bounded actor polling; worker completion never reads live authority. */
    public void tick() {
        if(readOnly!=null)readOnly.tick();
        for(var pending:List.copyOf(requests.values())) advance(pending);
    }
    private void advance(Pending pending) {
        if(pending.future.isDone()) return;
        if(pending.runtime&&pending.completion!=null&&(pending.completion.status()==AdminStatus.PENDING||pending.completion.status()==AdminStatus.DEFERRED)) {
            // Never poll runtime with revoked authority.
            var actor=current(pending.actor);
            if(actor.isEmpty()||!AdminContracts.authorized(pending.command,actor.get())||actor.get().permissionLevel()<pending.actor.permissionLevel()) {
                pending.future.complete(failure(pending.command,AdminStatus.REJECTED,ReasonCode.AUTH_DENIED));return;
            }
            try {pending.completion=Objects.requireNonNull(runtime.poll(new RuntimeAdminKey(pending.command.actorSession(),pending.command.requestId())));}
            catch(RuntimeException e) {pending.completion=failure(pending.command,AdminStatus.DEFERRED,ReasonCode.DELIVERY_UNCERTAIN);}
        }
        finish(pending);
    }
    private void finish(Pending pending) {
        if(pending.future.isDone()||pending.completion==null) return;
        var result=pending.completion;
        if(!result.requestId().equals(pending.command.requestId())) result=failure(pending.command,AdminStatus.DEFERRED,ReasonCode.DELIVERY_UNCERTAIN);
        pending.completion=result;
        if(result.status()==AdminStatus.PENDING || pending.runtime&&result.status()==AdminStatus.DEFERRED
                &&result.error().map(e->e.code()!=ReasonCode.STALE_CONTEXT
                    &&(e.code()==ReasonCode.DELIVERY_UNCERTAIN||e.retriable())).orElse(true)) return;
        var actor=current(pending.actor);
        if(actor.isEmpty()||!AdminContracts.authorized(pending.command,actor.get())||actor.get().permissionLevel()<pending.actor.permissionLevel())
            result=failure(pending.command,AdminStatus.REJECTED,ReasonCode.AUTH_DENIED);
        else if(actor.get().permissionLevel()<4&&result.data().isPresent()&&!(result.data().get().data() instanceof PublicStatusData))
            result=new AdminResult(result.status(),result.requestId(),result.revision(),Optional.empty(),result.error());
        pending.future.complete(result);
    }
    public void disconnect(SessionId session) {
        if(readOnly!=null)readOnly.disconnect(session);
        histories.remove(session);
        for(var entry:List.copyOf(requests.entrySet())) if(entry.getKey().actorSession().equals(session)) {
            entry.getValue().future.complete(failure(entry.getValue().command,AdminStatus.REJECTED,ReasonCode.AUTH_DENIED));requests.remove(entry.getKey());
        }
    }
    private static CompletionStage<AdminResult> completed(AdminResult result){return CompletableFuture.completedFuture(result);}
    public static AdminResult failure(AdminCommand c,AdminStatus status,ReasonCode code) {
        return new AdminResult(status,c.requestId(),c.expectedRevision(),Optional.empty(),Optional.of(error(code)));
    }
    private static AdminReadResult readError(AdminStatus status,ReasonCode code){return new AdminReadResult(status,List.of(),Optional.of(error(code)),Optional.empty(),Optional.empty());}
    private static SystemError error(ReasonCode code){return new SystemError(code,Severity.WARNING,false,Optional.empty(),Set.of(),new CorrelationId(UUID.randomUUID()),code==ReasonCode.DELIVERY_UNCERTAIN?SafeState.DEFERRED:SafeState.UNCHANGED);}
}
