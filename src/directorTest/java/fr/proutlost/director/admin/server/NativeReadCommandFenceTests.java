package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.admin.client.AdminClientReadPayloads;
import fr.proutlost.director.admin.client.AdminClientOperationMenu;
import java.util.*;

/** Pure adversarial gate; actual two-client, F8 visual and J04 reads NOT_RUN. */
public final class NativeReadCommandFenceTests {
    private static int checks;
    private static final SessionId SESSION=new SessionId(new UUID(0,5));
    private static final Revision REV=new Revision(11);
    private static final RequestId ID=new RequestId(new UUID(0,6));
    private static final UUID WORLD=new UUID(0,7);
    private static final UUID EVENT=new UUID(0,8);
    private static final UUID REGION=new UUID(0,9);

    public static void main(String[] args) {
        check(NativeReadCommandFence.supported().size()==7,"only seven safe read operations");
        var labReadPage=reply(ID,REV,ScopeKind.WORLD,WORLD);
        for(var operation:AdminOperation.values()){
            var allowed=NativeReadCommandFence.supported().contains(operation);
            var availableWithoutCapability=Arrays.stream(AdminPanel.values()).anyMatch(panel->
                AdminClientOperationMenu.canSend(panel,operation,Optional.of(labReadPage)));
            check(allowed==availableWithoutCapability,
                "empty TEST_ONLY capabilities expose exactly the seven native read operations "+operation);
            if(allowed)check(AdminClientReadPayloads.readOnly(operation),
                "each native inspection retains read-only transport semantics "+operation);
            if(operation==AdminOperation.PAUSE||operation==AdminOperation.RESUME||
               operation==AdminOperation.DRAIN||operation==AdminOperation.TRIGGER||
               operation==AdminOperation.STOP||operation==AdminOperation.SIMULATE)
                check(!allowed,"no gameplay/control/simulation bypass "+operation);
        }
        // Pure computation is broader than the legacy LAB read adapter.
        // PRODUCTION is routed to the real runtime before this fence; a client
        // snapshot may enable REPLAY only through its advertised capability.
        check(AdminClientReadPayloads.supported(AdminOperation.REPLAY)&&AdminClientReadPayloads.readOnly(AdminOperation.REPLAY),
            "production replay has a closed pure-calculation form");
        check(!AdminClientOperationMenu.canSend(AdminPanel.DECISIONS,AdminOperation.REPLAY,Optional.of(labReadPage)),
            "legacy empty-capability LAB cannot enable replay");
        var productionReplayPage=new AdminSnapshot(ID,REV,new Tick(5),new Sequence(5),
            List.of(),Optional.empty(),Set.of(AdminOperation.REPLAY));
        check(AdminClientOperationMenu.canSend(AdminPanel.DECISIONS,AdminOperation.REPLAY,Optional.of(productionReplayPage)),
            "explicit production replay capability enables the existing decisions action");
        check(!AdminClientOperationMenu.canSend(AdminPanel.WORLD,AdminOperation.REPLAY,Optional.of(productionReplayPage)),
            "a capability cannot enable replay outside its declared tab");
        var replay=command(AdminOperation.REPLAY,new ReplayPayload(new DecisionId(EVENT),false));
        check(NativeReadCommandFence.query(replay).isEmpty(),
            "even a valid typed replay has no legacy LAB read mapping");
        var replayDenied=NativeReadCommandFence.answer(replay,Optional.empty());
        check(replayDenied.status()==AdminStatus.REJECTED&&replayDenied.reasonCodes().contains(ReasonCode.CAPABILITY_MISSING),
            "legacy LAB refuses replay rather than inventing a calculation result");
        var status=command(AdminOperation.STATUS,new EmptyAdminPayload());
        var query=NativeReadCommandFence.query(status).orElseThrow();
        check(query.panel()==AdminPanel.WORLD&&query.filter() instanceof WorldFilter,"STATUS reads durable world only");
        check(query.requestId().equals(ID)&&query.actorSession().equals(SESSION)&&
            query.expectedSnapshotRevision().equals(Optional.of(REV))&&query.limit()==1,
            "wire request and stamp conserved");
        var good=reply(ID,REV,ScopeKind.WORLD,WORLD);
        var answer=NativeReadCommandFence.answer(status,Optional.of(good));
        check(answer.status()==AdminStatus.SUCCEEDED&&answer.resultRef().orElseThrow().id().equals(WORLD),"typed world read success");
        check(answer.commandId().equals(ID)&&answer.newRevision().orElseThrow().equals(REV),"exact command correlation");
        var absent=NativeReadCommandFence.answer(status,Optional.empty());
        check(absent.status()==AdminStatus.REJECTED&&absent.reasonCodes().equals(List.of(ReasonCode.STALE_CONTEXT)),
            "missing WAL never pretends success");
        check(NativeReadCommandFence.answer(status,Optional.of(reply(new RequestId(UUID.randomUUID()),REV,ScopeKind.WORLD,WORLD))).status()==AdminStatus.FAILED_SAFE,
            "foreign correlation refused");
        check(NativeReadCommandFence.answer(status,Optional.of(reply(ID,new Revision(12),ScopeKind.WORLD,WORLD))).status()==AdminStatus.FAILED_SAFE,
            "stale/mismatched revision refused");
        check(NativeReadCommandFence.answer(status,Optional.of(reply(ID,REV,ScopeKind.PLAYER,WORLD))).status()==AdminStatus.FAILED_SAFE,
            "wrong scope refused");
        var event=command(AdminOperation.EVENT_INSPECT,new EventInspectionPayload(new EventInstanceId(EVENT)));
        check(NativeReadCommandFence.query(event).orElseThrow().filter().equals(new EventFilter(Optional.of(new EventInstanceId(EVENT)),Set.of())),
            "event inspection targets exact instance");
        check(NativeReadCommandFence.answer(event,Optional.of(reply(ID,REV,ScopeKind.EVENT,EVENT))).status()==AdminStatus.SUCCEEDED,
            "event row accepted");
        check(NativeReadCommandFence.answer(event,Optional.of(reply(ID,REV,ScopeKind.EVENT,REGION))).status()==AdminStatus.FAILED_SAFE,
            "substituted event denied");
        var scanning=new AdminSnapshot(ID,REV,new Tick(5),new Sequence(5),List.of(),Optional.of("bounded-continuation"),Set.of());
        var continuation=NativeReadCommandFence.answer(event,Optional.of(scanning));
        check(continuation.status()==AdminStatus.REJECTED&&continuation.reasonCodes().equals(List.of(ReasonCode.STALE_CONTEXT)),
            "EVENT_INSPECT bounded continuation is not proof of NO_TARGET");
        var exhausted=new AdminSnapshot(ID,REV,new Tick(5),new Sequence(5),List.of(),Optional.empty(),Set.of());
        check(NativeReadCommandFence.answer(event,Optional.of(exhausted)).reasonCodes().equals(List.of(ReasonCode.NO_TARGET)),
            "EVENT_INSPECT reports NO_TARGET only after bounded search is exhausted");
        var disasterId=new DisasterId(new UUID(0,80));
        var blob=BlobContent.capture(new ResourceId("test:f8_disaster"),1,new byte[]{1});
        var disaster=new DisasterInstance(new AggregateHeader(1,disasterId.value(),REV,new Sequence(5),new Tick(5)),
            disasterId,new EventInstanceId(EVENT),new DefinitionId("test:disaster"),DisasterStage.WARN,Map.of(),new UnitValue(.1),
            new Footprint(new DimensionId("minecraft:overworld"),Set.of(),Set.of(),List.of()),
            new WarningEvidence(Set.of(),new Tick(1),new DurationTicks(20),blob.reference().hash(),new Epoch(1),false,false),
            Set.of(),List.of(),blob.reference(),Set.of(),"warn",Set.of());
        var actualDisaster=new AdminSnapshot(ID,REV,new Tick(5),new Sequence(5),List.of(new AdminRow(
            new ScopeRef(ScopeKind.EVENT,EVENT),REV,new Tick(5),new DisasterAdminData(disaster))),Optional.empty(),Set.of());
        check(NativeReadCommandFence.answer(event,Optional.of(actualDisaster)).status()==AdminStatus.SUCCEEDED,
            "EVENT_INSPECT accepts genuine DisasterAdminData by linked event scope");
        var usingDisasterId=command(AdminOperation.EVENT_INSPECT,new EventInspectionPayload(new EventInstanceId(disasterId.value())));
        check(NativeReadCommandFence.answer(usingDisasterId,Optional.of(actualDisaster)).reasonCodes().equals(List.of(ReasonCode.STALE_CONTEXT)),
            "DisasterId cannot substitute for linked EventInstanceId in command result");
        var mutationId=new MutationId(new UUID(0,81));
        var ownToken=new OwnerToken(new UUID(0,82));
        var physical=new BlockPosition(new DimensionId("minecraft:overworld"),1,64,2);
        var resource=new ResourceKey(OwnershipDomain.BLOCK,physical.dimension(),"1,64,2");
        var mutation=new MutationRecord(new AggregateHeader(1,mutationId.value(),REV,new Sequence(5),new Tick(5)),
            mutationId,new EventInstanceId(EVENT),new SliceId(new UUID(0,83)),MutationState.VERIFIED,
            new PrimitiveId("proutlost:trace"),1,new Footprint(physical.dimension(),Set.of(),Set.of(physical),List.of()),
            blob.reference(),blob.reference(),ownToken,new Epoch(1),
            new ReadSet(Map.of(),Map.of(resource,new Epoch(1)),Map.of(resource,blob.reference().hash())),
            Set.of(),List.of(),Optional.empty(),new Sequence(5));
        var owner=new OwnershipRecord(new AggregateHeader(1,new UUID(0,84),REV,new Sequence(5),new Tick(5)),
            resource,Set.of(ownToken),new Epoch(1),OwnershipDisposition.DIRECTOR_ACTIVE,mutationId,blob.reference().hash(),new Epoch(0),Optional.empty());
        var mutationCommand=command(AdminOperation.MUTATION_INSPECT,new MutationInspectionPayload(mutationId));
        var mutationQuery=NativeReadCommandFence.query(mutationCommand).orElseThrow();
        check(mutationQuery.filter().equals(new MutationFilter(mutationId))&&mutationQuery.panel()==AdminPanel.ACTIVE,
            "mutation operation has its own typed target, not EventFilter");
        check(AdminContracts.matches(AdminOperation.MUTATION_INSPECT,new MutationInspectionPayload(mutationId))
                &&!AdminContracts.matches(AdminOperation.MUTATION_INSPECT,new EventInspectionPayload(new EventInstanceId(EVENT))),
            "mutation operation cannot reuse EVENT_INSPECT payload semantics");
        var mutationData=new MutationAdminData(mutation,new OwnershipView(Map.of(resource,owner)),Set.of(resource));
        var mutationReply=new AdminSnapshot(ID,REV,new Tick(5),new Sequence(5),List.of(new AdminRow(
            new ScopeRef(ScopeKind.EVENT,EVENT),REV,new Tick(5),mutationData)),Optional.empty(),Set.of());
        check(NativeReadCommandFence.answer(mutationCommand,Optional.of(mutationReply)).status()==AdminStatus.SUCCEEDED,
            "explicit mutation row accepted using genuine linked event scope");
        var wrongMutation=command(AdminOperation.MUTATION_INSPECT,new MutationInspectionPayload(new MutationId(EVENT)));
        check(NativeReadCommandFence.answer(wrongMutation,Optional.of(mutationReply)).status()==AdminStatus.FAILED_SAFE,
            "EventId cannot replace selected MutationId");
        var wrongEventScope=new AdminSnapshot(ID,REV,new Tick(5),new Sequence(5),List.of(new AdminRow(
            new ScopeRef(ScopeKind.EVENT,REGION),REV,new Tick(5),mutationData)),Optional.empty(),Set.of());
        check(NativeReadCommandFence.answer(mutationCommand,Optional.of(wrongEventScope)).status()==AdminStatus.FAILED_SAFE,
            "mutation reply must preserve its actual instanceId scope");
        check(NativeReadCommandFence.answer(mutationCommand,Optional.of(exhausted)).reasonCodes().equals(List.of(ReasonCode.NO_TARGET)),
            "exact missing mutation lookup reports NO_TARGET");
        check(!AdminContracts.authorized(mutationCommand,new ActorIdentity(SESSION,ActorKind.PLAYER,Optional.of(new PlayerId(EVENT)),Optional.empty(),3,true))
                &&AdminContracts.authorized(mutationCommand,new ActorIdentity(SESSION,ActorKind.PLAYER,Optional.of(new PlayerId(EVENT)),Optional.empty(),4,true))
                &&!AdminContracts.authorized(mutationCommand,new ActorIdentity(SESSION,ActorKind.PLAYER,Optional.of(new PlayerId(EVENT)),Optional.empty(),4,false)),
            "new inspection preserves authenticated OP4 requirement");
        var region=command(AdminOperation.REGION,new RegionInspectionPayload(new RegionId(REGION)));
        check(NativeReadCommandFence.query(region).orElseThrow().panel()==AdminPanel.MEMORY,"region uses memory projection");
        check(NativeReadCommandFence.answer(region,Optional.of(reply(ID,REV,ScopeKind.REGION,REGION))).status()==AdminStatus.SUCCEEDED,
            "same region accepted");
        var empty=new AdminSnapshot(ID,REV,new Tick(5),new Sequence(5),List.of(),Optional.empty(),Set.of());
        check(NativeReadCommandFence.answer(region,Optional.of(empty)).status()==AdminStatus.REJECTED,
            "unseen region is NO_TARGET");
        var list=command(AdminOperation.EVENT_LIST,new EmptyAdminPayload());
        check(NativeReadCommandFence.query(list).orElseThrow().panel()==AdminPanel.ACTIVE,"event list reads WAL");
        check(NativeReadCommandFence.answer(list,Optional.of(empty)).status()==AdminStatus.SUCCEEDED,
            "empty event list is a valid durable read");
        var blocked=command(AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));
        check(NativeReadCommandFence.query(blocked).isEmpty(),"pause has no read mapping");
        var refused=NativeReadCommandFence.answer(blocked,Optional.empty());
        check(refused.status()==AdminStatus.REJECTED&&refused.reasonCodes().contains(ReasonCode.CAPABILITY_MISSING),
            "forbidden command remains fail closed");
        for(var op:List.of(AdminOperation.TRIGGER,AdminOperation.STABILIZE,
                AdminOperation.SIMULATE,AdminOperation.REPLAY))
            check(!NativeReadCommandFence.supported().contains(op),"unqualified runtime remains unavailable "+op);
        System.out.println("PASS NativeReadCommandFenceTests: "+checks+" assertions; real clients NOT_RUN");
    }

    private static AdminCommand command(AdminOperation op,AdminPayload payload){
        return new AdminCommand(SESSION,ID,REV,op,payload);
    }
    private static AdminSnapshot reply(RequestId id,Revision revision,ScopeKind kind,UUID target){
        var row=new AdminRow(new ScopeRef(kind,target),revision,new Tick(5),
            new PublicStatusData(HealthMode.READ_ONLY_SAFE,true,Set.of()));
        return new AdminSnapshot(id,revision,new Tick(5),new Sequence(5),
            List.of(row),Optional.empty(),Set.of());
    }
    private static void check(boolean ok,String label){
        checks++;if(!ok)throw new AssertionError(label);
    }
}
