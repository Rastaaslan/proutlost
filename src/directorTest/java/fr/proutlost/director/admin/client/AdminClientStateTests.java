package fr.proutlost.director.admin.client;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Standalone JDK 21 tests against the frozen contracts JAR; no gameplay testkit. */
public final class AdminClientStateTests {
    private static int checks;
    private static final SessionId SESSION = new SessionId(new UUID(0, 1));
    private static final RequestId REQUEST = new RequestId(new UUID(0, 2));
    private static final SessionIdentity NETWORK = new SessionIdentity(new WorldId(new UUID(0, 3)),
            new Epoch(1), new Epoch(1), new DimensionId("minecraft:overworld"), new PlayerId(new UUID(0, 4)));

    public static void main(String[] args) {
        pages();
        commands();
        queryFailures();
        transportFailure();
        modifyingTransportFailure();
        staleControlRefusal();
        readOnlyCommandTransportFailure();
        readOnlyNativePayloads();
        readOnlyF8Menu();
        resultNavigation();
        mutationInspectionNavigationAndDetails();
        System.out.println("PASS AdminClientStateTests: " + checks + " assertions; client rendering/network NOT_RUN");
    }

    /** A network write may have reached J04 even if local send throws. */
    private static void modifyingTransportFailure() {
        var state=new AdminCommandState(2);state.reset(SESSION);
        var cmd=new AdminCommand(SESSION,REQUEST,new Revision(7),
            AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));
        var uncertain=AdminCommandDispatch.send(state,cmd,ignored->{
            throw new IllegalStateException("transport delivery unknown");
        });
        check(uncertain==AdminCommandDispatch.Outcome.DELIVERY_UNCERTAIN,
            "modifying send failure is uncertain, never a fake refusal");
        check(state.result(REQUEST).orElseThrow().status()==AdminStatus.PENDING,
            "uncertain write retains PENDING and exact request ID");
        check(!state.dismiss(REQUEST),
            "unconfirmed write cannot be dismissed before authoritative result");
        var submitted=new ArrayList<AdminCommand>();
        check(AdminCommandDispatch.send(state,cmd,submitted::add)
            ==AdminCommandDispatch.Outcome.SENT_UNCONFIRMED,
            "manual identical request retry is possible");
        check(submitted.equals(List.of(cmd))&&state.requestIds().equals(List.of(REQUEST)),
            "retry uses same payload and id, never invents a second operation");
        var altered=new AdminCommand(SESSION,REQUEST,new Revision(8),
            AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));
        check(AdminCommandDispatch.send(state,altered,submitted::add)
            ==AdminCommandDispatch.Outcome.NOT_ACCEPTED,
            "different payload cannot reuse an uncertain request ID");
        check(submitted.size()==1,"rejected conflicting retry not sent");
        var result=new AdminCommandResult(REQUEST,AdminStatus.SUCCEEDED,
            List.of(),Optional.of(new Revision(9)),Optional.empty());
        check(state.receive(SESSION,result)&&state.result(REQUEST).orElseThrow().equals(result),
            "only authoritative receipt ends uncertain write");
        state.close();
        check(AdminCommandDispatch.send(state,cmd,submitted::add)
            ==AdminCommandDispatch.Outcome.NOT_ACCEPTED&&submitted.size()==1,
            "closed session never transmits retained command");
    }

    /** Read-only F8 sends must not consume 64 pending slots if local send fails;
     * a potentially effective modification must never use this escape hatch. */
    private static void readOnlyCommandTransportFailure() {
        var state=new AdminCommandState(1);state.reset(SESSION);
        var read=new AdminCommand(SESSION,REQUEST,new Revision(7),
            AdminOperation.STATUS,new EmptyAdminPayload());
        var failed=AdminCommandDispatch.sendReadOnly(state,read,ignored->{
            throw new IllegalStateException("socket failure after possible delivery");
        });
        check(failed==AdminCommandDispatch.Outcome.READ_DELIVERY_FAILED,
            "read-only send exception has explicit non-authoritative outcome");
        check(state.requestIds().isEmpty()&&state.result(REQUEST).isEmpty(),
            "read-only transport fault frees bounded F8 slot without fake receipt");
        check(!state.receive(SESSION,new AdminCommandResult(REQUEST,
            AdminStatus.SUCCEEDED,List.of(),Optional.of(new Revision(7)),
            Optional.empty())),"late response to abandoned read ignored");
        var retry=new AdminCommand(SESSION,new RequestId(new UUID(0,71)),
            new Revision(7),AdminOperation.STATUS,new EmptyAdminPayload());
        var submitted=new ArrayList<AdminCommand>();
        check(AdminCommandDispatch.sendReadOnly(state,retry,submitted::add)
            ==AdminCommandDispatch.Outcome.SENT_UNCONFIRMED,
            "explicit fresh read retry is possible");
        check(submitted.equals(List.of(retry))&&
            state.result(retry.requestId()).orElseThrow().status()==AdminStatus.PENDING,
            "successful transport remains pending until authoritative read reply");
        var pause=new AdminCommand(SESSION,new RequestId(new UUID(0,72)),
            new Revision(7),AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));
        try {
            AdminCommandDispatch.sendReadOnly(state,pause,submitted::add);
            throw new AssertionError("mutating command passed through read-only dispatch");
        } catch(IllegalArgumentException expected){
            check(true,"read-only dispatcher refuses mutating operation");
        }
        check(submitted.size()==1,"mutation never sent by read-only dispatcher");
        var writes=new AdminCommandState(1);writes.reset(SESSION);
        check(writes.begin(pause)&&!writes.abandonFailedRead(pause.requestId()),
            "cannot abandon possibly effective J04 write");
        check(writes.result(pause.requestId()).orElseThrow().status()==AdminStatus.PENDING,
            "failed world write must remain pending");
        var success=new AdminCommandResult(retry.requestId(),AdminStatus.SUCCEEDED,
            List.of(),Optional.of(new Revision(8)),Optional.empty());
        check(state.receive(SESSION,success)&&
            !state.abandonFailedRead(retry.requestId()),
            "already completed read cannot be retrospectively abandoned");
        check(state.requestIds().equals(List.of(retry.requestId())),
            "successful read receipt remains in history");
    }

    private static void resultNavigation() {
        var state=new AdminCommandState(4);state.reset(SESSION);
        var decisionA=new DecisionId(new UUID(0,100));var decisionB=new DecisionId(new UUID(0,200));
        var current=new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),AdminPanel.DECISIONS,new DecisionFilter(Optional.of(decisionA)),Optional.of("page-A"),Optional.of(new Revision(1)),50);
        var replay=new AdminCommand(SESSION,new RequestId(UUID.randomUUID()),new Revision(1),AdminOperation.REPLAY,new ReplayPayload(decisionB,false));
        var context=new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(NETWORK.worldId(),Set.of()),new Tick(0),Map.of(),
                new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),Set.of(),Set.of(),HealthMode.NORMAL);
        var fixtureB=new ResourceId("test:fixture_b");
        var simulation=new AdminCommand(SESSION,new RequestId(UUID.randomUUID()),new Revision(1),AdminOperation.SIMULATE,new SimulationPayload(fixtureB,context,true));
        check(state.begin(replay)&&state.begin(simulation),"concurrent replay and simulation retained");
        check(state.resultQuery(replay.requestId(),REQUEST,50).isEmpty(),"pending result has no navigation");
        var simulationRef=new ScopeRef(ScopeKind.WORLD,NETWORK.worldId().value());
        var simulationResult=new AdminCommandResult(simulation.requestId(),AdminStatus.SUCCEEDED,List.of(),Optional.of(new Revision(3)),Optional.of(simulationRef));
        check(state.receive(SESSION,simulationResult),"newer command completes first");
        var simulated=state.resultQuery(simulation.requestId(),new RequestId(UUID.randomUUID()),50).orElseThrow();
        check(simulated.panel()==AdminPanel.LABORATORY&&simulated.filter().equals(new LaboratoryFilter(Optional.of(fixtureB))),"simulation opens its fixture, not current panel or fixture");
        var replayRef=new ScopeRef(ScopeKind.WORLD,NETWORK.worldId().value());
        var replayResult=new AdminCommandResult(replay.requestId(),AdminStatus.SUCCEEDED,List.of(),Optional.of(new Revision(2)),Optional.of(replayRef));
        check(state.receive(SESSION,replayResult),"older command completes later");
        var replayed=state.resultQuery(replay.requestId(),new RequestId(UUID.randomUUID()),50).orElseThrow();
        check(replayed.panel()==AdminPanel.DECISIONS&&replayed.filter().equals(new DecisionFilter(Optional.of(decisionB)))&&!replayed.filter().equals(current.filter()),"replay B while displaying A navigates B");
        check(replayed.cursor().isEmpty()&&replayed.expectedSnapshotRevision().isEmpty(),"new destination never reuses page A cursor or stamp");
        check(state.result(simulation.requestId()).orElseThrow().equals(simulationResult),"late replay cannot overwrite simulation reference");
        check(state.result(replay.requestId()).orElseThrow().resultRef().orElseThrow().equals(replayRef),"reference retained verbatim, world UUID not treated as decision UUID");
        check(state.requestIds().equals(List.of(replay.requestId(),simulation.requestId())),"history follows submission order despite completion order");
        check(state.receive(SESSION,replayResult)&&state.requestIds().size()==2,"duplicate completion does not duplicate history");
        check(!state.receive(new SessionId(UUID.randomUUID()),simulationResult),"foreign session completion ignored");
        var target=new ScopeRef(ScopeKind.REGION,new UUID(0,300));
        var inspect=new AdminCommand(SESSION,new RequestId(UUID.randomUUID()),new Revision(1),AdminOperation.REGION,new RegionInspectionPayload(new RegionId(new UUID(0,301))));
        state.begin(inspect);state.receive(SESSION,new AdminCommandResult(inspect.requestId(),AdminStatus.SUCCEEDED,List.of(),Optional.empty(),Optional.of(target)));
        var referenced=state.resultQuery(inspect.requestId(),new RequestId(UUID.randomUUID()),50).orElseThrow();
        check(referenced.filter().equals(new RegionFilter(Optional.of(new RegionId(target.id())),Set.of())),"scope reference is followed when directly queryable");
        check(state.dismiss(replay.requestId())&&state.resultQuery(replay.requestId(),REQUEST,50).isEmpty(),"explicit dismissal releases replay only");
        check(state.result(simulation.requestId()).isPresent(),"other terminal result survives dismissal");
        state.close();check(state.requestIds().isEmpty()&&state.resultQuery(simulation.requestId(),REQUEST,50).isEmpty(),"close fences result history");

        // A refused operation cannot pretend to have a navigable outcome,
        // even when REPLAY/SIMULATE/CHAIN have their target in the payload.
        var refused=new AdminCommandState(3);refused.reset(SESSION);
        var chain=new AdminCommand(SESSION,new RequestId(UUID.randomUUID()),
            new Revision(1),AdminOperation.CHAIN,
            new ChainInspectionPayload(new ChainId(new UUID(0,302))));
        check(refused.begin(replay)&&refused.begin(simulation)&&refused.begin(chain),
            "refused-outcome navigation setup");
        check(refused.receive(SESSION,new AdminCommandResult(replay.requestId(),
            AdminStatus.REJECTED,List.of(fr.proutlost.director.api.error.ReasonCode.STALE_CONTEXT),
            Optional.empty(),Optional.empty())) &&
            refused.resultQuery(replay.requestId(),REQUEST,50).isEmpty(),
            "rejected replay must not navigate its requested decision");
        check(refused.receive(SESSION,new AdminCommandResult(simulation.requestId(),
            AdminStatus.FAILED_SAFE,List.of(fr.proutlost.director.api.error.ReasonCode.INTERNAL_FAULT),
            Optional.empty(),Optional.empty())) &&
            refused.resultQuery(simulation.requestId(),REQUEST,50).isEmpty(),
            "failed-safe simulation must not navigate its unexecuted fixture");
        check(refused.receive(SESSION,new AdminCommandResult(chain.requestId(),
            AdminStatus.REJECTED,List.of(fr.proutlost.director.api.error.ReasonCode.NO_TARGET),
            Optional.empty(),Optional.empty())) &&
            refused.resultQuery(chain.requestId(),REQUEST,50).isEmpty(),
            "rejected chain inspection must not navigate the payload UUID");
    }

    private static void queryFailures() {
        var state=new AdminPageState();state.reset(NETWORK);
        for(var reason:List.of(fr.proutlost.director.api.error.ReasonCode.STALE_CONTEXT,
                fr.proutlost.director.api.error.ReasonCode.RATE_LIMITED,
                fr.proutlost.director.api.error.ReasonCode.AUTH_DENIED)) {
            var request=new RequestId(UUID.randomUUID());
            var query=new AdminQuery(SESSION,request,AdminPanel.WORLD,new WorldFilter(),Optional.of("stale"),Optional.of(new Revision(7)),1);
            state.begin(query);
            var other=new SessionIdentity(NETWORK.worldId(),NETWORK.bootEpoch(),new Epoch(99),NETWORK.dimension(),NETWORK.playerId());
            check(!state.reject(other,request,reason,10,40)&&state.pending(),"foreign session failure ignored "+reason);
            check(!state.reject(NETWORK,REQUEST,reason,10,40)&&state.pending(),"foreign request failure ignored "+reason);
            check(state.reject(NETWORK,request,reason,10,40)&&!state.pending()&&state.snapshot().isEmpty(),"rejection clears pending "+reason);
            check(!state.needsQuery(),"no automatic retry storm "+reason);
            check(state.failureReason().orElseThrow()==reason,"correlated rejection reason available to screen");
            check(!state.canRetry(49)&&state.canRetry(50),"retry delayed by configured refresh interval");
            check(!state.receive(NETWORK,snapshot(request,7,List.of())),"late success after failure ignored");
            var retry=new RequestId(UUID.randomUUID());
            state.begin(new AdminQuery(SESSION,retry,AdminPanel.WORLD,new WorldFilter(),Optional.empty(),Optional.empty(),1));
            check(!state.reject(NETWORK,request,reason,10,40)&&state.pending(),"old failure cannot cancel retry");
            check(state.receive(NETWORK,snapshot(retry,8,List.of())),"fresh explicit retry succeeds");
        }
    }

    /** Read-only C2S transmission faults must terminate PENDING locally;
     * an uncertain write command is intentionally NOT handled by this fence. */
    private static void transportFailure() {
        var page=new AdminPageState();page.reset(NETWORK);
        var broken=new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),
            AdminPanel.WORLD,new WorldFilter(),Optional.empty(),Optional.empty(),1);
        check(!AdminQueryDispatch.send(page,NETWORK,broken,q->{
            throw new IllegalStateException("private socket internals");
        },10,40),"failed read dispatch returns false");
        check(!page.pending()&&!page.needsQuery()&&page.snapshot().isEmpty(),
            "failed read dispatch cannot hang F8 or requery automatically");
        check(page.failureReason().orElseThrow()==fr.proutlost.director.api.error.ReasonCode.INTERNAL_FAULT,
            "transport failure normalized without leaking exception text");
        check(!page.canRetry(49)&&page.canRetry(50),
            "failed dispatch obeys bounded explicit retry clock");
        check(!page.receive(NETWORK,snapshot(broken.requestId(),1,List.of())),
            "late reply cannot revive failed read");
        var retry=new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),
            AdminPanel.WORLD,new WorldFilter(),Optional.empty(),Optional.empty(),1);
        var submitted=new ArrayList<AdminQuery>();
        check(AdminQueryDispatch.send(page,NETWORK,retry,submitted::add,50,40)
            &&submitted.equals(List.of(retry))&&page.pending(),
            "explicit retry submits a fresh correlated request");
        check(page.receive(NETWORK,snapshot(retry.requestId(),2,List.of())),
            "successful retry restores F8 read page");
        check(page.failureReason().isEmpty(),"successful retry clears prior failure");
    }

    /** The real gateway's F8 payload callback is a safe typed read-only set.
     * Commands which could mutate world state remain explicitly disabled.
     */
    private static void readOnlyNativePayloads() {
        String id=new UUID(0, 34).toString();
        for(var op:List.of(AdminOperation.STATUS,AdminOperation.EVENT_LIST)){
            check(AdminClientReadPayloads.supported(op),"read-only action supported "+op);
            AdminPayload p=AdminClientReadPayloads.parse(op,"  ");
            check(p instanceof EmptyAdminPayload && AdminContracts.matches(op,p),
                "read-only empty payload "+op);
        }
        var typed=Map.<AdminOperation,Class<? extends AdminPayload>>of(
            AdminOperation.EVENT_INSPECT,EventInspectionPayload.class,
            AdminOperation.MUTATION_INSPECT,MutationInspectionPayload.class,
            AdminOperation.REGION,RegionInspectionPayload.class,
            AdminOperation.PLAYER,PlayerInspectionPayload.class,
            AdminOperation.CHAIN,ChainInspectionPayload.class);
        for(var entry:typed.entrySet()){
            var op=entry.getKey();
            var payload=AdminClientReadPayloads.parse(op,"  "+id+"  ");
            check(entry.getValue().isInstance(payload)&&AdminContracts.matches(op,payload),
                "typed inspection routes to declared contract "+op);
            try {
                AdminClientReadPayloads.parse(op,id+" "+id);
                throw new AssertionError("extra args accepted "+op);
            } catch(IllegalArgumentException expected) {
                check(true,"extra args refused "+op);
            }
            try {
                AdminClientReadPayloads.parse(op,"NOT-A-UUID");
                throw new AssertionError("invalid id accepted "+op);
            } catch(IllegalArgumentException expected) {
                check(true,"bad id refused "+op);
            }
        }
        for(var control:List.of(AdminOperation.PAUSE,AdminOperation.RESUME)){
            var payload=AdminClientReadPayloads.parse(control,"");
            check(payload instanceof ControlPayload&&AdminContracts.matches(control,payload),"typed native control "+control);
            check(!AdminClientReadPayloads.readOnly(control),"control cannot use failed-read cancellation "+control);
        }
        for(var forbidden:List.of(AdminOperation.RECORD_OBSERVATION,AdminOperation.DEBUG,AdminOperation.UI)){
            check(!AdminClientReadPayloads.supported(forbidden),"unlisted action disabled "+forbidden);
            formRejected(()->AdminClientReadPayloads.parse(forbidden,""),"unlisted action denied "+forbidden);
        }
        var forms=Map.ofEntries(
            Map.entry(AdminOperation.DRAIN,""),
            Map.entry(AdminOperation.INTENSITY,"test:approved"),
            Map.entry(AdminOperation.STOP,id),
            Map.entry(AdminOperation.STABILIZE,id+" stabilize"),
            Map.entry(AdminOperation.RESCUE,"self"),
            Map.entry(AdminOperation.REPORT,"technical test report"),
            Map.entry(AdminOperation.TRIGGER,"test:event region "+id+" targets "+id),
            Map.entry(AdminOperation.REPLAY,id+" without_ai"),
            Map.entry(AdminOperation.AUDIT_EXPORT,id));
        for(var entry:forms.entrySet()){
            var payload=AdminClientReadPayloads.parse(entry.getKey(),entry.getValue());
            check(AdminClientReadPayloads.supported(entry.getKey())&&AdminContracts.matches(entry.getKey(),payload),
                "actual typed form contract "+entry.getKey());
        }
        var target=new UUID(0,35).toString();
        var trigger=(TriggerPayload)AdminClientReadPayloads.parse(AdminOperation.TRIGGER,"test:event region "+id+" targets "+id+","+target);
        check(trigger.targets().equals(Set.of(PlayerId.parse(id),PlayerId.parse(target)))&&trigger.temporaryGroup().isEmpty(),
            "trigger uses explicit UUID set; no fake names or temporary social group");
        check(((StopPayload)AdminClientReadPayloads.parse(AdminOperation.STOP,"all")).all(),"stop all exact selector");
        check(((RescuePayload)AdminClientReadPayloads.parse(AdminOperation.RESCUE,id)).target().equals(Optional.of(PlayerId.parse(id))),"rescue exact UUID");
        var tension=(TensionPayload)AdminClientReadPayloads.parse(AdminOperation.TENSION,"TENSE 20",new Tick(10));
        check(tension.duration().value()==20&&tension.expiresTick().value()==30,"expiry derives only from real server capture");
        formRejected(()->AdminClientReadPayloads.parse(AdminOperation.TENSION,"TENSE 20"),"no estimated client tick");
        formRejected(()->AdminClientReadPayloads.parse(AdminOperation.TENSION,"TENSE 20",new Tick(Long.MAX_VALUE-1)),"expiry overflow refused");
        formRejected(()->AdminClientReadPayloads.parse(AdminOperation.TENSION,"TENSE -1",new Tick(10)),"negative duration refused");
        formRejected(()->AdminClientReadPayloads.parse(AdminOperation.TRIGGER,"test:event region "+id+" targets playerName"),"no guessed target name");
        formRejected(()->AdminClientReadPayloads.parse(AdminOperation.REPLAY,id+" arbitrary"),"closed replay option");
        formRejected(()->AdminClientReadPayloads.parse(AdminOperation.STABILIZE,id+" anything"),"closed stabilization suffix");
        check(AdminClientReadPayloads.supported(AdminOperation.SIMULATE)&&
            AdminClientReadPayloads.simulationFixture(" test:registered ").equals(new ResourceId("test:registered")),
            "simulation carries only strict registered server fixture selector");
        formRejected(()->AdminClientReadPayloads.simulationFixture("test:registered\nstop all"),"no arbitrary command/newline injection");
        formRejected(()->AdminClientReadPayloads.parse(AdminOperation.SIMULATE,"test:registered"),"never fabricate a client SnapshotBatch");
        check(AdminClientReadPayloads.readOnly(AdminOperation.REPLAY),"pure replay may release failed read delivery");
        for(var modifying:List.of(AdminOperation.DRAIN,AdminOperation.TENSION,AdminOperation.INTENSITY,AdminOperation.STOP,
                AdminOperation.STABILIZE,AdminOperation.RESCUE,AdminOperation.REPORT,AdminOperation.TRIGGER,AdminOperation.AUDIT_EXPORT))
            check(!AdminClientReadPayloads.readOnly(modifying),"uncertain action/file export retains exact request "+modifying);
        try {
            AdminClientReadPayloads.parse(AdminOperation.STATUS,"unexpected");
            throw new AssertionError("empty-only operation accepted arguments");
        } catch(IllegalArgumentException expected){check(true,"empty-only bound");}
        try {
            AdminClientReadPayloads.parse(AdminOperation.STATUS,"x".repeat(1001));
            throw new AssertionError("oversize payload accepted");
        } catch(IllegalArgumentException expected){check(true,"payload size bound");}
    }

    private static void formRejected(Runnable action,String label){
        try{action.run();throw new AssertionError(label);}catch(IllegalArgumentException|ArithmeticException expected){check(true,label);}
    }
    private static void readOnlyF8Menu() {
        var actual=EnumSet.noneOf(AdminOperation.class);
        var nativeReadOnly=new AdminSnapshot(REQUEST,new Revision(7),new Tick(10),new Sequence(5),List.of(),Optional.empty(),Set.of());
        var forgedMutating=new AdminSnapshot(REQUEST,new Revision(7),new Tick(10),new Sequence(5),List.of(),Optional.empty(),Set.of(AdminOperation.TRIGGER));
        for(var panel:AdminPanel.values()){
            var ops=AdminClientOperationMenu.forPanel(panel);
            check(ops.size()==new HashSet<>(ops).size(),"no duplicate action on "+panel);
            for(var operation:ops){
                check(actual.add(operation),"one action belongs to one tab "+operation);
                check(AdminClientReadPayloads.supported(operation)&&AdminClientOperationMenu.available(panel,operation),"listed action has real typed form/route "+operation);
                boolean requires=AdminClientOperationMenu.requiresCapability(operation);
                check(AdminClientOperationMenu.canSend(panel,operation,Optional.of(nativeReadOnly))==!requires,
                    "legacy empty TEST_ONLY capabilities enable inspections only "+operation);
                var granted=new AdminSnapshot(REQUEST,new Revision(7),new Tick(10),new Sequence(5),List.of(),Optional.empty(),Set.of(operation));
                check(AdminClientOperationMenu.canSend(panel,operation,Optional.of(granted)),"advertised capability enables its exact existing action "+operation);
                check(!AdminClientOperationMenu.canSend(panel,operation,Optional.empty()),"no current page no action "+operation);
            }
            for(var operation:AdminOperation.values())if(!ops.contains(operation))
                check(!AdminClientOperationMenu.canSend(panel,operation,Optional.of(forgedMutating)),"capability cannot cross tab/unlisted operation "+operation);
        }
        check(actual.equals(EnumSet.of(AdminOperation.STATUS,AdminOperation.PAUSE,AdminOperation.RESUME,AdminOperation.DRAIN,
            AdminOperation.TENSION,AdminOperation.INTENSITY,AdminOperation.RESCUE,AdminOperation.REPORT,
            AdminOperation.EVENT_LIST,AdminOperation.EVENT_INSPECT,AdminOperation.MUTATION_INSPECT,AdminOperation.CHAIN,
            AdminOperation.STOP,AdminOperation.STABILIZE,AdminOperation.REGION,AdminOperation.PLAYER,
            AdminOperation.REPLAY,AdminOperation.AUDIT_EXPORT,AdminOperation.SIMULATE,AdminOperation.TRIGGER)),"exact existing backend action set");
        check(AdminClientOperationMenu.forPanel(AdminPanel.DECISIONS).equals(List.of(AdminOperation.REPLAY,AdminOperation.AUDIT_EXPORT)),"decision actions reachable");
        check(AdminClientOperationMenu.forPanel(AdminPanel.LABORATORY).equals(List.of(AdminOperation.SIMULATE,AdminOperation.TRIGGER)),"laboratory calculation/admission reachable");
        check(AdminClientOperationMenu.forPanel(AdminPanel.WORLD).containsAll(List.of(AdminOperation.TENSION,AdminOperation.INTENSITY,AdminOperation.DRAIN)),"world controls reachable");
        check(AdminClientOperationMenu.forPanel(AdminPanel.ACTIVE).containsAll(List.of(AdminOperation.STOP,AdminOperation.STABILIZE)),"actual native stop/stabilize reachable");
        var fixture=new ResourceId("test:registered");var scopes=new ScopeSet(NETWORK.worldId(),Set.of());
        var context=new SnapshotBatch(REQUEST,scopes,new Tick(10),Map.of(),
            new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),Set.of(),Set.of(),HealthMode.NORMAL);
        var hash=new Fingerprint("0".repeat(64));var id=new DecisionId(new UUID(0,91));
        var audit=new AuditDecision(id,context,new Revision(1),hash,new Revision(1),hash,List.of(),List.of(),1,List.of(),
            Optional.empty(),Optional.empty(),new RoleAssignments(Map.of()),scopes,List.of(),List.of(),List.of(),Optional.empty(),List.of(),
            Map.of(),List.of(),List.of(),List.of(),Map.of(),List.of());
        var proposal=new DecisionProposal(id,List.of(),Optional.empty(),audit,List.of());
        var row=new AdminRow(new ScopeRef(ScopeKind.WORLD,NETWORK.worldId().value()),new Revision(7),new Tick(10),new LaboratoryAdminData(fixture,proposal,List.of()));
        var result=new AdminSnapshot(REQUEST,new Revision(7),new Tick(10),new Sequence(5),List.of(row),Optional.empty(),Set.of(AdminOperation.SIMULATE));
        check(AdminClientReadPayloads.simulationResultMatches(result,fixture),"actual typed matching fixture result is readable");
        check(!AdminClientReadPayloads.simulationResultMatches(result,new ResourceId("test:other")),"other fixture never accepted as this simulation result");
        check(!AdminClientReadPayloads.simulationResultMatches(nativeReadOnly,fixture),"empty query never invents a simulation result");
        try{AdminClientOperationMenu.forPanel(null);throw new AssertionError("null panel");}catch(NullPointerException expected){check(true,"null panel closed");}
        try{AdminClientOperationMenu.available(AdminPanel.WORLD,null);throw new AssertionError("null op");}catch(NullPointerException expected){check(true,"null operation closed");}
    }

    /** Explicit mutation navigation must not follow the linked event scope as EVENT_INSPECT. */
    private static void mutationInspectionNavigationAndDetails() {
        var mutationId=new MutationId(new UUID(0,510));
        var eventId=new EventInstanceId(new UUID(0,511));
        var commandId=new RequestId(new UUID(0,512));
        var destinationId=new RequestId(new UUID(0,513));
        var state=new AdminCommandState(1);state.reset(SESSION);
        var command=new AdminCommand(SESSION,commandId,new Revision(7),
            AdminOperation.MUTATION_INSPECT,new MutationInspectionPayload(mutationId));
        check(state.begin(command)&&state.resultQuery(commandId,destinationId,1).isEmpty(),
            "mutation inspection has no navigable result before authoritative success");
        var actualEventScope=new ScopeRef(ScopeKind.EVENT,eventId.value());
        var success=new AdminCommandResult(commandId,AdminStatus.SUCCEEDED,List.of(),
            Optional.of(new Revision(8)),Optional.of(actualEventScope));
        check(state.receive(SESSION,success),"authoritative mutation inspection receipt accepted");
        var query=state.resultQuery(commandId,destinationId,1).orElseThrow();
        check(query.panel()==AdminPanel.ACTIVE&&query.filter().equals(new MutationFilter(mutationId))
                &&query.actorSession().equals(SESSION)&&query.requestId().equals(destinationId),
            "result navigation retains the selected MutationId rather than falling back to the genuine EVENT scope");
        check(query.cursor().isEmpty()&&query.expectedSnapshotRevision().isEmpty()
                &&state.result(commandId).orElseThrow().resultRef().equals(Optional.of(actualEventScope)),
            "mutation navigation preserves event receipt verbatim and starts a fresh typed lookup");

        var dimension=NETWORK.dimension();
        var position=new BlockPosition(dimension,1,64,2);
        var resource=new ResourceKey(OwnershipDomain.BLOCK,dimension,"1,64,2");
        var pre=BlobContent.capture(new ResourceId("test:mutation_before"),1,new byte[]{1});
        var post=BlobContent.capture(new ResourceId("test:mutation_after"),1,new byte[]{2});
        var token=new OwnerToken(new UUID(0,514));
        var mutation=new MutationRecord(new AggregateHeader(1,mutationId.value(),new Revision(1),new Sequence(5),new Tick(10)),
            mutationId,eventId,new SliceId(new UUID(0,515)),MutationState.ROLLED_BACK,
            new PrimitiveId("proutlost:trace"),1,new Footprint(dimension,Set.of(),Set.of(position),List.of()),
            pre.reference(),post.reference(),token,new Epoch(1),
            new ReadSet(Map.of(),Map.of(resource,new Epoch(1)),Map.of(resource,pre.reference().hash())),
            Set.of(),List.of(),Optional.empty(),new Sequence(5));
        var reused=new OwnershipRecord(new AggregateHeader(1,new UUID(0,516),new Revision(2),new Sequence(6),new Tick(20)),
            resource,Set.of(new OwnerToken(new UUID(0,517))),new Epoch(2),OwnershipDisposition.DIRECTOR_ACTIVE,
            new MutationId(new UUID(0,518)),post.reference().hash(),new Epoch(0),Optional.empty());
        var row=new AdminRow(actualEventScope,new Revision(1),new Tick(10),
            new MutationAdminData(mutation,new OwnershipView(Map.of(resource,reused)),Set.of(resource)));
        var lines=AdminDetails.lines(row,100);
        check(lines.stream().anyMatch(line->line.contains("currentOwnerIsSameMutation=false"))
                &&lines.stream().anyMatch(line->line.contains("PRE : "+pre.reference().codecId().value()+" / "+pre.reference().hash().value()))
                &&lines.stream().anyMatch(line->line.contains("POST : "+post.reference().codecId().value()+" / "+post.reference().hash().value())),
            "details show current owner reuse and the exact PRE/POST references without relabelling ownership");
        var absent=new AdminRow(actualEventScope,new Revision(1),new Tick(10),
            new MutationAdminData(mutation,new OwnershipView(Map.of()),Set.of(resource)));
        var absentLines=AdminDetails.lines(absent,100);
        check(absentLines.stream().anyMatch(line->line.contains("Propriétaire courant : ABSENT"))
                &&absentLines.stream().noneMatch(line->line.contains("currentOwnerIsSameMutation=true")),
            "an inspected resource with no current owner is displayed as ABSENT rather than an invented match");
    }

    private static void pages() {
        var state = new AdminPageState();
        check(state.needsQuery() && state.snapshot().isEmpty(), "initial state");
        state.reset(NETWORK);
        var filters = List.<AdminFilter>of(new WorldFilter(), new EventFilter(Optional.empty(), Set.of()),
                new RegionFilter(Optional.empty(), Set.of()), new DecisionFilter(Optional.empty()),
                new LaboratoryFilter(Optional.empty()));
        for (var panel : AdminPanel.values()) {
            var query = new AdminQuery(SESSION, REQUEST, panel, filters.get(panel.ordinal()),
                    Optional.empty(), Optional.empty(), 1);
            state.begin(query);
            check(state.pending() && state.snapshot().isEmpty(), "tab clears visible page " + panel);
            check(!state.receive(NETWORK, snapshot(new RequestId(UUID.randomUUID()), 7, List.of())), "foreign request");
            check(state.pending(), "foreign request does not consume current request");
            check(state.receive(NETWORK, snapshot(REQUEST, 7, List.of())), "empty page retains metadata " + panel);
            check(state.snapshot().orElseThrow().snapshotRevision().equals(new Revision(7)), "revision");
            check(state.age(new Tick(15)).orElseThrow() == 5, "age");
            check(state.age(new Tick(9)).isEmpty(), "clock mismatch is unknown age");
            check(!state.receive(NETWORK, snapshot(REQUEST, 6, List.of())), "late duplicate cannot regress revision");
        }
        var query = new AdminQuery(SESSION, REQUEST, AdminPanel.WORLD, new WorldFilter(),
                Optional.of("server-cursor"), Optional.of(new Revision(7)), 1);
        state.begin(query);
        check(!state.receive(NETWORK, snapshot(REQUEST, 8, List.of())), "cursor snapshot mismatch");
        check(state.needsQuery() && state.snapshot().isEmpty(), "mismatch forces requery");
        state.begin(query);
        var row = new AdminRow(new ScopeRef(ScopeKind.WORLD, NETWORK.worldId().value()), new Revision(7),
                new Tick(10), new PublicStatusData(HealthMode.NORMAL, false, Set.of()));
        check(!state.receive(NETWORK, snapshot(REQUEST, 7, List.of(row, row))), "query-specific page limit");
        state.begin(query);
        var nextNetwork = new SessionIdentity(NETWORK.worldId(), NETWORK.bootEpoch(), new Epoch(2),
                NETWORK.dimension(), NETWORK.playerId());
        check(!state.receive(nextNetwork, snapshot(REQUEST, 7, List.of())), "connection fence");
        state.reset(nextNetwork);
        check(!state.receive(NETWORK, snapshot(REQUEST, 7, List.of())), "old connection after reset");
        check(state.snapshot().isEmpty() && state.needsQuery(), "reset clears data");
        state.begin(query);
        check(state.receive(nextNetwork, snapshot(REQUEST, 7, List.of())), "new connection");
        state.invalidate();
        check(state.snapshot().isEmpty() && state.needsQuery(), "delta requery discards obsolete rows and cursor");
        state.close();
        check(!state.receive(nextNetwork, snapshot(REQUEST, 7, List.of())), "closed screen rejects late response");
    }

    private static AdminSnapshot snapshot(RequestId id, long revision, List<AdminRow> rows) {
        return new AdminSnapshot(id, new Revision(revision), new Tick(10), new Sequence(5), rows,
                Optional.empty(), Set.of(AdminOperation.STATUS));
    }

    private static void commands() {
        var state = new AdminCommandState(1);
        var command = new AdminCommand(SESSION, REQUEST, new Revision(7), AdminOperation.PAUSE,
                new ControlPayload(RunControl.PAUSED));
        check(!state.begin(command), "no session");
        state.reset(SESSION);
        check(state.begin(command), "begin command");
        check(state.result(REQUEST).orElseThrow().status() == AdminStatus.PENDING, "transmission is not success");
        check(state.begin(command), "identical retry");
        check(!state.begin(new AdminCommand(SESSION, REQUEST, new Revision(8), command.operation(), command.payload())),
                "conflicting retry");
        check(!state.begin(new AdminCommand(SESSION, new RequestId(UUID.randomUUID()), new Revision(7),
                command.operation(), command.payload())), "bounded state never evicts pending command");
        check(!state.dismiss(REQUEST), "pending cannot be dismissed");
        check(!state.receive(new SessionId(UUID.randomUUID()), result(AdminStatus.SUCCEEDED)), "old actor session");
        check(state.receive(SESSION, result(AdminStatus.DEFERRED)), "deferred");
        check(!state.dismiss(REQUEST), "deferred remains tracked");
        check(!state.receive(SESSION, result(AdminStatus.PENDING)), "late pending cannot regress deferred");
        check(state.receive(SESSION, result(AdminStatus.REJECTED)), "final refusal");
        check(!state.receive(SESSION, result(AdminStatus.SUCCEEDED)), "terminal outcome cannot be rewritten");
        check(state.receive(SESSION, result(AdminStatus.REJECTED)), "duplicate final result");
        check(state.dismiss(REQUEST) && state.result(REQUEST).isEmpty(), "explicit dismissal");
        check(!state.receive(SESSION, result(AdminStatus.REJECTED)), "dismissed result stays dismissed");
        check(state.begin(command), "capacity reusable");
        state.close();
        check(state.result(REQUEST).isEmpty() && !state.receive(SESSION, result(AdminStatus.SUCCEEDED)), "close clears all");
    }

    private static void staleControlRefusal() {
        for(var operation:List.of(AdminOperation.PAUSE,AdminOperation.RESUME)){
            var state=new AdminCommandState(1);state.reset(SESSION);
            var command=new AdminCommand(SESSION,REQUEST,new Revision(7),operation,
                new ControlPayload(operation==AdminOperation.PAUSE?RunControl.PAUSED:RunControl.RUNNING));
            var sent=new ArrayList<AdminCommand>();
            check(AdminCommandDispatch.send(state,command,sent::add)==AdminCommandDispatch.Outcome.SENT_UNCONFIRMED,
                "actual control request recorded "+operation);
            var uncertain=new AdminCommandResult(REQUEST,AdminStatus.DEFERRED,
                List.of(fr.proutlost.director.api.error.ReasonCode.DELIVERY_UNCERTAIN),Optional.empty(),Optional.empty());
            check(state.receive(SESSION,uncertain)&&!state.dismiss(REQUEST),
                "delivery uncertainty retains modifying request "+operation);
            var stale=new AdminCommandResult(REQUEST,AdminStatus.DEFERRED,
                List.of(fr.proutlost.director.api.error.ReasonCode.STALE_CONTEXT),Optional.of(new Revision(8)),Optional.empty());
            check(state.receive(SESSION,stale),"authoritative CAS refusal accepted "+operation);
            check(AdminCommandDispatch.send(state,command,sent::add)==AdminCommandDispatch.Outcome.NOT_ACCEPTED&&sent.size()==1,
                "immutable stale revision cannot be retransmitted "+operation);
            check(!state.receive(SESSION,result(AdminStatus.PENDING))&&
                  !state.receive(SESSION,result(AdminStatus.SUCCEEDED)),
                "final CAS refusal cannot regress or become success "+operation);
            check(state.dismiss(REQUEST)&&state.requestIds().isEmpty(),"CAS refusal frees bounded slot "+operation);
            var fresh=new AdminCommand(SESSION,new RequestId(new UUID(0,93)),new Revision(9),operation,command.payload());
            check(state.begin(fresh),"explicit fresh request can use refreshed revision "+operation);
        }
    }
    private static AdminCommandResult result(AdminStatus status) {
        return new AdminCommandResult(REQUEST, status, List.of(), Optional.empty(), Optional.empty());
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
