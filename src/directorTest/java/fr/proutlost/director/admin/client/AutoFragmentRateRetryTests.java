package fr.proutlost.director.admin.client;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.adapter.neoforge.network.PerceptionTransport;
import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Declared synthetic pure-client regression; no Minecraft/render/native acceptance. */
public final class AutoFragmentRateRetryTests {
    private static int checks;
    private static int fixtureBytes;
    private static long requestCounter=100;
    private static final RegionId REGION=new RegionId(new UUID(17,2));
    private static final SessionIdentity NETWORK=new SessionIdentity(new WorldId(new UUID(17,3)),
        new Epoch(1),new Epoch(1),new DimensionId("minecraft:overworld"),new PlayerId(new UUID(17,4)));
    private static final SessionId ACTOR=PerceptionTransport.adminSession(NETWORK);
    private static final Revision REVISION=new Revision(7);
    private static final Tick CAPTURED=new Tick(10);
    private static final Sequence CUT=new Sequence(5);
    private static final RegionFilter FILTER=new RegionFilter(Optional.of(REGION),Set.of());
    private static final String CURSOR_ONE="server:opaque/fragment/1";
    private static final String CURSOR_TWO="server:opaque/fragment/2";
    private static final long SECOND=1_000_000_000L;

    public static void main(String[] args){
        nativeTransport39Versus40();
        completeAfterRate();
        typedReceiptOnly();
        evidenceView();
        independentTickAndMonotonicFences();
        retryBudgetAcrossFragments();
        deadlineTerminates();
        expiryInFlightAndDispatch();
        exactTupleFence();
        successfulFragmentStartsNewDeadline();
        correlatedOnly();
        manualAndOtherRefusals();
        resetCloseAndTransportFailure();
        mixedCutRejectedAfterRetry();
        System.out.println("PASS AutoFragmentRateRetryTests: "+checks+" assertions; encoded_region_bytes="+fixtureBytes+" legal_fragments=3; pure client state only; Minecraft/render/native NOT_RUN");
    }
    private static RequestId requestId(){return new RequestId(new UUID(17,requestCounter++));}
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static AdminQuery query(Optional<String> cursor,Optional<Revision> revision){
        return new AdminQuery(ACTOR,requestId(),AdminPanel.MEMORY,FILTER,cursor,revision,1);
    }
    private static final class Fixture {
        final AtomicLong nanos=new AtomicLong(0);
        final AdminPageState page;
        final AdminRow complete;
        final byte[] bytes;
        final Fingerprint hash;
        Fixture(){this(null);}
        Fixture(LongSupplier externalClock){
            page=new AdminPageState(externalClock==null?nanos::get:externalClock);
            var anchors=new LinkedHashSet<AnchorId>();
            for(int i=0;i<600;i++)anchors.add(new AnchorId(new UUID(18,i)));
            var header=new AggregateHeader(1,REGION.value(),new Revision(2),new Sequence(0),new Tick(2));
            var region=new RegionRecord(header,REGION,NETWORK.dimension(),RegionStatus.ACTIVE,
                new SupportShape(List.of(),List.of(),List.of()),anchors,new UnitValue(.5),Set.of(),Set.of(),
                new Revision(2),new RegionPersonality(Map.of()),Set.of());
            complete=new AdminRow(new ScopeRef(ScopeKind.REGION,REGION.value()),new Revision(2),new Tick(2),
                new RegionMemoryDetailData(REGION,RegionMemoryDetailData.Section.REGION,0,1,
                    Optional.of(region),Optional.empty(),Set.of(),List.of(),List.of()));
            bytes=new ModelCodec<>(AdminRow.class).encode(complete);fixtureBytes=bytes.length;hash=BlobContent.hash(bytes);
            check(bytes.length>2*MemoryDocumentPageData.MAX_FRAGMENT_BYTES&&bytes.length<=3*MemoryDocumentPageData.MAX_FRAGMENT_BYTES,
                "real encoded600anchor RegionRecord needs exactly3 legal fragments");
            page.reset(NETWORK);
            var first=query(Optional.empty(),Optional.empty());page.begin(first);
            check(page.receive(NETWORK,snapshot(first.requestId(),0,CURSOR_ONE,CUT)),"first encoded fragment accepted");
            check(page.assemblingDocument()&&page.documentContinuation().orElseThrow().equals(CURSOR_ONE),"partial row remains explicitly incomplete");
        }
        AdminSnapshot snapshot(RequestId rid,int index,String next,Sequence cut){
            int start=index*MemoryDocumentPageData.MAX_FRAGMENT_BYTES;
            int end=Math.min(bytes.length,start+MemoryDocumentPageData.MAX_FRAGMENT_BYTES);
            var fragment=new MemoryDocumentPageData(hash,0,17,bytes.length,start,
                Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,start,end)));
            var row=new AdminRow(complete.scope(),complete.revision(),complete.capturedTick(),fragment);
            return new AdminSnapshot(rid,REVISION,CAPTURED,cut,List.of(row),Optional.ofNullable(next),Set.of(AdminOperation.STATUS));
        }
        AdminQuery next(String cursor){return query(Optional.of(cursor),Optional.of(REVISION));}
        AdminQuery begin(String cursor){var q=next(cursor);page.beginContinuation(q);return q;}
        void rate(AdminQuery q,long tick){
            if(!page.deferRateLimitedContinuation(NETWORK,rateResult(q),tick,40))
                check(page.reject(NETWORK,q.requestId(),ReasonCode.RATE_LIMITED,tick,40),"terminal rate refusal handled");
            else check(true,"typed correlated native rate refusal deferred");
        }
        AdminQuery retry(long tick,long seconds){
            nanos.addAndGet(seconds*SECOND);
            var q=page.continuationRetryQuery(requestId(),tick).orElseThrow();page.beginContinuation(q);return q;
        }
    }
    private static AdminCommandResult rateResult(AdminQuery query){
        return new AdminCommandResult(query.requestId(),AdminStatus.REJECTED,List.of(ReasonCode.RATE_LIMITED),Optional.empty(),Optional.empty());
    }
    private static void nativeTransport39Versus40(){
        var serverTicks=new AtomicLong(0);
        var transport=new PerceptionTransport(NETWORK.worldId(),NETWORK.bootEpoch(),Set.of(),Map.of(),
            new PerceptionTransport.Limits(2,8,100,120,32,32,40),serverTicks::get,(player,payload)->{});
        var session=transport.open(NETWORK.playerId(),NETWORK.dimension());
        check(session.equals(NETWORK),"actual transport creates fixture connection identity");
        check(transport.capabilities(NETWORK.playerId(),new Capabilities(1,session.connectionEpoch(),Set.of(),Map.of())),"actual J06 capability handshake accepted");
        check(transport.adminQuery(NETWORK.playerId(),ACTOR),"actual server admits first authenticated query at server tick0");
        var f=new Fixture();var continuation=f.begin(CURSOR_ONE);serverTicks.set(39);
        check(!transport.adminQuery(NETWORK.playerId(),ACTOR),"actual transport refuses client40tick continuation after only39server ticks");
        check(f.page.deferRateLimitedContinuation(NETWORK,rateResult(continuation),40,40),"typed authoritative quota result preserves partial document");
        check(f.page.assemblingDocument(),"actual39tick refusal does not erase first fragment");
        serverTicks.set(40);
        check(transport.adminQuery(NETWORK.playerId(),ACTOR),"actual unchanged40server tick quota admits at exact fence");
        var retry=f.retry(80,2);sameContinuation(continuation,retry);serverTicks.set(80);
        check(transport.adminQuery(NETWORK.playerId(),ACTOR),"bounded later retry respects unchanged native quota");
        check(f.page.receive(NETWORK,f.snapshot(retry.requestId(),1,CURSOR_TWO,CUT)),"admitted retry joins preserved document");
        var last=f.begin(CURSOR_TWO);serverTicks.set(120);
        check(transport.adminQuery(NETWORK.playerId(),ACTOR)&&f.page.receive(NETWORK,f.snapshot(last.requestId(),2,null,CUT)),"next native-admitted fragment completes semantic document");
        check(f.page.snapshot().orElseThrow().rows().equals(List.of(f.complete)),"native transport fence regression ends with full600anchor typed row");
    }
    private static void typedReceiptOnly(){
        for(var status:List.of(AdminStatus.DEFERRED,AdminStatus.FAILED_SAFE,AdminStatus.SUCCEEDED)){
            var f=new Fixture();var q=f.begin(CURSOR_ONE);
            var result=new AdminCommandResult(q.requestId(),status,List.of(ReasonCode.RATE_LIMITED),Optional.empty(),Optional.empty());
            check(!f.page.deferRateLimitedContinuation(NETWORK,result,100,40)&&f.page.pending(),"RATE text alone cannot schedule retry for status"+status);
        }
        var f=new Fixture();var q=f.begin(CURSOR_ONE);
        var mixed=new AdminCommandResult(q.requestId(),AdminStatus.REJECTED,List.of(ReasonCode.RATE_LIMITED,ReasonCode.AUTH_DENIED),Optional.empty(),Optional.empty());
        check(!f.page.deferRateLimitedContinuation(NETWORK,mixed,100,40)&&f.page.pending(),"mixed reasons cannot acquire quota-only retry");
        check(f.page.reject(NETWORK,q.requestId(),ReasonCode.RATE_LIMITED,100,40)&&!f.page.assemblingDocument(),"generic refusal API remains terminal even forRATE");
    }
    private static void evidenceView(){
        var f=new Fixture();check(f.page.continuationRetryView().isEmpty(),"no retry evidence before genuine quota refusal");
        var first=f.begin(CURSOR_ONE);f.rate(first,100);var scheduled=f.page.continuationRetryView().orElseThrow();
        check(scheduled.session().equals(NETWORK)&&scheduled.refusedQuery().equals(first)&&scheduled.retryRequestId().isEmpty(),"scheduled view binds exact session and refused query without invented retryID");
        check(scheduled.retryOrdinal()==1&&scheduled.maximumRetries()==3&&scheduled.observedNanos()==0
            &&scheduled.retryNotBeforeNanos()==2*SECOND&&scheduled.deadlineNanos()==30*SECOND&&scheduled.backoffNanos()==2*SECOND,"scheduled view reports exact bounded client monotonic times");
        check(scheduled.documentHash().equals(f.hash)&&scheduled.documentIndex()==0&&scheduled.documentCount()==17
            &&scheduled.documentBytes()==f.bytes.length&&scheduled.receivedBytes()==MemoryDocumentPageData.MAX_FRAGMENT_BYTES,"scheduled view describes exact incomplete document and retained bytes");
        check(scheduled.snapshotRevision().equals(REVISION)&&scheduled.capturedTick().equals(CAPTURED)&&scheduled.lastDurableSequence().equals(CUT),"scheduled evidence preserves complete original cut tuple");
        var retry=f.retry(140,2);var inflight=f.page.continuationRetryView().orElseThrow();
        check(inflight.refusedQuery().equals(first)&&inflight.retryRequestId().equals(Optional.of(retry.requestId()))
            &&inflight.receivedBytes()==scheduled.receivedBytes()&&inflight.deadlineNanos()==scheduled.deadlineNanos(),"inflight view correlates freshretry to unchanged refusedtuple and partialbytes");
        check(f.page.receive(NETWORK,f.snapshot(retry.requestId(),1,CURSOR_TWO,CUT)),"view fixture middle fragment accepted");
        check(f.page.continuationRetryView().isEmpty(),"successful fragment ends old refusedcursor evidenceepisode");
        var last=f.begin(CURSOR_TWO);f.rate(last,200);var second=f.page.continuationRetryView().orElseThrow();
        check(second.refusedQuery().equals(last)&&second.retryOrdinal()==2&&second.backoffNanos()==4*SECOND
            &&second.receivedBytes()==2*MemoryDocumentPageData.MAX_FRAGMENT_BYTES&&second.deadlineNanos()==32*SECOND,"new episode retains perdocument retrybudget and actual accumulated byteoffset");
        var lastRetry=f.retry(240,4);check(f.page.receive(NETWORK,f.snapshot(lastRetry.requestId(),2,null,CUT)),"view fixture document complete");
        check(f.page.continuationRetryView().isEmpty(),"semanticcompletion clears all retry evidence");
        var expired=new Fixture();var exp=expired.begin(CURSOR_ONE);expired.rate(exp,100);expired.nanos.set(30*SECOND);
        check(expired.page.expireAutomaticContinuation()&&expired.page.continuationRetryView().isEmpty(),"timeout clears refusedtuple and partialbyte evidence");
    }
    private static void sameContinuation(AdminQuery original,AdminQuery retry){
        check(!original.requestId().equals(retry.requestId()),"retry has fresh request identity");
        check(original.actorSession().equals(retry.actorSession())&&original.panel()==retry.panel()
            &&original.filter().equals(retry.filter())&&original.cursor().equals(retry.cursor())
            &&original.expectedSnapshotRevision().equals(retry.expectedSnapshotRevision())&&original.limit()==retry.limit(),
            "retry preserves exact actor/filter/cursor/revision/page bound");
    }
    private static void completeAfterRate(){
        var f=new Fixture();var original=f.begin(CURSOR_ONE);f.rate(original,100);
        check(f.page.continuationRetrying()&&f.page.assemblingDocument()&&!f.page.pending(),"rate preserves bounded partial document for typed retry");
        check(f.page.snapshot().isPresent()&&!f.page.needsQuery(),"rate retains cut-bearing base without fresh search loop");
        check(!f.page.receive(NETWORK,f.snapshot(original.requestId(),1,CURSOR_TWO,CUT)),"late reply to refused identity ignored");
        var retry=f.retry(140,2);sameContinuation(original,retry);
        check(f.page.receive(NETWORK,f.snapshot(retry.requestId(),1,CURSOR_TWO,CUT)),"second fragment joins preserved first fragment");
        check(f.page.assemblingDocument()&&f.page.documentContinuation().orElseThrow().equals(CURSOR_TWO),"last fragment remains to read");
        var last=f.begin(CURSOR_TWO);
        check(f.page.receive(NETWORK,f.snapshot(last.requestId(),2,null,CUT)),"third fragment completes original hash verified document");
        var snapshot=f.page.snapshot().orElseThrow();
        check(snapshot.rows().equals(List.of(f.complete))&&!f.page.assemblingDocument(),"complete semantic600anchor row replaces all transport fragments");
        check(!f.page.continuationRetrying()&&f.page.loadingContinuation().isEmpty()&&f.page.failureReason().isEmpty(),"completion leaves no hidden retry or fragment cursor");
    }
    private static void independentTickAndMonotonicFences(){
        var f=new Fixture();var q=f.begin(CURSOR_ONE);f.rate(q,100);
        check(f.page.continuationRetryQuery(requestId(),140).isEmpty(),"client40ticks alone cannot skip monotonic backoff");
        f.nanos.set(2*SECOND-1);
        check(f.page.continuationRetryQuery(requestId(),10000).isEmpty(),"even accelerated client ticks cannot shorten2second delay");
        f.nanos.set(2*SECOND);
        check(f.page.continuationRetryQuery(requestId(),139).isEmpty(),"wall time alone cannot skip existing tick fence");
        check(f.page.continuationRetryQuery(requestId(),140).isPresent(),"both independent fences satisfied");
    }
    private static void retryBudgetAcrossFragments(){
        var f=new Fixture();var one=f.begin(CURSOR_ONE);f.rate(one,100);
        var retryOne=f.retry(140,2);
        check(f.page.receive(NETWORK,f.snapshot(retryOne.requestId(),1,CURSOR_TWO,CUT)),"successful middle fragment does not finish document");
        var two=f.begin(CURSOR_TWO);f.rate(two,200);
        f.nanos.addAndGet(4*SECOND-1);
        check(f.page.continuationRetryQuery(requestId(),240).isEmpty(),"same document second rate uses4second backoff");
        f.nanos.incrementAndGet();var retryTwo=f.page.continuationRetryQuery(requestId(),240).orElseThrow();
        f.page.beginContinuation(retryTwo);f.rate(retryTwo,300);
        f.nanos.addAndGet(8*SECOND-1);
        check(f.page.continuationRetryQuery(requestId(),340).isEmpty(),"same document third rate uses8second backoff");
        f.nanos.incrementAndGet();var retryThree=f.page.continuationRetryQuery(requestId(),340).orElseThrow();
        f.page.beginContinuation(retryThree);f.rate(retryThree,400);
        check(!f.page.continuationRetrying()&&!f.page.assemblingDocument()&&f.page.snapshot().isEmpty(),"fourth rate refusal exhausts3retry budget and releases assembly");
        check(!f.page.needsQuery()&&f.page.continuationRetryQuery(requestId(),10000).isEmpty(),"exhaustion cannot start automatic retry storm");
    }
    private static void deadlineTerminates(){
        var f=new Fixture();var q=f.begin(CURSOR_ONE);f.rate(q,100);f.nanos.set(30*SECOND);
        check(f.page.continuationRetryQuery(requestId(),10000).isEmpty(),"30second monotonic lifetime is bounded");
        check(!f.page.assemblingDocument()&&!f.page.continuationRetrying()&&f.page.snapshot().isEmpty(),"expired document bytes and cursor released");
        check(!f.page.needsQuery()&&f.page.failureReason().orElseThrow()==ReasonCode.STALE_CONTEXT,"expired continuation explicitly stale, no fake success or requery");
        check(!f.page.receive(NETWORK,f.snapshot(q.requestId(),1,CURSOR_TWO,CUT)),"expired original reply cannot revive assembly");
    }
    private static void expiryInFlightAndDispatch(){
        var f=new Fixture();var q=f.begin(CURSOR_ONE);f.rate(q,100);var retry=f.retry(140,2);f.nanos.set(30*SECOND);
        check(!f.page.receive(NETWORK,f.snapshot(retry.requestId(),1,CURSOR_TWO,CUT)),"inflight retry success at monotonic deadline cannot revive expired document");
        check(!f.page.pending()&&!f.page.assemblingDocument()&&f.page.failureReason().orElseThrow()==ReasonCode.STALE_CONTEXT,"inflight expiry has terminal bounded outcome");
        var expired=new Fixture();var original=expired.begin(CURSOR_ONE);expired.rate(original,100);expired.nanos.set(2*SECOND);
        var candidate=expired.page.continuationRetryQuery(requestId(),140).orElseThrow();expired.nanos.set(30*SECOND);
        var sent=new ArrayList<AdminQuery>();
        check(!AdminQueryDispatch.sendContinuation(expired.page,NETWORK,candidate,sent::add,140,40),"expired dispatch refuses without exception");
        check(sent.isEmpty()&&!expired.page.assemblingDocument()&&expired.page.failureReason().orElseThrow()==ReasonCode.STALE_CONTEXT,"expired query is never sent or assembled");
        for(int read=3;read<=4;read++){
            final int boundary=read;var clock=new AtomicLong(0);var calls=new AtomicLong(-1);
            var race=new Fixture(()->calls.get()<0?clock.get():calls.incrementAndGet()>=boundary?30*SECOND:2*SECOND);
            var first=race.begin(CURSOR_ONE);race.rate(first,100);clock.set(2*SECOND);
            var ready=race.page.continuationRetryQuery(requestId(),140).orElseThrow();calls.set(0);
            var racedSent=new ArrayList<AdminQuery>();
            check(!AdminQueryDispatch.sendContinuation(race.page,NETWORK,ready,racedSent::add,140,40),"deadline crossed inside dispatch at clockread"+read+" returns false");
            check(racedSent.isEmpty()&&!race.page.pending()&&!race.page.assemblingDocument(),"dispatch clockrace sends no stale cursor and retains no bytes boundary"+read);
        }
    }
    private static void invalidTuple(Fixture f,AdminQuery query,String why){
        try{f.page.beginContinuation(query);throw new AssertionError("accepted altered continuation: "+why);}
        catch(IllegalArgumentException expected){check(true,"typed continuation refuses altered "+why);}
        check(!f.page.pending()&&f.page.assemblingDocument(),"altered tuple cannot destroy or consume authentic partial "+why);
    }
    private static void exactTupleFence(){
        var f=new Fixture();
        invalidTuple(f,new AdminQuery(new SessionId(new UUID(99,1)),requestId(),AdminPanel.MEMORY,FILTER,Optional.of(CURSOR_ONE),Optional.of(REVISION),1),"actor");
        invalidTuple(f,query(Optional.of("different-cursor"),Optional.of(REVISION)),"cursor");
        invalidTuple(f,query(Optional.of(CURSOR_ONE),Optional.of(new Revision(8))),"revision");
        invalidTuple(f,new AdminQuery(ACTOR,requestId(),AdminPanel.MEMORY,FILTER,Optional.of(CURSOR_ONE),Optional.of(REVISION),2),"page bound");
        invalidTuple(f,new AdminQuery(ACTOR,requestId(),AdminPanel.MEMORY,new RegionFilter(Optional.of(new RegionId(new UUID(99,2))),Set.of()),Optional.of(CURSOR_ONE),Optional.of(REVISION),1),"selected region");
        invalidTuple(f,new AdminQuery(ACTOR,requestId(),AdminPanel.WORLD,new WorldFilter(),Optional.of(CURSOR_ONE),Optional.of(REVISION),1),"panel");
        var original=f.begin(CURSOR_ONE);f.rate(original,100);f.nanos.set(2*SECOND);
        try{f.page.continuationRetryQuery(original.requestId(),140);throw new AssertionError("retry reused rejectedID");}
        catch(IllegalArgumentException expected){check(true,"retry cannot reuse authoritative rejected requestID");}
        invalidTuple(f,query(Optional.of("altered-after-rate"),Optional.of(REVISION)),"retry cursor");
        var sent=new ArrayList<AdminQuery>();var retry=f.page.continuationRetryQuery(requestId(),140).orElseThrow();
        check(!AdminQueryDispatch.sendContinuation(f.page,NETWORK,retry,sent::add,139,40)&&sent.isEmpty(),"typed dispatcher enforces tick fence without submitting query");
        check(AdminQueryDispatch.sendContinuation(f.page,NETWORK,retry,sent::add,140,40)&&sent.equals(List.of(retry)),"typed dispatcher sends exact due retry once");
    }
    private static void successfulFragmentStartsNewDeadline(){
        var f=new Fixture();var q=f.begin(CURSOR_ONE);f.rate(q,100);
        var retry=f.retry(140,29);
        check(f.page.receive(NETWORK,f.snapshot(retry.requestId(),1,CURSOR_TWO,CUT)),"success before30sec ends prior rejected cursor episode");
        var last=f.begin(CURSOR_TWO);f.rate(last,200);f.nanos.set(33*SECOND);
        var lastRetry=f.page.continuationRetryQuery(requestId(),240).orElseThrow();
        check(f.page.beginContinuation(lastRetry)&&f.page.receive(NETWORK,f.snapshot(lastRetry.requestId(),2,null,CUT)),"new cursor episode may complete beyond prior30sec deadline");
        check(f.page.snapshot().orElseThrow().rows().equals(List.of(f.complete)),"deadline renewal retains document budget and semantic identity");
    }
    private static void correlatedOnly(){
        var f=new Fixture();var q=f.begin(CURSOR_ONE);
        var foreign=new SessionIdentity(NETWORK.worldId(),NETWORK.bootEpoch(),new Epoch(2),NETWORK.dimension(),NETWORK.playerId());
        check(!f.page.reject(foreign,q.requestId(),ReasonCode.RATE_LIMITED,100,40)&&f.page.pending(),"foreign connection cannot schedule retry");
        check(!f.page.reject(NETWORK,requestId(),ReasonCode.RATE_LIMITED,100,40)&&f.page.pending(),"foreign request cannot schedule retry");
        f.rate(q,100);var retry=f.retry(140,2);
        check(!f.page.reject(NETWORK,q.requestId(),ReasonCode.RATE_LIMITED,150,40)&&f.page.pending(),"old refusal cannot cancel fresh retry");
        check(!f.page.receive(NETWORK,f.snapshot(q.requestId(),1,CURSOR_TWO,CUT))&&f.page.pending(),"old success cannot consume new request");
        check(f.page.receive(NETWORK,f.snapshot(retry.requestId(),1,CURSOR_TWO,CUT)),"fresh correlated retry remains valid");
    }
    private static void manualAndOtherRefusals(){
        for(var reason:List.of(ReasonCode.STALE_CONTEXT,ReasonCode.AUTH_DENIED,ReasonCode.INTERNAL_FAULT)){
            var f=new Fixture();var q=f.begin(CURSOR_ONE);
            check(f.page.reject(NETWORK,q.requestId(),reason,100,40),"terminal refusal handled "+reason);
            check(!f.page.assemblingDocument()&&!f.page.continuationRetrying()&&f.page.snapshot().isEmpty()
                &&!f.page.needsQuery(),"nonrate refusal clears partial document without auto retry "+reason);
        }
        var manual=new Fixture();var cursor=manual.next(CURSOR_ONE);manual.page.begin(cursor);manual.rate(cursor,100);
        check(!manual.page.continuationRetrying()&&!manual.page.assemblingDocument(),"manual begin of same cursor is not typed automatic continuation");
        var plain=new AdminPageState();plain.reset(NETWORK);
        var world=new AdminQuery(ACTOR,requestId(),AdminPanel.WORLD,new WorldFilter(),Optional.of("world-cursor"),Optional.of(REVISION),1);
        plain.begin(world);check(plain.reject(NETWORK,world.requestId(),ReasonCode.RATE_LIMITED,100,40),"manual WORLD rate handled");
        check(!plain.continuationRetrying()&&plain.continuationRetryQuery(requestId(),10000).isEmpty()&&!plain.needsQuery(),"WORLD cursor retains explicit manual retry contract");
    }
    private static void resetCloseAndTransportFailure(){
        for(int mode=0;mode<3;mode++){
            var f=new Fixture();var q=f.begin(CURSOR_ONE);f.rate(q,100);
            if(mode==0)f.page.invalidate();else if(mode==1)f.page.reset(NETWORK);else f.page.close();
            f.nanos.set(2*SECOND);
            check(!f.page.assemblingDocument()&&!f.page.continuationRetrying()
                &&f.page.continuationRetryQuery(requestId(),140).isEmpty(),"context lifecycle releases retry mode"+mode);
            check(!f.page.receive(NETWORK,f.snapshot(q.requestId(),1,CURSOR_TWO,CUT)),"late prior context reply ignored mode"+mode);
        }
        var f=new Fixture();var query=f.next(CURSOR_ONE);
        check(!AdminQueryDispatch.sendContinuation(f.page,NETWORK,query,ignored->{throw new IllegalStateException("declared transport fault");},100,40),"typed continuation dispatch reports transport failure");
        check(!f.page.pending()&&!f.page.assemblingDocument()&&!f.page.continuationRetrying()&&!f.page.needsQuery(),"transport failure never enters serverRATE retry path");
        check(f.page.failureReason().orElseThrow()==ReasonCode.INTERNAL_FAULT,"transport failure remains explicit local fault");
    }
    private static void mixedCutRejectedAfterRetry(){
        var f=new Fixture();var q=f.begin(CURSOR_ONE);f.rate(q,100);var retry=f.retry(140,2);
        check(!f.page.receive(NETWORK,f.snapshot(retry.requestId(),1,CURSOR_TWO,CUT.plus(1))),"retry cannot mix durable cuts despite same cursor and revision");
        check(!f.page.assemblingDocument()&&f.page.snapshot().isEmpty(),"mixed cut releases all fragment state");
    }
}
