package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.network.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.ActorAuthorityPort;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.BoundedDecisionWorker;
import fr.proutlost.director.runtime.DirectorTickWorkBudget;
import java.util.*;
import java.util.function.*;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** One bounded, actor-owned TEST_ONLY decision/admission lane.
 * The decision journal is drained before an explicit control transition;
 * chain consent is polled before this runtime on each fresh actor phase.
 */
public final class DirectorNativeCatalogueRuntime implements AutoCloseable {
    private static final long REQUEST_TICKS=1200,RETRY_TICKS=200;
    private final MinecraftServer server;
    private final WorldId world;
    private final JournalStore store;
    private final StoreSession session;
    private final DirectorNativeCatalogueExecution executor;
    private final DirectorDecisionJournal journal;
    private final LongFunction<Optional<DecisionInput>> inputs;
    private final BooleanSupplier enabled,transitionPending;
    private final LongSupplier clock;
    private final Executor archiveWorker;
    private final BoundedDecisionWorker decisionWorker=new BoundedDecisionWorker("director-pure-decision");
    private boolean closed;
    private Supplier<Optional<DirectorTickWorkBudget>> globalBudget=Optional::empty;
    public void globalBudget(Supplier<Optional<DirectorTickWorkBudget>> budget){actor();globalBudget=Objects.requireNonNull(budget);}
    private final Map<UUID,Job> jobs=new LinkedHashMap<>();
    private final LinkedHashMap<UUID,DirectorNativeChainGameplay.Launch> replies=new LinkedHashMap<>();
    private Job current;
    private boolean automatic,frozen,stopFence;
    private final Set<UUID> stopBarrier=new HashSet<>();
    private boolean stopUnidentifiedCurrent;
    private long ordinal,nextAutomatic;
    private String last="IDLE";
    private static final class Job {
        UUID event,actor;DefinitionId definition;Optional<RegionId> region;Optional<ChainId> parent;
        SessionIdentity client;long deadline,nextTry;boolean registered,started,automatic,isolated;
        AdminCommand command;ActorAuthorityPort authority;Set<PlayerId> targets=Set.of();
        CompletableFuture<Map.Entry<DirectorFact,List<BlobContent>>> recording;boolean submitted;DecisionProposal previousNoPlan;
        String cancelled;DecisionProposal proposal;DirectorNativeCatalogueExecution.Dispatch dispatch;
        DirectorNativeCatalogueExecution.EvaluationWork evaluation;
    }
    public DirectorNativeCatalogueRuntime(MinecraftServer server,WorldId world,JournalStore store,
            DirectorNativeCatalogueExecution executor,LongFunction<Optional<DecisionInput>> inputs,
            BooleanSupplier enabled,BooleanSupplier transitionPending,LongSupplier clock){
        this(server,world,store,executor,inputs,enabled,transitionPending,clock,null);
    }
    public DirectorNativeCatalogueRuntime(MinecraftServer server,WorldId world,JournalStore store,
            DirectorNativeCatalogueExecution executor,LongFunction<Optional<DecisionInput>> inputs,
            BooleanSupplier enabled,BooleanSupplier transitionPending,LongSupplier clock,Executor archiveWorker){
        this.archiveWorker=archiveWorker;
        if(executor.mode()==ProviderMode.PRODUCTION&&archiveWorker==null)throw new IllegalArgumentException("production decision archive worker required");
        this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);
        this.store=Objects.requireNonNull(store);this.executor=Objects.requireNonNull(executor);
        this.inputs=Objects.requireNonNull(inputs);this.enabled=Objects.requireNonNull(enabled);
        this.transitionPending=Objects.requireNonNull(transitionPending);this.clock=Objects.requireNonNull(clock);
        actor();session=store.session();journal=new DirectorDecisionJournal(store,4);
    }
    private void actor(){if(!server.isSameThread())throw new IllegalStateException("catalogue runtime actor");}
    private Optional<SessionIdentity> client(ServerPlayer player){
        return DirectorNativeNetworkBootstrap.qualifiedRecipient(server,new PlayerId(player.getUUID()),
            PerceptionKind.SYMBOL,DirectorGameplayAssets.WARNING,DirectorGameplayAssets.WARNING_HASH);
    }
    private String invalid(Job job){
        if(job.cancelled!=null)return job.cancelled;
        if(!session.equals(store.session())||store.readOnlySafe())return "WAL_SESSION_CHANGED";
        if(clock.getAsLong()>=job.deadline)return "ORIGINAL_REQUEST_EXPIRED";
        if(job.actor!=null){
            var player=server.getPlayerList().getPlayer(job.actor);
            if(player==null||!player.isAlive()||job.command==null&&!job.automatic&&!player.hasPermissions(4)&&!verifiedParent(job.definition,job.region,job.parent))
                return "INITIATOR_LEFT_OR_OP4_LOST";
            if(job.client!=null&&!client(player).filter(job.client::equals).isPresent())return "J06_SESSION_CHANGED";
            if(job.region.isPresent()&&fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle()
                .runtimePrimaryRegion(server,new BlockPosition(new DimensionId(player.serverLevel().dimension().location().toString()),
                    player.blockPosition().getX(),player.blockPosition().getY(),player.blockPosition().getZ()))
                .filter(job.region.get()::equals).isEmpty())return "ORIGINAL_REGION_CHANGED";
        }
        if(job.command!=null&&job.authority.current(job.command.actorSession()).filter(a->AdminContracts.authorized(job.command,a)).isEmpty())return "ADMIN_AUTHORITY_REVOKED";
        if(!enabled.getAsBoolean())return "CATALOGUE_PAUSED";
        return null;
    }
    private boolean verifiedParent(DefinitionId definition,Optional<RegionId> region,Optional<ChainId> parent){
        if(executor.mode()!=ProviderMode.PRODUCTION||parent.isEmpty()||region.isEmpty())return false;
        var query=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.CHAIN,parent.get().value())),new Sequence(0),Optional.empty(),1);
        var read=store.view(query);ReadContracts.validate(read,query,session);
        if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().aggregates().size()!=1||!(read.page().orElseThrow().aggregates().getFirst().state() instanceof ChainAggregate aggregate))return false;
        var chain=aggregate.value();var recipe=executor.catalog().chains().get(chain.recipe().id());
        if(recipe==null||recipe.calibration()!=Calibration.APPROVED||chain.state()!=ChainState.ACTIVE||!chain.lineageRefs().contains(region.get())||chain.branchHistory().isEmpty())return false;
        var reference=new DefinitionRef(recipe.id(),recipe.contentRevision(),BlobContent.hash(new fr.proutlost.director.api.codec.ModelCodec<>(ChainDefinition.class).encode(recipe)));
        if(!reference.equals(chain.recipe()))return false;var last=chain.branchHistory().getLast();
        return recipe.transitions().stream().anyMatch(t->t.id().equals(last.transitionId())&&t.eventId().filter(definition::equals).isPresent()&&chain.currentNodes().equals(Set.of(t.to()))&&chain.plannedHorizon().equals(List.of(t.id())));
    }
    private void register(Job job){
        if(job.registered||job.event==null||job.definition==null||job.actor==null)return;
        executor.registerQueuedIntent(new DirectorNativeCatalogueExecution.QueuedIntent(
            new EventInstanceId(job.event),job.definition,job.parent,new PlayerId(job.actor),job.region,session));
        job.registered=true;
    }
    public DirectorNativeChainGameplay.Launch enqueue(ServerPlayer actor,DefinitionId definition,UUID event,
            Optional<RegionId> region,Optional<ChainId> parent){
        actor();Objects.requireNonNull(actor);Objects.requireNonNull(definition);Objects.requireNonNull(event);
        if(server.getPlayerList().getPlayer(actor.getUUID())!=actor||!actor.isAlive()||!actor.hasPermissions(4)&&!verifiedParent(definition,region,parent))
            return new DirectorNativeChainGameplay.Launch(false,"Joueur OP4 connecte requis.");
        if(!executor.catalog().events().containsKey(definition)||!enabled.getAsBoolean()||frozen||stopFence)
            return new DirectorNativeChainGameplay.Launch(false,"Catalogue TEST_ONLY non actif ou definition inconnue.");
        var previous=replies.get(event);if(previous!=null)return previous;
        var existing=jobs.get(event);
        if(existing!=null){
            if(!existing.actor.equals(actor.getUUID())||!existing.definition.equals(definition)||
                !existing.region.equals(region)||!existing.parent.equals(parent))
                throw new IllegalArgumentException("queued catalogue identity changed");
            return new DirectorNativeChainGameplay.Launch(false,"Audit/admission J04 en attente.",true);
        }
        if(jobs.size()>=16)return new DirectorNativeChainGameplay.Launch(false,"File catalogue pleine.");
        var client=client(actor);if(client.isEmpty())return new DirectorNativeChainGameplay.Launch(false,"Session J06 negociee requise.");
        var job=new Job();job.event=event;job.actor=actor.getUUID();job.definition=definition;
        job.region=Objects.requireNonNull(region);job.parent=Objects.requireNonNull(parent);
        job.client=client.get();job.deadline=Math.addExact(clock.getAsLong(),REQUEST_TICKS);
        register(job);jobs.put(event,job);
        return new DirectorNativeChainGameplay.Launch(false,"Demande catalogue reelle en attente, UUID="+event,true);
    }
    /** Canonical production admin path; identity is rechecked on every queue poll. */
    public DirectorNativeChainGameplay.Launch enqueueAdmin(ServerPlayer participant,AdminCommand command,
            ActorIdentity actor,ActorAuthorityPort authority){
        actor();if(!(command.payload() instanceof TriggerPayload payload)||!AdminContracts.authorized(command,actor)
            ||authority.current(command.actorSession()).filter(actor::equals).isEmpty())
            return new DirectorNativeChainGameplay.Launch(false,"AUTH_DENIED");
        var event=UUID.nameUUIDFromBytes(("admin-trigger/"+command.actorSession().value()+"/"+command.requestId().value()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(replies.containsKey(event))return replies.get(event);
        if(jobs.containsKey(event))return new DirectorNativeChainGameplay.Launch(false,"PENDING",true);
        // Preserve the exact original short-circuit order and closed outcome.
        String admissionRefusal=jobs.size()>=16?"QUEUE_CAPACITY":frozen?"FROZEN":stopFence?"STOP_FENCE":
            !enabled.getAsBoolean()?"DISABLED":!executor.catalog().events().containsKey(payload.eventId())?"UNKNOWN_DEFINITION":null;
        if(admissionRefusal!=null){
            System.out.println("DIRECTOR_NATIVE_TRIGGER_ADMISSION_REFUSED request="+command.requestId()+" event="+event+" definition="+payload.eventId()+" predicate="+admissionRefusal);
            return new DirectorNativeChainGameplay.Launch(false,"CATALOGUE_UNAVAILABLE");
        }
        if(participant==null||server.getPlayerList().getPlayer(participant.getUUID())!=participant||!participant.isAlive())
            return new DirectorNativeChainGameplay.Launch(false,"NO_ACTUAL_PARTICIPANT");
        var negotiated=client(participant);if(negotiated.isEmpty())return new DirectorNativeChainGameplay.Launch(false,"J06_NOT_QUALIFIED");
        var job=new Job();job.command=command;job.authority=authority;job.targets=payload.targets();
        job.event=event;job.actor=participant.getUUID();job.definition=payload.eventId();job.region=Optional.of(payload.regionId());
        job.parent=Optional.empty();job.client=negotiated.get();job.deadline=Math.addExact(clock.getAsLong(),REQUEST_TICKS);
        register(job);jobs.put(event,job);return new DirectorNativeChainGameplay.Launch(false,"PENDING",true);
    }
    public Optional<DirectorNativeChainGameplay.Launch> pendingStart(UUID event){
        actor();var reply=replies.get(event);if(reply!=null)return Optional.of(reply);
        return jobs.containsKey(event)?Optional.of(new DirectorNativeChainGameplay.Launch(false,"Audit/admission en attente.",true)):Optional.empty();
    }
    public void cancelPending(UUID event,String reason){
        actor();var job=jobs.get(event);if(job==null||job.started)return;
        if(job.cancelled==null)job.cancelled=Objects.requireNonNull(reason);
        if(job.evaluation!=null){executor.cancelEvaluation(job.evaluation);job.evaluation=null;}
    }
    private void reply(Job job,boolean accepted,String detail){
        if(job.event==null)return;
        System.out.println("DIRECTOR_NATIVE_TRIGGER_TERMINAL event="+job.event+" request="+(job.command==null?"NONE":job.command.requestId())+" accepted="+accepted+" detail="+detail);
        replies.put(job.event,new DirectorNativeChainGameplay.Launch(accepted,detail));
        while(replies.size()>128)replies.remove(replies.keySet().iterator().next());
    }
    private void retire(Job job){
        if(job.proposal!=null){
            executor.retire(job.proposal.proposalId());journal.retireTerminal(job.proposal.proposalId());
        }
        if(job.event!=null){executor.retireQueuedIntent(new EventInstanceId(job.event));jobs.remove(job.event);}
        if(current==job)current=null;
    }
    private void cancel(Job job,String reason){
        if(job.evaluation!=null){executor.cancelEvaluation(job.evaluation);job.evaluation=null;}
        if(job.cancelled==null)job.cancelled=reason;
        if(!job.registered){reply(job,false,reason);retire(job);return;}
        var result=executor.abortQueuedIntent(new EventInstanceId(job.event),Optional.ofNullable(job.proposal),journal,job.cancelled);
        last="CANCELLING "+result.status()+" "+result.detail();
        switch(result.status()){
            case DURABLE->{reply(job,false,result.detail());retire(job);}
            case DRIVER_INVOKED->{job.started=true;job.isolated=true;current=null;replies.put(job.event,new DirectorNativeChainGameplay.Launch(false,"EVENT_RECOVERY_REQUIRED",true));last="ISOLATED_EVENT_DRIVER_INVOKED";}
            case REJECTED->{frozen=true;last="RECOVERY_REQUIRED_ABORT_UNCERTAIN";}
            default->{}
        }
    }
    private long seed(Job job){
        var name=world.value()+"/catalogue-runtime/"+session.bootEpoch().value()+"/"+
            Math.addExact(ordinal,1)+"/"+(job.event==null?"automatic":job.event);
        ordinal=Math.addExact(ordinal,1);
        var id=UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return id.getMostSignificantBits()^id.getLeastSignificantBits();
    }
    /** External authority/failure fences must cancel CPU even when the outer
     * production owner will no longer call pollEvaluation. Keep jobs, journal,
     * submitted audits and ACK/abort drain state intact. */
    public void revokeSpeculativeCpu(String reason){
        actor();automatic=false;frozen=true;
        try{executor.revokeSpeculativeCpu();}
        finally{
            var owned=new LinkedHashSet<Job>(jobs.values());if(current!=null)owned.add(current);
            for(var job:owned)job.evaluation=null;
            decisionWorker.close();last=Objects.requireNonNull(reason);
        }
    }
    /** Must be called AFTER chain pending-consent validation, never before it. */
    public void pollAfterChains(){
        actor();if(closed)return;
        if(!session.equals(store.session())||store.readOnlySafe()){revokeSpeculativeCpu("J04_RECOVERY_REQUIRED");return;}
        if(frozen){
            // Drain only an already-requested cancellation. Never admit or invoke while frozen.
            try{
                if(session.equals(store.session())&&!store.readOnlySafe()&&current!=null&&
                    !current.started&&current.cancelled!=null)cancel(current,current.cancelled);
            }catch(RuntimeException failure){
                last="RECOVERY_REQUIRED_ABORT_DRAIN "+failure;
                System.out.println("DIRECTOR_CATALOGUE_ABORT_DRAIN_RECOVERY_REQUIRED "+failure);
            }
            return;
        }
        try{
            if(!session.equals(store.session())||store.readOnlySafe()){revokeSpeculativeCpu("J04_RECOVERY_REQUIRED");return;}
            // Audits stay serialized; every already-confirmed ready plan is offered
            // before the next global arbitration poll, independent of current's order.
            for(var queued:List.copyOf(jobs.values()))if(!queued.started&&queued.cancelled==null&&queued.submitted&&queued.proposal!=null&&invalid(queued)==null)
                executor.stageEnvelope(queued.proposal,journal,queued.parent,()->queued.cancelled==null&&!queued.started&&invalid(queued)==null);
            for(var active:List.copyOf(jobs.values()))if(active.started){
                var progress=executor.progress(new EventInstanceId(active.event));
                if(progress.isPresent()&&(progress.get().stage()==DirectorNativeCatalogueExecution.EventStage.COMPLETED||progress.get().stage()==DirectorNativeCatalogueExecution.EventStage.ABORTED))retire(active);
            }
            if(current!=null&&current.started)current=null;
            if(current==null){
                current=jobs.values().stream().filter(j->!j.started&&(j.proposal==null||!j.submitted||clock.getAsLong()>=j.nextTry))
                    .sorted(Comparator.<Job>comparingInt(j->j.parent.isPresent()?1:0).reversed().thenComparingLong(j->j.deadline)).findFirst().orElse(null);
                if(current==null){
                    if(!automatic||!enabled.getAsBoolean()||transitionPending.getAsBoolean()||
                        clock.getAsLong()<nextAutomatic)return;
                    current=new Job();current.region=Optional.empty();current.parent=Optional.empty();
                    current.automatic=true;current.deadline=clock.getAsLong()+REQUEST_TICKS;
                    nextAutomatic=clock.getAsLong()+RETRY_TICKS;
                }
            }
            var job=current;
            if(job.started){
                var progress=executor.progress(new EventInstanceId(job.event));
                if(progress.isEmpty()){last="WAIT_REAL_TERMINAL_FACT "+job.event;return;}
                last=progress.get().stage()+" "+progress.get().detail();
                if(progress.get().stage()==DirectorNativeCatalogueExecution.EventStage.COMPLETED||
                    progress.get().stage()==DirectorNativeCatalogueExecution.EventStage.ABORTED)retire(job);
                return;
            }
            if(job.proposal!=null&&!job.submitted){
                if(job.recording==null||!job.recording.isDone()){last="WAIT_RECORDED_INPUT_WORKER";return;}
                if(!session.equals(store.session())||store.readOnlySafe())throw new IllegalStateException("decision archive session revoked");
                // Revocation/expiry cancels later admission. Preserve this pure, already
                // calculated decision audit so the existing exact-ACK abort proof remains valid.
                var noLongerAdmissible=invalid(job);if(noLongerAdmissible!=null&&job.cancelled==null)job.cancelled=noLongerAdmissible;
                var archive=job.recording.getNow(null);var receipt=journal.submitRecorded(job.proposal,archive.getKey(),archive.getValue());
                if(receipt.state()==DirectorDecisionJournal.State.REJECTED||receipt.state()==DirectorDecisionJournal.State.UNAVAILABLE)
                    throw new IllegalStateException("recorded decision submission refused");
                job.submitted=true;
                if(job.previousNoPlan!=null){executor.retire(job.previousNoPlan.proposalId());journal.retireTerminal(job.previousNoPlan.proposalId());job.previousNoPlan=null;}
            }
            if(job.proposal!=null){
                var ack=journal.poll(job.proposal.proposalId());
                if(ack.state()==DirectorDecisionJournal.State.WAITING){last="WAIT_EXACT_DECISION_ACK";return;}
                if(!ack.mayProceedToAdmission()){frozen=true;last="DECISION_ACK_UNCERTAIN";return;}
                var invalid=invalid(job);if(invalid!=null){cancel(job,invalid);return;}
                if(job.proposal.selectedPlan().isEmpty()){
                    last="NO_PLAN_WAIT_REAL_CONDITIONS";
                    if(job.automatic){retire(job);return;}
                    if(clock.getAsLong()<job.nextTry)return;
                    // Keep the latest genuinely durable NO_PLAN until a replacement audit
                    // is submitted, so cancellation never loses its exact proof.
                }else{
                    if(inputs.apply(0).isEmpty()){
                        // The common J01/J04 cut is temporarily unavailable to every
                        // new evaluation. Retain the already-audited lane and retry on
                        // the next actor tick: a 200-tick retry can stay phase-locked
                        // behind fresh automatic WORLD writes at that same cadence.
                        if(!last.equals("WAIT_FRESH_CONTEXT_BEFORE_ADMISSION"))System.out.println("DIRECTOR_NATIVE_CATALOGUE_WAIT_FRESH_CONTEXT event="+job.event+" retry=NEXT_ACTOR_TICK audit_ack=DURABLE");
                        last="WAIT_FRESH_CONTEXT_BEFORE_ADMISSION";return;
                    }
                    final DirectorNativeCatalogueExecution.Dispatch dispatch;
                    try{dispatch=executor.beginAudited(job.proposal,journal,job.parent);}
                    catch(RuntimeException failure){
                        System.err.println("DIRECTOR_NATIVE_DISPATCH_FAILURE event="+job.event+" definition="+job.definition+" cause="+failure);failure.printStackTrace();
                        // A provider failure cannot imply no effect. The existing executor
                        // distinguishes pre-invocation aborts from a physically uncertain event.
                        cancel(job,"NATIVE_DISPATCH_FAILED_"+failure.getClass().getSimpleName());return;
                    }
                    job.dispatch=dispatch;
                    last=dispatch.stage()+" "+dispatch.detail();
                    if(dispatch.stage()==DirectorNativeCatalogueExecution.Stage.STARTED||
                        dispatch.stage()==DirectorNativeCatalogueExecution.Stage.ALREADY_STARTED){
                        job.started=true;reply(job,true,dispatch.detail());current=null;return;
                    }
                    if(dispatch.stage()==DirectorNativeCatalogueExecution.Stage.REJECTED){
                        System.out.println("DIRECTOR_NATIVE_CATALOGUE_DRIVER_REJECTED event="+dispatch.eventId()+" detail="+dispatch.detail());
                        cancel(job,"NATIVE_DRIVER_REJECTED");return;
                    }
                    // Audited admission is actor-owned metadata polling, not a new
                    // decision. A full RETRY_TICKS sleep can equal the reservation
                    // lease and expire it before this same plan reaches its driver.
                    // Keep the original audit/intent and poll again next actor tick.
                    job.nextTry=clock.getAsLong();current=null;return;
                }
            }
            var invalid=invalid(job);if(invalid!=null){cancel(job,invalid);return;}
            if(transitionPending.getAsBoolean()||clock.getAsLong()<job.nextTry)return;
            if(job.evaluation==null){
                var captured=inputs.apply(seed(job));if(captured.isEmpty()){last="WAIT_ACTUAL_J01_J04_CONTEXT";return;}
                job.evaluation=executor.beginEvaluation(captured.get(),Optional.ofNullable(job.definition),
                    Optional.ofNullable(job.event).map(EventInstanceId::new),job.region,
                    job.targets.isEmpty()?(job.actor==null?Set.of():Set.of(new PlayerId(job.actor))):job.targets,decisionWorker);
            }
            var frozen=job.evaluation.frozenInput();
            long maxAge=frozen.config().values().get("scheduler.snapshot_max_age_ticks").value().longValueExact();
            if(clock.getAsLong()-frozen.context().capturedTick().value()>maxAge){
                executor.cancelEvaluation(job.evaluation);job.evaluation=null;
                last="WAIT_FRESH_CONTEXT_AFTER_CPU_WORK_EXPIRY";return;
            }
            var computed=executor.pollEvaluation(job.evaluation,clock.getAsLong(),()->globalBudget.get().map(DirectorTickWorkBudget::takeObservationOperation).orElse(true));
            if(computed.isEmpty()){last="WAIT_DECISION_CPU_WORK stage="+job.evaluation.stage()+" worker_queue="+decisionWorker.queued();return;}
            var previousNoPlan=job.proposal;var proposal=computed.orElseThrow();job.evaluation=null;
            if(job.automatic&&proposal.selectedPlan().isPresent()){
                var plan=proposal.selectedPlan().orElseThrow();job.event=plan.effects().getFirst().instanceId().value();
                job.definition=plan.definition().id();
                job.actor=plan.roles().roles().values().stream().flatMap(Collection::stream).findFirst().orElseThrow().value();
                var player=server.getPlayerList().getPlayer(job.actor);job.client=player==null?null:client(player).orElse(null);
                var regions=plan.scopes().scopes().stream().filter(s->s.kind()==ScopeKind.REGION).toList();
                job.region=regions.size()==1?Optional.of(new RegionId(regions.getFirst().id())):Optional.empty();
                register(job);jobs.put(job.event,job);
            }
            job.proposal=proposal;job.nextTry=clock.getAsLong()+RETRY_TICKS;
            job.submitted=false;job.previousNoPlan=previousNoPlan;
            // The full host supplies its bounded archive worker in declared QA too.
            // Preserve the real provider mode; legacy worker-free test hosts keep audit-only behavior.
            if(archiveWorker!=null){
                var frozenInput=executor.capturedInput(proposal.proposalId()).orElseThrow();
                var snapshots=executor.capturedPlanningSnapshots(proposal.proposalId());var effects=executor.capturedEffects(proposal.proposalId());
                var recordingMode=executor.mode();
                job.recording=CompletableFuture.supplyAsync(()->RecordedDecisionArchive.record(frozenInput,proposal,snapshots,effects,session,recordingMode),archiveWorker);
            }else{
                var receipt=journal.submit(proposal);job.submitted=true;
                if(receipt.state()==DirectorDecisionJournal.State.REJECTED||receipt.state()==DirectorDecisionJournal.State.UNAVAILABLE)
                    throw new IllegalStateException("real decision audit submission refused");
                if(previousNoPlan!=null){executor.retire(previousNoPlan.proposalId());journal.retireTerminal(previousNoPlan.proposalId());job.previousNoPlan=null;}
            }
            last="DECISION_SUBMITTED "+proposal.proposalId().value();
        }catch(RuntimeException failure){
            try{revokeSpeculativeCpu("RECOVERY_REQUIRED "+failure.getClass().getSimpleName()+" "+failure.getMessage());}
            catch(RuntimeException releaseFailure){failure.addSuppressed(releaseFailure);System.out.println("DIRECTOR_CPU_CAPTURE_DISCARD_REQUIRES_RETRY "+releaseFailure);}
            System.out.println("DIRECTOR_CATALOGUE_RUNTIME_RECOVERY_REQUIRED "+failure);
        }
    }
    /** Capture stop targets on the actor. IDs returned for UI/head readback are
     * real EVENT heads only; no-head queued intents remain in the internal
     * no-invocation barrier until the existing abort ACK retires each job. */
    public Set<EventInstanceId> requestStopQueue(Optional<EventInstanceId> target){
        actor();if(target.isEmpty()){automatic=false;stopFence=true;}
        var captured=new LinkedHashSet<Job>(jobs.values());if(current!=null)captured.add(current);
        var heads=new HashSet<EventInstanceId>();
        for(var job:captured){
            if(job.started||target.isPresent()&&(job.event==null||!target.get().value().equals(job.event)))continue;
            if(job.cancelled==null)job.cancelled="ADMIN_STOP_BEFORE_DRIVER";
            if(job.evaluation!=null){executor.cancelEvaluation(job.evaluation);job.evaluation=null;}
            if(job.event==null){if(job==current)stopUnidentifiedCurrent=true;continue;}
            stopBarrier.add(job.event);var event=new EventInstanceId(job.event);
            var query=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.EVENT,job.event)),new Sequence(0),Optional.empty(),1);
            var read=store.view(query);ReadContracts.validate(read,query,session);
            if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().aggregates().stream().anyMatch(row->row.state() instanceof EventAggregate))heads.add(event);
        }
        return Set.copyOf(heads);
    }
    public boolean stopQueueComplete(Set<EventInstanceId> targets){
        actor();stopBarrier.removeIf(id->!jobs.containsKey(id)&&(current==null||!id.equals(current.event)));
        if(stopUnidentifiedCurrent){if(current!=null&&!current.started)return false;stopUnidentifiedCurrent=false;}
        // Retirement is performed only by the existing exact-ACK abort path.
        return stopBarrier.isEmpty();
    }
    public Optional<DirectorNativeChainGameplay.Launch> adminProgress(RuntimeAdminKey key){
        return pendingStart(UUID.nameUUIDFromBytes(("admin-trigger/"+key.actorSession().value()+"/"+key.requestId().value()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    /** Metadata/cleanup takes precedence over speculative CPU capture. Preserve
     * the original queued intent and any durable earlier NO_PLAN audit. */
    public void deferEvaluationForMetadata(){
        actor();if(current!=null&&current.evaluation!=null){executor.cancelEvaluation(current.evaluation);current.evaluation=null;last="WAIT_METADATA_BEFORE_NEW_CPU_CAPTURE";}
    }
    public boolean failedSafe(){actor();return frozen;}
    public boolean worldWriterIdle(){
        actor();return (current==null||current.evaluation==null)&&(current==null||current.proposal==null||current.submitted&&
            journal.poll(current.proposal.proposalId()).mayProceedToAdmission());
    }
    /** Called only after the canonical RESUME control has its durable WORLD ACK.
     * Health recovery alone leaves the stop fence closed. Manual admission may
     * reopen while automatic selection remains disabled in a declared QA run.
     */
    public boolean resumeAdmissionAfterCommittedControl(){
        actor();if(frozen||!enabled.getAsBoolean())return false;
        boolean reopened=stopFence;stopFence=false;
        if(reopened)System.out.println("DIRECTOR_NATIVE_CATALOGUE_EXPLICIT_RESUME_ADMISSION_REOPENED automatic="+automatic);
        return true;
    }
    public String automatic(boolean run){
        actor();if(run&&(!enabled.getAsBoolean()||frozen))return "Catalogue non actif.";
        if(run)stopFence=false;automatic=run;return "Selection automatique="+run+"; native provenance preserved.";
    }
    public String status(){actor();return "CATALOGUE_RUNTIME "+last+" queued="+jobs.size()+" automatic_lab="+automatic+" frozen="+frozen;}
    @Override public void close(){
        actor();if(closed)return;
        try{revokeSpeculativeCpu("CLOSED");}finally{closed=true;}
    }
}
