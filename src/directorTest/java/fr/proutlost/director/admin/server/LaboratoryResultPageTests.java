package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.admin.client.AdminClientReadPayloads;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Targeted regression: exact immutable J07 input plus adversarial page boundaries.
 * Does not substitute for the required real Minecraft display qualification. */
public final class LaboratoryResultPageTests {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static final SessionId SESSION=new SessionId(new UUID(0,707));
    private static final ResourceId FIXTURE=new ResourceId("qa:a3-e7214257");
    private static final Revision REV=new Revision(241);
    private static AdminQuery query(Optional<String> cursor,int limit,ResourceId fixture) {
        return new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),AdminPanel.LABORATORY,
            new LaboratoryFilter(Optional.of(fixture)),cursor,Optional.of(REV),limit);
    }
    private static void refused(Runnable action,ReasonCode code) {
        try{action.run();throw new AssertionError("expected "+code);}
        catch(AdminProjectionPages.Rejected expected){check(expected.reason()==code,"exact refusal "+code);}
    }
    public static void main(String[] args)throws Exception {
        var input=new ModelCodec<>(DecisionInput.class).decode(Files.readAllBytes(Path.of(args[0])));
        var proposal=new fr.proutlost.director.engine.WorldDirector(ProviderMode.PRODUCTION).evaluate(input);
        var before=new ModelCodec<>(DecisionProposal.class).encode(proposal);
        var stamp=new AdminSnapshotStamp(input.context().scopes().worldId(),input.state().world().bootEpoch(),REV,new Tick(227200),new Sequence(1281));
        var row=new AdminRow(new ScopeRef(ScopeKind.WORLD,stamp.worldId().value()),REV,stamp.capturedTick(),
            new LaboratoryAdminData(FIXTURE,proposal,List.of()));
        var original=new AdminReadResult(AdminStatus.SUCCEEDED,List.of(row),Optional.empty(),Optional.empty(),Optional.of(stamp));
        check(new ModelCodec<>(AdminReadResult.class).encode(original).length==66311,"exact original response regression");
        check(!AdminLaboratoryResultPages.fits(original),"original result exceeds unchanged bound");
        var pages=new AdminLaboratoryResultPages(SESSION,row,stamp);
        var first=pages.query(query(Optional.empty(),10,FIXTURE));
        check(AdminLaboratoryResultPages.fits(first),"real result has bounded projection");
        var detail=(LaboratoryResultPageData)first.data().getFirst().data();
        check(detail.candidates().equals(proposal.candidates()),"all eight exact candidates retained");
        check(detail.proposalHash().equals(BlobContent.hash(before)),"exact complete proposal hash");
        check(detail.auditHash().equals(BlobContent.hash(new ModelCodec<>(AuditDecision.class).encode(proposal.audit()))),"exact audit hash");
        var snapshot=AdminFacade.snapshot(query(Optional.empty(),10,FIXTURE),first,EnumSet.allOf(AdminOperation.class));
        var codec=new ModelCodec<>(AdminSnapshot.class);
        check(codec.decode(codec.encode(snapshot)).equals(snapshot),"closed model wire round trip");
        check(AdminClientReadPayloads.simulationResultMatches(snapshot,FIXTURE),"client accepts exact fixture projection");
        check(!AdminClientReadPayloads.simulationResultMatches(snapshot,new ResourceId("qa:other")),"client refuses substituted fixture");
        check(Arrays.equals(before,new ModelCodec<>(DecisionProposal.class).encode(proposal)),"complete server proposal remains immutable");

        // Force multiple real bounded pages without changing a limit.
        var many=new ArrayList<Candidate>();
        for(int i=0;i<64;i++){
            var c=proposal.candidates().get(i%proposal.candidates().size());
            many.add(new Candidate(new CandidateId(new UUID(707,i)),c.definition(),c.eligible(),c.reasons(),
                c.contributions(),c.score(),c.roles(),c.scopes()));
        }
        var large=new DecisionProposal(proposal.proposalId(),many,proposal.selectedPlan(),proposal.audit(),proposal.stateChanges());
        var largeRow=new AdminRow(row.scope(),REV,stamp.capturedTick(),new LaboratoryAdminData(FIXTURE,large,List.of()));
        var paginated=new AdminLaboratoryResultPages(SESSION,largeRow,stamp);
        var result=paginated.query(query(Optional.empty(),10,FIXTURE));
        check(result.nextCursor().isPresent(),"large score set genuinely paginates");
        var initialCursor=result.nextCursor().orElseThrow();
        refused(()->paginated.query(query(Optional.of(initialCursor),9,FIXTURE)),ReasonCode.STALE_CONTEXT);
        refused(()->paginated.query(new AdminQuery(new SessionId(new UUID(0,999)),new RequestId(UUID.randomUUID()),
            AdminPanel.LABORATORY,new LaboratoryFilter(Optional.of(FIXTURE)),Optional.of(initialCursor),Optional.of(REV),10)),ReasonCode.AUTH_DENIED);
        refused(()->paginated.query(query(Optional.of(initialCursor),10,new ResourceId("qa:other"))),ReasonCode.AUTH_DENIED);
        var collected=new ArrayList<Candidate>();int pageCount=0;
        while(true){
            check(AdminLaboratoryResultPages.fits(result),"each response and worst wire envelope bounded");
            var data=(LaboratoryResultPageData)result.data().getFirst().data();
            check(data.candidateOffset()==collected.size()&&data.candidateCount()==many.size(),"complete ordered pagination metadata");
            collected.addAll(data.candidates());pageCount++;
            if(result.nextCursor().isEmpty())break;
            result=paginated.query(query(result.nextCursor(),10,FIXTURE));
            check(pageCount<64,"bounded traversal");
        }
        check(collected.equals(many),"no dropped or duplicated candidate");
        refused(()->paginated.query(query(Optional.of(initialCursor),10,FIXTURE)),ReasonCode.STALE_CONTEXT);

        // A single oversized candidate remains a refusal, never a raised budget.
        var c=proposal.candidates().getFirst();
        var huge=new Candidate(c.id(),c.definition(),c.eligible(),c.reasons(),Collections.nCopies(500,c.contributions().getFirst()),c.score(),c.roles(),c.scopes());
        var hugeProposal=new DecisionProposal(proposal.proposalId(),List.of(huge),proposal.selectedPlan(),proposal.audit(),proposal.stateChanges());
        var hugeRow=new AdminRow(row.scope(),REV,stamp.capturedTick(),new LaboratoryAdminData(FIXTURE,hugeProposal,List.of()));
        refused(()->new AdminLaboratoryResultPages(SESSION,hugeRow,stamp).query(query(Optional.empty(),10,FIXTURE)),ReasonCode.BUDGET_EXHAUSTED);

        var actor=new AtomicReference<>(new ActorIdentity(SESSION,ActorKind.PLAYER,Optional.of(new PlayerId(new UUID(0,707))),Optional.empty(),4,true));
        ActorAuthorityPort authority=s->s.equals(SESSION)?Optional.of(actor.get()):Optional.empty();
        var calculations=new AdminReadOnlyCalculations(i->proposal,id->input,id->input);
        var readOnly=new AdminReadOnlyCommands(calculations,new AdminAuditExporter(Path.of(args[1]),Runnable::run,100000),
            id->{throw new AssertionError("no export expected");},authority,Runnable::run,4);
        RuntimeAdminPort never=new RuntimeAdminPort(){
            public AdminResult submit(AdminCommand command){throw new AssertionError("simulation must not admit physical effects");}
            public AdminResult poll(RuntimeAdminKey key){throw new AssertionError("simulation must not poll runtime");}
        };
        var command=new AdminCommand(SESSION,new RequestId(UUID.randomUUID()),REV,AdminOperation.SIMULATE,new SimulationPayload(FIXTURE,input.context(),true));
        var completion=readOnly.submit(command,stamp);readOnly.tick();
        check(completion.toCompletableFuture().join().data().orElseThrow().data() instanceof LaboratoryAdminData,"canonical complete result preserved");
        var facade=new AdminFacade(q->{throw new AssertionError("must use session result");},never,authority,stamp.worldId(),stamp.bootEpoch(),4,readOnly);
        check(facade.query(query(Optional.empty(),10,FIXTURE),actor.get()).status()==AdminStatus.SUCCEEDED,"actual read-only route fixes exact regression");
        var supplied=actor.get();
        actor.set(new ActorIdentity(SESSION,ActorKind.PLAYER,supplied.playerId(),Optional.empty(),0,true));
        var denied=facade.query(query(Optional.empty(),10,FIXTURE),supplied);
        check(denied.status()==AdminStatus.REJECTED&&denied.error().orElseThrow().code()==ReasonCode.AUTH_DENIED,"permission revocation denies cached result");
        readOnly.disconnect(SESSION);
        check(readOnly.query(query(Optional.empty(),10,FIXTURE)).isEmpty(),"disconnect removes private result and cursors");
        System.out.println("PASS LaboratoryResultPageTests: "+checks+" assertions; stress_pages="+pageCount+" exact_projection_bytes="+new ModelCodec<>(AdminReadResult.class).encode(first).length+" wire_bytes="+codec.encode(snapshot).length);
    }
}
