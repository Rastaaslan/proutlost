package fr.proutlost.director.admin.server;
import fr.proutlost.director.adapter.neoforge.network.AdminQueryReplyFence.Reply;
import fr.proutlost.director.api.error.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class AsyncColdPlayerReadRegressionTest {
 private static int assertions;
 private static void check(boolean yes,String why){assertions++;if(!yes)throw new AssertionError(why);}
 private static final SessionId SESSION=new SessionId(UUID.fromString("948bd265-bf6f-47c6-9876-2794c97a7d29"));
 private static final PlayerId PLAYER=new PlayerId(UUID.fromString("93094eda-b7a6-39e1-ba24-b4e8ef1d05fd"));
 private static AdminQuery query(){return new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),AdminPanel.MEMORY,new PlayerFilter(PLAYER),Optional.empty(),Optional.empty(),25);}
 private static Reply stale(AdminQuery q){return new Reply(Optional.empty(),Optional.of(new AdminCommandResult(q.requestId(),AdminStatus.DEFERRED,List.of(ReasonCode.STALE_CONTEXT),Optional.empty(),Optional.empty())));}
 private static Reply ok(AdminQuery q){return new Reply(Optional.of(new AdminSnapshot(q.requestId(),new Revision(7),new Tick(11),new Sequence(13),List.of(),Optional.empty(),Set.of())),Optional.empty());}
 private static void drain(Queue<Runnable> q){for(int i=0;i<32&&!q.isEmpty();i++)q.remove().run();check(q.isEmpty(),"bounded actor queue drained");}
 private static void callbackAndMutableHeads()throws Exception{
  var q=query();var mailbox=new ConcurrentLinkedQueue<Runnable>();var tick=new AtomicInteger();var authorized=new AtomicBoolean(true);
  var owner=Thread.currentThread();var evaluations=new AtomicInteger();var work=new AtomicReference<CompletableFuture<Void>>(new CompletableFuture<>());var delivered=new ArrayList<Reply>();
  var gate=new DeferredAdminReadGate(32,40,4,tick::get,mailbox::add);
  check(gate.await(PLAYER,q,stale(q),()->{check(Thread.currentThread()==owner,"authority read only on actor");return authorized.get();},
   ()->{check(Thread.currentThread()==owner,"J04 recheck only on actor");return evaluations.incrementAndGet()==1?stale(q):ok(q);},
   ()->Optional.of(work.get()),delivered::add),"original admitted request waits for its actual pending work");
  var first=work.get();work.set(new CompletableFuture<>());
  Thread complete=new Thread(()->first.complete(null));complete.start();complete.join();
  check(evaluations.get()==0&&delivered.isEmpty(),"IO callback cannot read authority or publish");
  drain(mailbox);check(delivered.isEmpty()&&gate.pendingCount()==1,"changed heads are not published; fresh load awaited");
  work.get().complete(null);drain(mailbox);
  check(delivered.size()==1&&delivered.getFirst().snapshot().orElseThrow().requestId().equals(q.requestId()),"fresh result completes original exact request");
  check(gate.pendingCount()==0,"completed ticket released");
 }
 private static void revocationExpiryReplacement(){
  var mailbox=new ArrayDeque<Runnable>();var tick=new AtomicInteger();var auth=new AtomicBoolean(true);var result=new ArrayList<Reply>();
  var q=query();var work=new CompletableFuture<Void>();var eval=new AtomicInteger();var gate=new DeferredAdminReadGate(1,40,4,tick::get,mailbox::add);
  check(gate.await(PLAYER,q,stale(q),auth::get,()->{eval.incrementAndGet();return ok(q);},()->Optional.of(work),result::add),"one ticket");
  check(!gate.await(new PlayerId(UUID.randomUUID()),query(),stale(q),auth::get,()->ok(q),()->Optional.of(work),result::add),"capacity cannot be borrowed");
  auth.set(false);work.complete(null);drain(mailbox);
  check(result.isEmpty()&&eval.get()==0&&gate.pendingCount()==0,"revoked session/role cannot read or receive data");
  auth.set(true);var pending=new CompletableFuture<Void>();var q2=query();
  check(gate.await(PLAYER,q2,stale(q2),auth::get,()->ok(q2),()->Optional.of(pending),result::add),"new current request admitted");
  tick.set(40);gate.expire();check(result.size()==1&&DeferredAdminReadGate.awaitingData(result.getFirst()),"same40tick deadline returns correlated refusal");
  pending.complete(null);drain(mailbox);check(result.size()==1,"late completion cannot duplicate expired delivery");
  result.clear();tick.set(41);var old=new CompletableFuture<Void>();var oldQ=query();
  check(gate.await(PLAYER,oldQ,stale(oldQ),auth::get,()->ok(oldQ),()->Optional.of(old),result::add),"old current ticket");
  gate.cancel(PLAYER);var newer=new CompletableFuture<Void>();var newQ=query();
  check(gate.await(PLAYER,newQ,stale(newQ),auth::get,()->ok(newQ),()->Optional.of(newer),result::add),"explicitly admitted replacement");
  old.complete(null);drain(mailbox);check(result.isEmpty(),"replaced request cannot publish");
  newer.complete(null);drain(mailbox);check(result.size()==1&&result.getFirst().snapshot().orElseThrow().requestId().equals(newQ.requestId()),"only replacement can publish");
 }
 private static void refusalBoundsAndTickWrap(){
  var mailbox=new ArrayDeque<Runnable>();var tick=new AtomicInteger(Integer.MAX_VALUE-10);var result=new ArrayList<Reply>();var q=query();
  var gate=new DeferredAdminReadGate(32,40,4,tick::get,mailbox::add);
  check(gate.await(PLAYER,q,stale(q),()->true,()->stale(q),()->Optional.of(CompletableFuture.completedFuture(null)),result::add),"bounded completed-work loop");
  drain(mailbox);check(result.size()==1&&DeferredAdminReadGate.awaitingData(result.getFirst())&&gate.pendingCount()==0,"four failed loads end safely without retry loop");
  result.clear();var future=new CompletableFuture<Void>();var q2=query();
  check(gate.await(PLAYER,q2,stale(q2),()->true,()->ok(q2),()->Optional.of(future),result::add),"tick wrap ticket");
  tick.set(Integer.MIN_VALUE+9);future.complete(null);drain(mailbox);check(result.size()==1&&result.getFirst().snapshot().isPresent(),"short deadline uses defined int tick wrap");
  result.clear();var q3=query();var failure=new DeferredAdminReadGate(32,40,4,tick::get,r->{throw new RejectedExecutionException();});
  check(failure.await(PLAYER,q3,stale(q3),()->true,()->ok(q3),()->Optional.of(CompletableFuture.completedFuture(null)),result::add),"admitted ticket marks rejected notification only");failure.expire();
  check(failure.pendingCount()==0&&result.size()==1&&DeferredAdminReadGate.awaitingData(result.getFirst()),"actor expires rejected mailbox safely without forged data");
 }
 private static void exactChargedProductionAdmission(){
  var tick=new AtomicLong();var actor=new AtomicReference<>(new ActorIdentity(SESSION,ActorKind.PLAYER,Optional.of(PLAYER),Optional.empty(),4,true));
  var calls=new AtomicInteger();var fresh=new AtomicBoolean(false);
  AdminPort downstream=new AdminPort(){
   public AdminReadResult query(AdminQuery q,ActorIdentity a){
    calls.incrementAndGet();
    return fresh.get()?new AdminReadResult(AdminStatus.SUCCEEDED,List.of(),Optional.empty(),Optional.empty(),
      Optional.of(new AdminSnapshotStamp(new WorldId(UUID.fromString("011e910f-674e-4464-81fd-bfb1dbf060b5")),new Epoch(17),new Revision(7),new Tick(11),new Sequence(13)))):
      new AdminReadResult(AdminStatus.DEFERRED,List.of(),Optional.of(new SystemError(ReasonCode.STALE_CONTEXT,Severity.INFO,false,Optional.empty(),Set.of(),new CorrelationId(q.requestId().value()),SafeState.UNCHANGED)),Optional.empty(),Optional.empty());
   }
   public CompletionStage<AdminResult> submit(AdminCommand q,ActorIdentity a){throw new AssertionError("no command/effect path");}
  };
  var guard=new AdminAccessGuard(downstream,s->s.equals(SESSION)?Optional.of(actor.get()):Optional.empty(),tick::get,Runnable::run,256,128,2,40,20);
  var q=query();check(guard.query(q,actor.get()).status()==AdminStatus.DEFERRED,"original read admission charged");
  tick.set(1);check(guard.query(q,actor.get()).error().orElseThrow().code()==ReasonCode.RATE_LIMITED,"normal wire retry still rate limited");
  check(guard.continueRead(query(),actor.get()).error().orElseThrow().code()==ReasonCode.STALE_CONTEXT&&calls.get()==1,"new request receives no admission credit");
  var foreign=new AdminQuery(q.actorSession(),q.requestId(),q.panel(),new PlayerFilter(new PlayerId(UUID.randomUUID())),q.cursor(),q.expectedSnapshotRevision(),q.limit());
  check(guard.continueRead(foreign,actor.get()).error().orElseThrow().code()==ReasonCode.STALE_CONTEXT&&calls.get()==1,"changed target receives no credit");
  actor.set(new ActorIdentity(SESSION,ActorKind.PLAYER,Optional.of(PLAYER),Optional.empty(),3,true));
  check(guard.continueRead(q,actor.get()).error().orElseThrow().code()==ReasonCode.AUTH_DENIED&&calls.get()==1,"current role is rechecked");
  actor.set(new ActorIdentity(SESSION,ActorKind.PLAYER,Optional.of(PLAYER),Optional.empty(),4,true));fresh.set(true);
  check(guard.continueRead(q,actor.get()).status()==AdminStatus.SUCCEEDED&&calls.get()==2,"exact charged read continues without second debit");
  check(guard.continueRead(q,actor.get()).error().orElseThrow().code()==ReasonCode.STALE_CONTEXT&&calls.get()==2,"completed credit cannot be replayed");
  fresh.set(false);tick.set(40);var next=query();guard.query(next,actor.get());int before=calls.get();
  tick.set(80);check(guard.continueRead(next,actor.get()).error().orElseThrow().code()==ReasonCode.STALE_CONTEXT&&calls.get()==before,"expired exact admission is refused");
 }
 public static void main(String[]args)throws Exception{
  callbackAndMutableHeads();revocationExpiryReplacement();refusalBoundsAndTickWrap();exactChargedProductionAdmission();
  System.out.println("ASYNC_COLD_PLAYER_READ_REGRESSION_PASS assertions="+assertions+" native_qualified=false");
 }
}
