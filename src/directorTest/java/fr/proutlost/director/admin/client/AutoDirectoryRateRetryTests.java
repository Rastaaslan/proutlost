package fr.proutlost.director.admin.client;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.adapter.neoforge.network.PerceptionTransport;
import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Synthetic client/actual transport regression; never native acceptance. */
public final class AutoDirectoryRateRetryTests {
    private static int checks;
    private static long ids=100;
    private static final long SECOND=1_000_000_000L;
    private static final SessionIdentity NETWORK=new SessionIdentity(new WorldId(new UUID(24,1)),
        new Epoch(1),new Epoch(1),new DimensionId("minecraft:overworld"),new PlayerId(new UUID(24,2)));
    private static final SessionId ACTOR=PerceptionTransport.adminSession(NETWORK);
    private static final RegionFilter FILTER=new RegionFilter(Optional.empty(),Set.of(new TagId("proutlost:dungeon")));
    private static final Revision REV=new Revision(7);
    private static final Tick CAPTURED=new Tick(10);
    private static final Sequence CUT=new Sequence(5);
    private static RequestId id(){return new RequestId(new UUID(24,ids++));}
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static AdminQuery query(String cursor){
        return new AdminQuery(ACTOR,id(),AdminPanel.MEMORY,FILTER,Optional.ofNullable(cursor),
            cursor==null?Optional.empty():Optional.of(REV),1);
    }
    private static AdminSnapshot response(AdminQuery q,String next,Revision rev,Tick tick,Sequence cut){
        return new AdminSnapshot(q.requestId(),rev,tick,cut,List.of(),Optional.ofNullable(next),Set.of(AdminOperation.STATUS));
    }
    private static AdminCommandResult rate(AdminQuery q){
        return new AdminCommandResult(q.requestId(),AdminStatus.REJECTED,List.of(ReasonCode.RATE_LIMITED),Optional.empty(),Optional.empty());
    }
    private static final class Fixture {
        final AtomicLong clock=new AtomicLong();
        final AdminPageState page=new AdminPageState(clock::get);
        Fixture(){
            page.reset(NETWORK);var first=query(null);page.begin(first);
            check(page.receive(NETWORK,response(first,"cursor:1",REV,CAPTURED,CUT)),"accepted native-shaped empty warm page");
            check(page.loadingDirectoryPage()&&!page.assemblingDocument(),"directory warm page has no invented fragment");
        }
        AdminQuery begin(){var q=query("cursor:1");check(page.beginDirectoryContinuation(q),"exact warm cursor marked");return q;}
        void refuse(AdminQuery q,long tick){
            if(!page.deferRateLimitedContinuation(NETWORK,rate(q),tick,40))
                check(page.reject(NETWORK,q.requestId(),ReasonCode.RATE_LIMITED,tick,40),"exhausted rate becomes terminal");
            else check(true,"authoritative exact rate retained");
        }
        AdminQuery retry(long tick,long seconds){
            clock.addAndGet(seconds*SECOND);
            var q=page.continuationRetryQuery(id(),tick).orElseThrow();
            check(page.beginDirectoryContinuation(q),"bounded exact directory retry begins");return q;
        }
    }
    public static void main(String[] args){
        initialWarmFilterEditCancelsWithoutSending();
        completeOnSameCursor();independentClocks();budgetAndDeadline();tupleFences();
        authenticatedQuotaOnly();manualQueriesStayManual();mixedFullStampRefuses();
        cancellationAndTransportFailure();native39Versus40();
        System.out.println("PASS AutoDirectoryRateRetryTests: "+checks+" assertions; synthetic client and actual transport only; native NOT_RUN");
    }
    /** The real R25 screen crashed when an initial empty directory page was
     * accepted, then its form was edited before the first automatic next.
     * Also exercise editing while an authoritative rate retry is scheduled. */
    private static void initialWarmFilterEditCancelsWithoutSending(){
        for(boolean afterRate:List.of(false,true)){
            var f=new Fixture();var accepted=f.page.snapshot().orElseThrow();
            if(afterRate){var q=f.begin();f.refuse(q,100);f.clock.addAndGet(2*SECOND);}
            else check(!f.page.directoryContinuationActive()&&!f.page.assemblingDocument(),
                    "native crash happens before automatic directory is active");
            var changedFilter=new RegionFilter(Optional.empty(),Set.of(new TagId("proutlost:clearing")));
            var changed=new AdminQuery(ACTOR,id(),AdminPanel.MEMORY,changedFilter,
                    Optional.of("cursor:1"),Optional.of(REV),1);
            var sent=new ArrayList<AdminQuery>();long tick=afterRate?140:0;
            check(!AdminQueryDispatch.sendDirectoryContinuation(f.page,NETWORK,changed,sent::add,tick,40),
                    "form edit safely cancels instead of throwing on screen tick");
            check(sent.isEmpty(),"old cursor never sent with edited filter");
            check(!f.page.pending()&&f.page.snapshot().isEmpty()&&f.page.loadingContinuation().isEmpty(),
                    "context cancellation clears the old visible and continuation state");
            check(!f.page.needsQuery()&&!f.page.continuationRetrying()
                    &&f.page.directoryContinuationRetryView().isEmpty(),
                    "edited form waits explicit Search without silently replaying old work");
            check(f.page.failureReason().equals(Optional.of(ReasonCode.STALE_CONTEXT)),
                    "local context drift remains an explicit safe stale cancellation");
            check(!f.page.receive(NETWORK,accepted),"late old page cannot restore cancelled context");
            var fresh=new AdminQuery(ACTOR,id(),AdminPanel.MEMORY,changedFilter,
                    Optional.empty(),Optional.empty(),1);
            check(AdminQueryDispatch.send(f.page,NETWORK,fresh,sent::add,tick,40),
                    "normal explicit fresh Search remains available after editing");
            check(sent.equals(List.of(fresh))&&f.page.receive(NETWORK,response(fresh,null,REV,CAPTURED,CUT)),
                    "fresh edited search completes on its own exact request");
        }
    }
    private static void same(AdminQuery a,AdminQuery b){
        check(!a.requestId().equals(b.requestId()),"new request identity required");
        check(a.actorSession().equals(b.actorSession())&&a.panel()==b.panel()&&a.filter().equals(b.filter())
            &&a.cursor().equals(b.cursor())&&a.expectedSnapshotRevision().equals(b.expectedSnapshotRevision())
            &&a.limit()==b.limit(),"actor/filter/cursor/revision/limit preserved");
    }
    private static void completeOnSameCursor(){
        var f=new Fixture();var q=f.begin();f.refuse(q,100);
        check(f.page.continuationRetrying()&&f.page.directoryContinuationRetrying()&&!f.page.pending(),"rate schedules directory retry only");
        check(f.page.continuationRetryView().isEmpty(),"directory does not fabricate fragment/hash evidence");
        var view=f.page.directoryContinuationRetryView().orElseThrow();
        check(view.session().equals(NETWORK)&&view.refusedQuery().equals(q)&&view.retryRequestId().isEmpty(),"view binds genuine refused identity");
        check(view.retryOrdinal()==1&&view.maximumRetries()==3&&view.observedNanos()==0
            &&view.retryNotBeforeNanos()==2*SECOND&&view.deadlineNanos()==30*SECOND&&view.backoffNanos()==2*SECOND,"unchanged bounded clocks");
        check(view.snapshotRevision().equals(REV)&&view.capturedTick().equals(CAPTURED)&&view.lastDurableSequence().equals(CUT),"entire original stamp retained");
        check(!f.page.receive(NETWORK,response(q,"cursor:2",REV,CAPTURED,CUT)),"late refused identity ignored");
        var retry=f.retry(140,2);same(q,retry);
        check(f.page.directoryContinuationRetryView().orElseThrow().retryRequestId().equals(Optional.of(retry.requestId())),"actual retry identity observable");
        check(f.page.receive(NETWORK,response(retry,"cursor:2",REV,CAPTURED,CUT)),"same-cut retry advances original cursor");
        check(f.page.loadingContinuation().orElseThrow().equals("cursor:2")&&!f.page.continuationRetrying()
            &&f.page.directoryContinuationRetryView().isEmpty(),"success ends old refusal episode");
        var next=query("cursor:2");check(f.page.beginDirectoryContinuation(next),"next exact empty warm cursor allowed");
        f.refuse(next,200);
        check(f.page.directoryContinuationRetryView().orElseThrow().retryOrdinal()==1,"new successful window has separate bounded retry episode");
        var last=f.retry(240,2);
        check(f.page.receive(NETWORK,response(last,null,REV,CAPTURED,CUT)),"terminal directory page accepted");
        check(!f.page.directoryContinuationActive()&&!f.page.pending()&&f.page.loadingContinuation().isEmpty()
            &&f.page.failureReason().isEmpty(),"terminal page leaves no pending retry loop");
    }
    private static void independentClocks(){
        var f=new Fixture();var q=f.begin();f.refuse(q,100);
        check(f.page.continuationRetryQuery(id(),140).isEmpty(),"40client ticks do not skip real monotonic backoff");
        f.clock.set(2*SECOND-1);check(f.page.continuationRetryQuery(id(),10000).isEmpty(),"accelerated client ticks cannot skip2seconds");
        f.clock.set(2*SECOND);check(f.page.continuationRetryQuery(id(),139).isEmpty(),"2seconds do not skip40client ticks");
        check(f.page.continuationRetryQuery(id(),140).isPresent(),"both clocks required");
    }
    private static void budgetAndDeadline(){
        var f=new Fixture();var q=f.begin();f.refuse(q,100);
        q=f.retry(140,2);f.refuse(q,140);
        check(f.page.directoryContinuationRetryView().orElseThrow().backoffNanos()==4*SECOND,"second backoff4seconds");
        q=f.retry(180,4);f.refuse(q,180);
        check(f.page.directoryContinuationRetryView().orElseThrow().backoffNanos()==8*SECOND,"third backoff8seconds");
        q=f.retry(220,8);f.refuse(q,220);
        check(!f.page.continuationRetrying()&&!f.page.directoryContinuationActive()&&f.page.snapshot().isEmpty(),"fourth refusal exhausts unchanged3retry budget");
        check(!f.page.needsQuery()&&f.page.continuationRetryQuery(id(),10000).isEmpty(),"exhaustion never starts fresh search");
        var expired=new Fixture();var first=expired.begin();expired.refuse(first,100);
        var inFlight=expired.retry(140,2);expired.clock.set(30*SECOND);
        check(!expired.page.receive(NETWORK,response(inFlight,"cursor:2",REV,CAPTURED,CUT)),"inflight success at30second deadline rejected");
        check(expired.page.failureReason().orElseThrow()==ReasonCode.STALE_CONTEXT
            &&expired.page.directoryContinuationRetryView().isEmpty()&&!expired.page.pending(),"deadline releases pending cursor and evidence");
        var e=new Fixture();var a=e.begin();e.refuse(a,100);e.clock.set(2*SECOND);
        var ready=e.page.continuationRetryQuery(id(),140).orElseThrow();e.clock.set(30*SECOND);
        var sent=new ArrayList<AdminQuery>();
        check(!AdminQueryDispatch.sendDirectoryContinuation(e.page,NETWORK,ready,sent::add,140,40)
            &&sent.isEmpty(),"expiry between scheduling and dispatch sends nothing");
    }
    private static void bad(AdminPageState page,AdminQuery q,String reason){
        try{page.beginDirectoryContinuation(q);throw new AssertionError("accepted "+reason);}
        catch(IllegalArgumentException expected){check(true,"refuses altered "+reason);}
        check(!page.pending()&&page.snapshot().isPresent(),"altered tuple cannot consume accepted basis");
    }
    private static void tupleFences(){
        var f=new Fixture();
        bad(f.page,new AdminQuery(new SessionId(new UUID(99,1)),id(),AdminPanel.MEMORY,FILTER,Optional.of("cursor:1"),Optional.of(REV),1),"actor");
        bad(f.page,new AdminQuery(ACTOR,id(),AdminPanel.MEMORY,new RegionFilter(Optional.empty(),Set.of(new TagId("proutlost:fauna_corridor"))),Optional.of("cursor:1"),Optional.of(REV),1),"filter");
        bad(f.page,new AdminQuery(ACTOR,id(),AdminPanel.MEMORY,FILTER,Optional.of("other"),Optional.of(REV),1),"cursor");
        bad(f.page,new AdminQuery(ACTOR,id(),AdminPanel.MEMORY,FILTER,Optional.of("cursor:1"),Optional.of(new Revision(8)),1),"revision");
        bad(f.page,new AdminQuery(ACTOR,id(),AdminPanel.MEMORY,FILTER,Optional.of("cursor:1"),Optional.of(REV),2),"page bound");
        bad(f.page,query(null),"fresh query");
        var q=f.begin();f.refuse(q,100);f.clock.set(2*SECOND);
        bad(f.page,q,"replayed request ID");
    }
    private static void authenticatedQuotaOnly(){
        var f=new Fixture();var q=f.begin();
        var foreign=new SessionIdentity(NETWORK.worldId(),NETWORK.bootEpoch(),new Epoch(2),NETWORK.dimension(),NETWORK.playerId());
        check(!f.page.deferRateLimitedContinuation(foreign,rate(q),100,40),"foreign connection cannot schedule");
        check(!f.page.deferRateLimitedContinuation(NETWORK,rate(query("cursor:1")),100,40),"wrong result identity cannot schedule");
        for(var status:List.of(AdminStatus.DEFERRED,AdminStatus.FAILED_SAFE,AdminStatus.SUCCEEDED)){
            var r=new AdminCommandResult(q.requestId(),status,List.of(ReasonCode.RATE_LIMITED),Optional.empty(),Optional.empty());
            check(!f.page.deferRateLimitedContinuation(NETWORK,r,100,40),"quota text alone with status"+status+" cannot schedule");
        }
        var mixed=new AdminCommandResult(q.requestId(),AdminStatus.REJECTED,List.of(ReasonCode.RATE_LIMITED,ReasonCode.AUTH_DENIED),Optional.empty(),Optional.empty());
        check(!f.page.deferRateLimitedContinuation(NETWORK,mixed,100,40),"mixed refusal not retryable");
        check(f.page.reject(NETWORK,q.requestId(),ReasonCode.STALE_CONTEXT,100,40)&&!f.page.directoryContinuationActive(),"other refusal remains terminal");
    }
    private static void manualQueriesStayManual(){
        var f=new Fixture();var q=query("cursor:1");f.page.begin(q);
        check(!f.page.deferRateLimitedContinuation(NETWORK,rate(q),100,40),"unmarked semantic NEXT cannot acquire policy");
        var a=new AdminPageState();a.reset(NETWORK);var first=query(null);a.begin(first);
        check(!a.deferRateLimitedContinuation(NETWORK,rate(first),100,40),"fresh search cannot auto retry");
    }
    private static void mixedFullStampRefuses(){
        for(int part=0;part<3;part++){
            var f=new Fixture();var q=f.begin();f.refuse(q,100);var retry=f.retry(140,2);
            var response=response(retry,null,part==0?new Revision(8):REV,
                part==1?new Tick(11):CAPTURED,part==2?new Sequence(6):CUT);
            check(!f.page.receive(NETWORK,response)&&f.page.snapshot().isEmpty()&&f.page.needsQuery(),"mixed stamp component"+part+" refuses safely");
        }
    }
    private static void cancellationAndTransportFailure(){
        for(int mode=0;mode<4;mode++){
            var f=new Fixture();var q=f.begin();f.refuse(q,100);
            if(mode==0)f.page.cancelDocumentContinuation();
            else if(mode==1)f.page.reset(NETWORK);
            else if(mode==2)f.page.close();
            else f.page.invalidate();
            check(!f.page.directoryContinuationActive()&&f.page.directoryContinuationRetryView().isEmpty()
                &&f.page.continuationRetryQuery(id(),10000).isEmpty(),"lifecycle boundary"+mode+" clears retries");
        }
        var f=new Fixture();var q=query("cursor:1");
        check(!AdminQueryDispatch.sendDirectoryContinuation(f.page,NETWORK,q,x->{throw new IllegalStateException("test transport");},100,40),"transport exception reports failed send");
        check(f.page.failureReason().orElseThrow()==ReasonCode.INTERNAL_FAULT&&!f.page.continuationRetrying()
            &&f.page.snapshot().isEmpty(),"uncertain transport never auto retries cursor");
    }
    private static void native39Versus40(){
        var ticks=new AtomicLong();
        var t=new PerceptionTransport(NETWORK.worldId(),NETWORK.bootEpoch(),Set.of(),Map.of(),
            new PerceptionTransport.Limits(2,8,100,120,32,32,40),ticks::get,(player,payload)->{});
        var session=t.open(NETWORK.playerId(),NETWORK.dimension());
        check(t.capabilities(NETWORK.playerId(),new Capabilities(1,session.connectionEpoch(),Set.of(),Map.of())),"actual native transport capability handshake");
        check(t.adminQuery(NETWORK.playerId(),ACTOR),"initial admitted query");
        var f=new Fixture();var q=f.begin();ticks.set(39);
        check(!t.adminQuery(NETWORK.playerId(),ACTOR),"unchanged40tick quota rejects at39");
        f.refuse(q,100);ticks.set(40);
        check(t.adminQuery(NETWORK.playerId(),ACTOR),"same native quota admits at40");
        var retry=f.retry(140,2);ticks.set(80);
        check(t.adminQuery(NETWORK.playerId(),ACTOR),"later exact retry respects original server quota");
        check(f.page.receive(NETWORK,response(retry,null,REV,CAPTURED,CUT)),"quota-safe completion on original stamp");
    }
}
