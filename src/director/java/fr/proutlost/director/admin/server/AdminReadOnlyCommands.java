package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.ActorAuthorityPort;
import fr.proutlost.director.api.error.ReasonCode;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Read-only work runs on bounded workers. Only tick() publishes results on the actor.
 * No AdmissionPort, durable writer, or world effect port is available to calculations. */
public final class AdminReadOnlyCommands {
    private record Computed(AdminRow row,Path export,ReasonCode error) {}
    private static final class Job {
        final AdminCommand command; final AdminSnapshotStamp stamp;
        final AtomicBoolean authorized=new AtomicBoolean(true);
        final CompletableFuture<AdminResult> future=new CompletableFuture<>();
        volatile Computed computed;
        volatile AdminLaboratoryResultPages laboratoryPages;
        boolean succeeded;
        Job(AdminCommand command,AdminSnapshotStamp stamp){this.command=command;this.stamp=stamp;}
    }
    private final AdminReadOnlyCalculations calculations;
    private final AdminAuditExporter exporter;
    private final Function<DecisionId,AuditDecision> audits;
    private final ActorAuthorityPort authority;
    private final Executor worker;
    private final int capacity;
    private final Map<RuntimeAdminKey,Job> jobs=new LinkedHashMap<>();
    private final Map<SessionId,AdminRequestHistory> histories=new HashMap<>();
    private static final int MAX_SESSIONS=256;
    public AdminReadOnlyCommands(AdminReadOnlyCalculations calculations,AdminAuditExporter exporter,
            Function<DecisionId,AuditDecision> immutableAudits,ActorAuthorityPort authority,Executor boundedWorker,int capacity) {
        this.calculations=Objects.requireNonNull(calculations);this.exporter=Objects.requireNonNull(exporter);
        this.audits=Objects.requireNonNull(immutableAudits);this.authority=Objects.requireNonNull(authority);this.worker=Objects.requireNonNull(boundedWorker);
        if(capacity<1)throw new IllegalArgumentException("capacity");this.capacity=capacity;
    }
    public boolean handles(AdminOperation operation){return operation==AdminOperation.SIMULATE||operation==AdminOperation.REPLAY||operation==AdminOperation.AUDIT_EXPORT;}
    public CompletionStage<AdminResult> submit(AdminCommand command,AdminSnapshotStamp stamp) {
        if(!handles(command.operation()))throw new IllegalArgumentException("not read only");
        var key=new RuntimeAdminKey(command.actorSession(),command.requestId());
        var existing=jobs.get(key);
        if(existing!=null)return existing.command.equals(command)?existing.future.minimalCompletionStage():
                CompletableFuture.completedFuture(AdminFacade.failure(command,AdminStatus.REJECTED,ReasonCode.IDEMPOTENCY_CONFLICT));
        var history=histories.get(command.actorSession());
        if(history==null) {
            if(histories.size()>=MAX_SESSIONS)return CompletableFuture.completedFuture(AdminFacade.failure(command,AdminStatus.DEFERRED,ReasonCode.RATE_LIMITED));
            history=new AdminRequestHistory();histories.put(command.actorSession(),history);
        }
        if(history.seen(command.requestId()))return CompletableFuture.completedFuture(AdminFacade.failure(command,AdminStatus.DEFERRED,ReasonCode.DELIVERY_UNCERTAIN));
        var retained=jobs.entrySet().stream().filter(e->e.getKey().actorSession().equals(command.actorSession())).toList();
        if(retained.size()>=capacity) {
            var completed=retained.stream().filter(e->e.getValue().future.isDone()).findFirst();
            if(completed.isEmpty())return CompletableFuture.completedFuture(AdminFacade.failure(command,AdminStatus.DEFERRED,ReasonCode.RATE_LIMITED));
            completed.get().getValue().authorized.set(false);jobs.remove(completed.get().getKey());
        }
        history.remember(command.requestId());
        var job=new Job(command,stamp);jobs.put(key,job);
        job.authorized.set(allowed(command));
        try {worker.execute(()-> {
            try {
                if(!job.authorized.get()){job.computed=new Computed(null,null,ReasonCode.AUTH_DENIED);return;}
                if(command.payload() instanceof AuditExportPayload export) {
                    var audit=Objects.requireNonNull(audits.apply(export.decisionId()));
                    if(!audit.decisionId().equals(export.decisionId()))throw new IllegalArgumentException("audit identity");
                    exporter.export(audit,job.authorized::get).whenComplete((path,error)->job.computed=new Computed(null,path,
                            error==null?null:error instanceof SecurityException?ReasonCode.AUTH_DENIED:ReasonCode.INTERNAL_FAULT));
                } else {
                    AdminData data;
                    if(command.payload() instanceof SimulationPayload simulation) {
                        var proposal=calculations.simulate(simulation.fixtureId(),simulation.injectedContext());
                        data=new LaboratoryAdminData(simulation.fixtureId(),proposal,List.of());
                    } else {
                        var replay=(ReplayPayload)command.payload();var proposal=calculations.replay(replay.decisionId(),replay.withoutAi());
                        var audit=proposal.audit();data=new DecisionAdminData(replay.decisionId(),audit.context().capturedTick(),proposal.candidates(),audit.chosen(),audit.advisor(),audit.validations(),audit.outcome());
                    }
                    var row=new AdminRow(new ScopeRef(ScopeKind.WORLD,stamp.worldId().value()),stamp.snapshotRevision(),stamp.capturedTick(),data);
                    if(data instanceof LaboratoryAdminData) {
                        var result=new AdminReadResult(AdminStatus.SUCCEEDED,List.of(row),Optional.empty(),Optional.empty(),Optional.of(stamp));
                        if(!AdminLaboratoryResultPages.fits(result))
                            job.laboratoryPages=new AdminLaboratoryResultPages(command.actorSession(),row,stamp);
                    }
                    job.computed=new Computed(row,null,null);
                }
            } catch(AdminProjectionPages.Rejected rejected){job.computed=new Computed(null,null,rejected.reason());}
            catch(IllegalArgumentException|NullPointerException unavailable){job.computed=new Computed(null,null,ReasonCode.NO_TARGET);}
            catch(RuntimeException error){job.computed=new Computed(null,null,ReasonCode.INTERNAL_FAULT);}
        });}catch(RejectedExecutionException full){job.computed=new Computed(null,null,ReasonCode.RATE_LIMITED);}
        return job.future.minimalCompletionStage();
    }
    private boolean allowed(AdminCommand command){return authority.current(command.actorSession()).filter(a->AdminContracts.authorized(command,a)).isPresent();}
    public void tick() {
        for(var job:List.copyOf(jobs.values())) {
            job.authorized.set(allowed(job.command));
            if(job.future.isDone())continue;
            var computed=job.computed;
            if(!job.authorized.get())job.future.complete(AdminFacade.failure(job.command,AdminStatus.REJECTED,ReasonCode.AUTH_DENIED));
            else if(computed!=null){job.succeeded=computed.error()==null;job.future.complete(computed.error()!=null?
                    AdminFacade.failure(job.command,AdminStatus.REJECTED,computed.error()):
                    new AdminResult(AdminStatus.SUCCEEDED,job.command.requestId(),job.stamp.snapshotRevision(),Optional.ofNullable(computed.row()),Optional.empty()));}
        }
    }
    public Optional<AdminReadResult> query(AdminQuery query) {
        Job selected=null;
        for(var job:jobs.values()) {
            if(!job.command.actorSession().equals(query.actorSession())||!job.succeeded||job.computed==null||job.computed.row()==null||!allowed(job.command))continue;
            boolean match=query.filter() instanceof LaboratoryFilter f && job.command.payload() instanceof SimulationPayload p && f.fixtureId().filter(p.fixtureId()::equals).isPresent()
                    ||query.filter() instanceof DecisionFilter decision && job.command.payload() instanceof ReplayPayload replay && decision.decisionId().filter(replay.decisionId()::equals).isPresent();
            if(match)selected=job;
        }
        if(selected==null)return Optional.empty();
        if(selected.laboratoryPages!=null)return Optional.of(selected.laboratoryPages.query(query));
        if(query.cursor().isPresent())return Optional.of(new AdminReadResult(AdminStatus.DEFERRED,List.of(),Optional.empty(),Optional.empty(),Optional.empty()));
        return Optional.of(new AdminReadResult(AdminStatus.SUCCEEDED,List.of(selected.computed.row()),Optional.empty(),Optional.empty(),Optional.of(selected.stamp)));
    }
    /** Server-derived path for the command adapter's export feedback; never a client path. */
    public Optional<Path> exportPath(RuntimeAdminKey key) {
        var job=jobs.get(key);
        if(job==null||!allowed(job.command)||!job.succeeded||job.computed==null)return Optional.empty();
        return Optional.ofNullable(job.computed.export());
    }
    public void disconnect(SessionId session) {
        histories.remove(session);
        for(var entry:List.copyOf(jobs.entrySet()))if(entry.getKey().actorSession().equals(session)) {
            var job=entry.getValue();job.authorized.set(false);job.future.complete(AdminFacade.failure(job.command,AdminStatus.REJECTED,ReasonCode.AUTH_DENIED));jobs.remove(entry.getKey());
        }
    }
}
