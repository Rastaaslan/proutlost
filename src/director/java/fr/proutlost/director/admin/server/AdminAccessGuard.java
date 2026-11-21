package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.AdminPort;
import fr.proutlost.director.api.port.ActorAuthorityPort;
import fr.proutlost.director.api.port.ProviderProvenance;
import fr.proutlost.director.api.error.ReasonCode;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Server-actor-owned entrance. The downstream runtime must recheck authority at its own
 * deferred application boundary as well. No Minecraft object or disk IO is used here. */
public final class AdminAccessGuard implements AdminPort,ProviderProvenance {
    private static final class Entry {
        final AdminCommand command; final ActorIdentity supplied; final int permissionLevel;
        final CompletableFuture<AdminResult> result=new CompletableFuture<>();
        volatile AdminResult completion;
        Entry(AdminCommand command,ActorIdentity supplied,int permissionLevel) {
            this.command=command;this.supplied=supplied;this.permissionLevel=permissionLevel;
        }
    }
    private static final class ReadAdmission {
        final AdminQuery query;final int permission;final long at;int continuations;
        ReadAdmission(AdminQuery query,int permission,long at){this.query=query;this.permission=permission;this.at=at;}
    }
    private static final class Session {
        ReadAdmission read;
        long lastQuery=-1, window=-1, lastReport=-1; int commands;
        final Map<RequestId,Entry> entries=new LinkedHashMap<>();
        final AdminRequestHistory history=new AdminRequestHistory();
    }
    private final Thread readOwner=Thread.currentThread();
    private final AdminPort downstream;
    private final ActorAuthorityPort authority;
    private final LongSupplier ticks;
    private final Executor mailbox;
    private final int maxSessions,maxRequests,commandsPerSecond;
    private final long refreshTicks,reportGap;
    private final ProviderMode mode;
    private final Map<SessionId,Session> sessions=new HashMap<>();

    public AdminAccessGuard(AdminPort downstream, ActorAuthorityPort authority,
            LongSupplier ticks,Executor boundedActorMailbox,int maxSessions,int maxRequests,
            int commandsPerSecond,long refreshTicks,long reportGap) {
        this(downstream,authority,ticks,boundedActorMailbox,maxSessions,maxRequests,commandsPerSecond,refreshTicks,reportGap,ProviderMode.TEST_ONLY);
    }
    public AdminAccessGuard(AdminPort downstream, ActorAuthorityPort authority,
            LongSupplier ticks,Executor boundedActorMailbox,int maxSessions,int maxRequests,
            int commandsPerSecond,long refreshTicks,long reportGap,ProviderMode mode) {
        this.downstream=Objects.requireNonNull(downstream);this.authority=Objects.requireNonNull(authority);
        this.mode=Objects.requireNonNull(mode);
        if(mode==ProviderMode.PRODUCTION&&(!(downstream instanceof ProviderProvenance provenance)||!provenance.supportedModes().contains(mode)))
            throw new IllegalArgumentException("unprovenanced downstream admin provider");
        this.ticks=Objects.requireNonNull(ticks);this.mailbox=Objects.requireNonNull(boundedActorMailbox);
        if(maxSessions<1||maxRequests<1||commandsPerSecond<1||refreshTicks<1||reportGap<1) throw new IllegalArgumentException("limits");
        this.maxSessions=maxSessions;this.maxRequests=maxRequests;this.commandsPerSecond=commandsPerSecond;
        this.refreshTicks=refreshTicks;this.reportGap=reportGap;
    }
    public Set<ProviderMode> supportedModes(){return Set.of(mode);}
    private Optional<ActorIdentity> current(ActorIdentity supplied) {
        return authority.current(supplied.session()).filter(a->a.serverAuthenticated() && supplied.serverAuthenticated()
                && a.session().equals(supplied.session()) && a.kind()==supplied.kind() && a.playerId().equals(supplied.playerId()) && a.systemModule().equals(supplied.systemModule()));
    }
    private Session session(SessionId id) {
        if(!sessions.containsKey(id)&&sessions.size()>=maxSessions) return null;
        return sessions.computeIfAbsent(id,k->new Session());
    }
    public void disconnect(SessionId id) { sessions.remove(id); }
    /** Call on the server actor each tick. Completion slots are bounded by session/request
     * limits; worker callbacks only publish immutable results, never inspect authority.
     * A saturated notification mailbox delays delivery without changing the applied outcome. */
    public void drainCompletions() {
        for(var state:List.copyOf(sessions.values()))
            for(var entry:List.copyOf(state.entries.values())) deliver(state,entry);
    }
    private void deliver(Session state,Entry entry) {
        var result=entry.completion;
        if(result==null||entry.result.isDone()) return;
        var actor=current(entry.supplied);
        if(sessions.get(entry.command.actorSession())!=state||actor.isEmpty()
                ||!AdminContracts.authorized(entry.command,actor.get())||actor.get().permissionLevel()<entry.permissionLevel)
            entry.result.complete(reject(entry.command,ReasonCode.AUTH_DENIED));
        else if(actor.get().permissionLevel()<4 && result.data().isPresent() && !(result.data().get().data() instanceof PublicStatusData))
            entry.result.complete(new AdminResult(result.status(),result.requestId(),result.revision(),Optional.empty(),result.error()));
        else entry.result.complete(result);
    }
    @Override public AdminReadResult query(AdminQuery query,ActorIdentity supplied) {
        var actor=current(supplied);
        if(actor.isEmpty()||!query.actorSession().equals(actor.get().session())||actor.get().permissionLevel()<4)
            return deniedRead(ReasonCode.AUTH_DENIED);
        var state=session(query.actorSession());long now=ticks.getAsLong();
        if(state==null||state.lastQuery>=0&&(now<state.lastQuery||now-state.lastQuery<refreshTicks)) return deniedRead(ReasonCode.RATE_LIMITED);
        state.lastQuery=now;state.read=null;
        var result=downstream.query(query,actor.get());
        if(awaitablePlayerRead(query,result))state.read=new ReadAdmission(query,actor.get().permissionLevel(),now);
        return result;
    }
    private static boolean awaitablePlayerRead(AdminQuery query,AdminReadResult result){
        return query.panel()==AdminPanel.MEMORY&&query.filter() instanceof PlayerFilter&&query.cursor().isEmpty()
            &&(result.status()==AdminStatus.DEFERRED||result.status()==AdminStatus.REJECTED)
            &&result.error().map(e->e.code()==ReasonCode.STALE_CONTEXT).orElse(false);
    }
    /** Continue only the exact already charged request, in the same refresh window.
     * A different query, actor, role, session or completed admission gets no credit. */
    public AdminReadResult continueRead(AdminQuery query,ActorIdentity supplied){
        if(Thread.currentThread()!=readOwner)throw new IllegalStateException("admin read continuation actor");
        var actor=current(supplied);
        if(actor.isEmpty()||actor.get().permissionLevel()<4||!query.actorSession().equals(actor.get().session()))
            return deniedRead(ReasonCode.AUTH_DENIED);
        var state=sessions.get(query.actorSession());var admission=state==null?null:state.read;long now=ticks.getAsLong();
        if(admission==null||!admission.query.equals(query)||actor.get().permissionLevel()<admission.permission
            ||now<admission.at||now-admission.at>=refreshTicks||admission.continuations>=4)
            return deniedRead(ReasonCode.STALE_CONTEXT);
        admission.continuations++;
        var result=downstream.query(query,actor.get());
        if(!awaitablePlayerRead(query,result))state.read=null;
        return result;
    }
    @Override public CompletionStage<AdminResult> submit(AdminCommand command,ActorIdentity supplied) {
        var actor=current(supplied);
        if(actor.isEmpty()||!AdminContracts.authorized(command,actor.get())) return CompletableFuture.completedFuture(reject(command,ReasonCode.AUTH_DENIED));
        var state=session(command.actorSession());
        if(state==null) return CompletableFuture.completedFuture(reject(command,ReasonCode.RATE_LIMITED));
        var existing=state.entries.get(command.requestId());
        if(existing!=null) {
            if(actor.get().permissionLevel()<existing.permissionLevel) return CompletableFuture.completedFuture(reject(command,ReasonCode.AUTH_DENIED));
            if(!existing.command.equals(command)) return CompletableFuture.completedFuture(reject(command,ReasonCode.IDEMPOTENCY_CONFLICT));
            deliver(state,existing);
            return existing.result.minimalCompletionStage();
        }
        if(state.history.seen(command.requestId()))return CompletableFuture.completedFuture(AdminFacade.failure(command,AdminStatus.DEFERRED,ReasonCode.DELIVERY_UNCERTAIN));
        if(state.entries.size()>=maxRequests) {
            var completed=state.entries.entrySet().stream().filter(e->e.getValue().result.isDone()).findFirst();
            if(completed.isPresent())state.entries.remove(completed.get().getKey());
        }
        long now=ticks.getAsLong(),window=now/20;
        if(state.entries.size()>=maxRequests || state.window>window || state.window==window&&state.commands>=commandsPerSecond
                ||command.operation()==AdminOperation.REPORT&&state.lastReport>=0&&(now<state.lastReport||now-state.lastReport<reportGap))
            return CompletableFuture.completedFuture(reject(command,ReasonCode.RATE_LIMITED));
        if(state.window!=window) {state.window=window;state.commands=0;}
        state.commands++;
        if(command.operation()==AdminOperation.REPORT) state.lastReport=now;
        state.history.remember(command.requestId());
        var entry=new Entry(command,supplied,actor.get().permissionLevel());
        var future=entry.result;state.entries.put(command.requestId(),entry);
        try { mailbox.execute(()-> {
            var latest=current(supplied);
            if(sessions.get(command.actorSession())!=state||latest.isEmpty()||!AdminContracts.authorized(command,latest.get())) {
                future.complete(reject(command,ReasonCode.AUTH_DENIED));return;
            }
            try { downstream.submit(command,latest.get()).whenComplete((result,error)-> {
                entry.completion=error!=null||result==null||!result.requestId().equals(command.requestId())
                        ?reject(command,ReasonCode.INTERNAL_FAULT):result;
                try { mailbox.execute(()->deliver(state,entry)); }
                catch(RejectedExecutionException full) {
                    // Retained for actor tick drainage or an idempotent retry. No false refusal.
                }
            }); } catch(RuntimeException error) { future.complete(reject(command,ReasonCode.INTERNAL_FAULT)); }
        }); } catch(RejectedExecutionException full) { future.complete(reject(command,ReasonCode.RATE_LIMITED)); }
        return future.minimalCompletionStage();
    }
    private static AdminResult reject(AdminCommand command,ReasonCode code) {
        return new AdminResult(code==ReasonCode.INTERNAL_FAULT?AdminStatus.FAILED_SAFE:AdminStatus.REJECTED,
                command.requestId(),command.expectedRevision(),Optional.empty(),Optional.of(error(code)));
    }
    private static AdminReadResult deniedRead(ReasonCode code) { return new AdminReadResult(AdminStatus.REJECTED,List.of(),Optional.of(error(code)),Optional.empty()); }
    private static SystemError error(ReasonCode code) { return new SystemError(code,Severity.WARNING,false,Optional.empty(),Set.of(),new CorrelationId(UUID.randomUUID()),SafeState.UNCHANGED); }
}
