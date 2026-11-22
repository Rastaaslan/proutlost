package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.adapter.neoforge.network.WireCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.nio.file.*;
import java.util.*;

/** Self-contained synthetic packet/continuation regression using the existing closed DTOs.
 * Native official-client rendering remains an independent required receipt. */
public final class DecisionResultPageTests {
    private static int checks;
    private static final SessionId SESSION=new SessionId(new UUID(0,739));
    private static final Revision REV=new Revision(241);
    private static void check(boolean value,String why) {
        checks++;if(!value)throw new AssertionError(why);
    }
    private static AdminQuery query(DecisionId id,Optional<String> cursor,int limit) {
        return new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),AdminPanel.DECISIONS,
                new DecisionFilter(Optional.of(id)),cursor,Optional.of(REV),limit);
    }
    private static void refused(Runnable action,ReasonCode reason) {
        try {action.run();throw new AssertionError("expected "+reason);}
        catch(AdminProjectionPages.Rejected error) {check(error.reason()==reason,"exact "+reason);}
    }
    public static void main(String[] args)throws Exception {
        var world=new WorldId(new UUID(739,1));
        var decisionId=new DecisionId(new UUID(739,2));
        var scope=new ScopeRef(ScopeKind.WORLD,world.value());
        var stamp=new AdminSnapshotStamp(world,new Epoch(1),REV,new Tick(227200),new Sequence(1281));
        var scores=new ArrayList<ScoreContribution>();
        for(int n=0;n<24;n++)scores.add(new ScoreContribution("synthetic-feature-"+n,.5,1,.5));
        var definition=new DefinitionRef(new DefinitionId("test:readonly_packet_boundary"),new Revision(1),new Fingerprint("0".repeat(64)));
        var roles=new RoleAssignments(Map.of("witness",List.of(new PlayerId(new UUID(739,3)))));
        var scopes=new ScopeSet(world,Set.of(scope));
        var validations=List.of(new ValidationResult(true,List.of(),new ReadSet(Map.of(scope,REV),Map.of(),Map.of())));
        var many=new ArrayList<Candidate>();
        for(int n=0;n<64;n++) {
            many.add(new Candidate(new CandidateId(new UUID(739,n)),definition,true,
                    List.of(),scores,12,roles,scopes));
        }
        var full=new DecisionAdminData(decisionId,new Tick(227100),many,
                Optional.of(many.get(37).id()),Optional.empty(),validations,Optional.of(Outcome.SUCCEEDED));
        var original=new AdminRow(new ScopeRef(ScopeKind.WORLD,stamp.worldId().value()),
                REV,stamp.capturedTick(),full);
        byte[] frozen=new ModelCodec<>(DecisionAdminData.class).encode(full);
        var whole=new AdminReadResult(AdminStatus.SUCCEEDED,List.of(original),
                Optional.empty(),Optional.empty(),Optional.of(stamp));
        check(!AdminDecisionResultPages.fits(whole),"actual full candidate view exceeds unchanged packet bound");
        var pages=new AdminDecisionResultPages(SESSION,original,stamp);
        var result=pages.query(new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),AdminPanel.DECISIONS,
                new DecisionFilter(Optional.of(full.decisionId())),Optional.empty(),Optional.empty(),25));
        check(result.nextCursor().isPresent(),"genuine bounded continuation required");
        var firstToken=result.nextCursor().orElseThrow();
        refused(()->pages.query(query(full.decisionId(),Optional.of(firstToken),24)),ReasonCode.STALE_CONTEXT);
        var foreign=new AdminQuery(new SessionId(new UUID(0,740)),new RequestId(UUID.randomUUID()),
                AdminPanel.DECISIONS,new DecisionFilter(Optional.of(full.decisionId())),
                Optional.of(firstToken),Optional.of(REV),25);
        refused(()->pages.query(foreign),ReasonCode.AUTH_DENIED);
        refused(()->pages.query(query(new DecisionId(new UUID(0,741)),Optional.of(firstToken),25)),ReasonCode.AUTH_DENIED);
        var stale=new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),AdminPanel.DECISIONS,
                new DecisionFilter(Optional.of(full.decisionId())),Optional.of(firstToken),Optional.of(REV.plus(1)),25);
        refused(()->pages.query(stale),ReasonCode.STALE_CONTEXT);
        var restored=new ArrayList<Candidate>();int count=0;
        do {
            count++;check(count<100,"finite candidate paging");
            var data=(DecisionAdminData)result.data().getFirst().data();
            restored.addAll(data.candidates());
            check(data.decisionId().equals(full.decisionId()) && data.capturedTick().equals(full.capturedTick())
                    && data.selected().equals(full.selected()) && data.advisor().equals(full.advisor())
                    && data.validations().equals(full.validations()) && data.outcome().equals(full.outcome()),
                    "global decision identity/selection/validation never truncated or relabelled");
            check(result.snapshot().orElseThrow().equals(stamp),"original historical capture tuple retained");
            var q=query(full.decisionId(),Optional.empty(),25);
            var wire=AdminFacade.snapshot(q,result,EnumSet.allOf(AdminOperation.class));
            check(new WireCodec<>(AdminSnapshot.class,24572).encode(wire).length<=AdminFacade.MAX_READ_BYTES,
                    "actual closed wire packet obeys original bound");
            check(new ModelCodec<>(AdminSnapshot.class).decode(new ModelCodec<>(AdminSnapshot.class).encode(wire)).equals(wire),
                    "existing closed DecisionAdminData wire round trip");
            if(result.nextCursor().isEmpty())break;
            var token=result.nextCursor();
            result=pages.query(query(full.decisionId(),token,25));
            refused(()->pages.query(query(full.decisionId(),token,25)),ReasonCode.STALE_CONTEXT);
        } while(true);
        check(restored.equals(many),"every exact candidate and original order restored across all pages");
        check(new HashSet<>(restored.stream().map(Candidate::id).toList()).size()==64,"no candidate duplicated");
        check(Arrays.equals(frozen,new ModelCodec<>(DecisionAdminData.class).encode(full)),"full immutable calculation unchanged");
        // Actual native F8 sequence: first query has no expected revision;
        // Suivante echoes the revision returned by that very first page.
        var fixture=new ResourceId("test:readonly_cursor_revision_contract");
        var observation=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of());
        var context=new SnapshotBatch(new RequestId(UUID.randomUUID()),scopes,stamp.capturedTick(),
                Map.of(scope,REV),observation,Set.of(),Set.of(),HealthMode.NORMAL);
        var fp=new Fingerprint("0".repeat(64));
        var audit=new AuditDecision(decisionId,context,REV,fp,REV,fp,many,List.of(),0L,List.of(),
                full.advisor(),full.selected(),roles,scopes,validations,List.of(),List.of(),full.outcome(),
                List.of(),Map.of(),List.of(),List.of(),List.of(),Map.of(),List.of());
        var proposal=new DecisionProposal(decisionId,many,Optional.empty(),audit,List.of());
        var labRow=new AdminRow(scope,REV,stamp.capturedTick(),new LaboratoryAdminData(fixture,proposal,List.of()));
        var labPages=new AdminLaboratoryResultPages(SESSION,labRow,stamp);
        var labFirst=new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),AdminPanel.LABORATORY,
                new LaboratoryFilter(Optional.of(fixture)),Optional.empty(),Optional.empty(),25);
        var labResult=labPages.query(labFirst);
        check(labResult.nextCursor().isPresent(),"laboratory native sequence actually paginates");
        var labCollected=new ArrayList<Candidate>();int labCount=0;
        while(true) {
            labCount++;check(labCount<100,"finite laboratory native-style sequence");
            check(AdminLaboratoryResultPages.fits(labResult),"laboratory original read/wire limits retained");
            check(labResult.snapshot().orElseThrow().equals(stamp),"laboratory original capture tuple retained");
            var part=(LaboratoryResultPageData)labResult.data().getFirst().data();
            check(part.candidateOffset()==labCollected.size()&&part.candidateCount()==many.size(),"laboratory exact offsets/total");
            check(part.chosen().equals(full.selected()),"laboratory global choice retained on every page");
            labCollected.addAll(part.candidates());
            if(labResult.nextCursor().isEmpty())break;
            var token=labResult.nextCursor();
            var wrong=new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),AdminPanel.LABORATORY,
                    new LaboratoryFilter(Optional.of(fixture)),token,Optional.of(REV.plus(1)),25);
            refused(()->labPages.query(wrong),ReasonCode.STALE_CONTEXT);
            var next=new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),AdminPanel.LABORATORY,
                    new LaboratoryFilter(Optional.of(fixture)),token,Optional.of(REV),25);
            labResult=labPages.query(next);
            refused(()->labPages.query(next),ReasonCode.STALE_CONTEXT);
        }
        check(labCollected.equals(many),"laboratory all exact candidates restored in native optional-to-known sequence");
        var empty=new DecisionAdminData(full.decisionId(),full.capturedTick(),List.of(),Optional.empty(),
                full.advisor(),full.validations(),full.outcome());
        var emptyRow=new AdminRow(original.scope(),REV,stamp.capturedTick(),empty);
        var noPlan=new AdminDecisionResultPages(SESSION,emptyRow,stamp).query(query(full.decisionId(),Optional.empty(),25));
        check(noPlan.nextCursor().isEmpty() && ((DecisionAdminData)noPlan.data().getFirst().data()).candidates().isEmpty(),
                "empty real result is complete without a fabricated candidate");
        var c=many.getFirst();
        var huge=new Candidate(c.id(),c.definition(),c.eligible(),c.reasons(),
                Collections.nCopies(2000,c.contributions().getFirst()),c.score(),c.roles(),c.scopes());
        var tooBig=new DecisionAdminData(full.decisionId(),full.capturedTick(),List.of(huge),
                Optional.empty(),full.advisor(),full.validations(),full.outcome());
        var cannotFit=new AdminDecisionResultPages(SESSION,
                new AdminRow(original.scope(),REV,stamp.capturedTick(),tooBig),stamp);
        refused(()->cannotFit.query(query(full.decisionId(),Optional.empty(),25)),ReasonCode.BUDGET_EXHAUSTED);
        System.out.println("PASS DecisionResultPageTests assertions="+checks+" candidates=64 pages="+count
                +" scope=synthetic_existing_closed_DTO_no_native_or_world_authority");
    }
}
