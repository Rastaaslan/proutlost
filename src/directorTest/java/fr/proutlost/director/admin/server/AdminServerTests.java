package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.api.error.ReasonCode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.nio.file.*;

public final class AdminServerTests {
    private static int checks;
    private static final UUID ID=new UUID(0,1);
    private static final SessionId SESSION=new SessionId(ID);
    private static final WorldId WORLD=new WorldId(ID);
    private static final Revision REV=new Revision(1);
    private static final Tick TICK=new Tick(10);
    private static final Fingerprint HASH=new Fingerprint("0".repeat(64));
    public static void main(String[] args) throws Exception { permissions();saturatedDelivery();qualifiedPorts();requestLifecycle();pages();disasterStateFilter();codecItemPages();facadeRefusal();queryFailClosed();calculations();System.out.println("PASS AdminServerTests: "+checks+" assertions; runtime effects/native integration NOT_RUN"); }
    private static ActorIdentity actor(int level) {return new ActorIdentity(SESSION,ActorKind.PLAYER,Optional.of(new PlayerId(ID)),Optional.empty(),level,true);}
    private static AdminCommand command(AdminOperation op,AdminPayload payload) {return new AdminCommand(SESSION,new RequestId(UUID.randomUUID()),REV,op,payload);}
    private static final class Queue implements Executor {final ArrayDeque<Runnable> tasks=new ArrayDeque<>();public void execute(Runnable task){tasks.add(task);}void drain(){while(!tasks.isEmpty())tasks.remove().run();}}
    private static void permissions() {
        var live=new AtomicReference<>(actor(4));var tick=new AtomicLong();var calls=new AtomicInteger();var queue=new Queue();
        AdminPort backend=new AdminPort(){
            public AdminReadResult query(AdminQuery q,ActorIdentity a){calls.incrementAndGet();return new AdminReadResult(AdminStatus.SUCCEEDED,List.of(),Optional.empty(),Optional.empty());}
            public CompletionStage<AdminResult> submit(AdminCommand c,ActorIdentity a){calls.incrementAndGet();return CompletableFuture.completedFuture(new AdminResult(AdminStatus.SUCCEEDED,c.requestId(),REV,Optional.empty(),Optional.empty()));}
        };
        var guard=new AdminAccessGuard(backend,s->Optional.ofNullable(live.get()),tick::get,queue,2,20,2,40,1200);
        var pause=command(AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));
        var pending=guard.submit(pause,actor(4));check(!pending.toCompletableFuture().isDone(),"queued not succeeded");
        live.set(actor(0));queue.drain();check(code(pending)==ReasonCode.AUTH_DENIED&&calls.get()==0,"revocation before application");
        var player=command(AdminOperation.PLAYER,new PlayerInspectionPayload(new PlayerId(ID)));
        check(code(guard.submit(player,actor(4)))==ReasonCode.AUTH_DENIED,"supplied level cannot override server");
        live.set(actor(4));tick.set(20);
        var second=command(AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));
        var a=guard.submit(second,actor(4));var b=guard.submit(second,actor(4));queue.drain();
        check(a.toCompletableFuture().join().status()==AdminStatus.SUCCEEDED&&b.toCompletableFuture().join().equals(a.toCompletableFuture().join())&&calls.get()==1,"idempotent one dispatch");
        var conflict=new AdminCommand(SESSION,second.requestId(),new Revision(2),second.operation(),second.payload());
        check(code(guard.submit(conflict,actor(4)))==ReasonCode.IDEMPOTENCY_CONFLICT,"conflicting replay");
        var third=command(AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));guard.submit(third,actor(4));
        check(code(guard.submit(command(AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED)),actor(4)))==ReasonCode.RATE_LIMITED,"command quota");queue.drain();
        var q=new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),AdminPanel.WORLD,new WorldFilter(),Optional.empty(),Optional.empty(),50);
        check(guard.query(q,actor(4)).status()==AdminStatus.SUCCEEDED,"admin query");
        check(guard.query(q,actor(4)).error().orElseThrow().code()==ReasonCode.RATE_LIMITED,"refresh quota");
        tick.addAndGet(40);live.set(actor(2));check(guard.query(q,actor(4)).error().orElseThrow().code()==ReasonCode.AUTH_DENIED,"moderator no detailed panel");
        var rescue=command(AdminOperation.RESCUE,new RescuePayload(Optional.of(new PlayerId(UUID.randomUUID())),false));
        var rescueResult=guard.submit(rescue,actor(2));queue.drain();check(rescueResult.toCompletableFuture().join().status()==AdminStatus.SUCCEEDED,"moderator rescue");
        tick.addAndGet(20);live.set(actor(0));
        check(code(guard.submit(rescue,actor(0)))==ReasonCode.AUTH_DENIED,"player cannot rescue others");
        var self=guard.submit(command(AdminOperation.RESCUE,new RescuePayload(Optional.empty(),true)),actor(0));queue.drain();check(self.toCompletableFuture().join().status()==AdminStatus.SUCCEEDED,"self cleanup");
        var report=guard.submit(command(AdminOperation.REPORT,new ReportPayload("help",Set.of())),actor(0));queue.drain();check(report.toCompletableFuture().join().status()==AdminStatus.SUCCEEDED,"player report");
        tick.addAndGet(20);check(code(guard.submit(command(AdminOperation.REPORT,new ReportPayload("again",Set.of())),actor(0)))==ReasonCode.RATE_LIMITED,"report cooldown");
        live.set(actor(4));tick.addAndGet(20);var disconnected=guard.submit(command(AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED)),actor(4));guard.disconnect(SESSION);queue.drain();check(code(disconnected)==ReasonCode.AUTH_DENIED,"disconnection cancels mailbox entry");
    }
    private static void saturatedDelivery() throws Exception {
        Thread actorThread=Thread.currentThread();
        var live=new AtomicReference<>(actor(4));var calls=new AtomicInteger();
        var applied=new AtomicReference<CompletableFuture<AdminResult>>();
        var queue=new ArrayBlockingQueue<Runnable>(1);
        Executor bounded=task->{if(!queue.offer(task)) throw new RejectedExecutionException("full");};
        AdminPort backend=new AdminPort() {
            public AdminReadResult query(AdminQuery q,ActorIdentity a){throw new AssertionError("unused");}
            public CompletionStage<AdminResult> submit(AdminCommand c,ActorIdentity a) {
                calls.incrementAndGet();var future=new CompletableFuture<AdminResult>();applied.set(future);return future;
            }
        };
        var guard=new AdminAccessGuard(backend,id->{
            if(Thread.currentThread()!=actorThread) throw new AssertionError("authority read off actor");
            return Optional.of(live.get());
        },()->0,bounded,1,10,10,40,1200);
        for(int scenario=0;scenario<3;scenario++) {
            live.set(actor(4));
            var command=command(AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));
            var pending=guard.submit(command,actor(4));queue.remove().run();
            bounded.execute(()->{}); // Fill delivery mailbox after application starts.
            var success=new AdminResult(AdminStatus.SUCCEEDED,command.requestId(),new Revision(7),Optional.empty(),Optional.empty());
            Thread worker=new Thread(()->applied.get().complete(success));worker.start();worker.join();
            check(!pending.toCompletableFuture().isDone(),"saturation retains pending delivery, never false rejection");
            if(scenario==0) {
                var retry=guard.submit(command,actor(4));
                check(retry.toCompletableFuture().join().equals(success),"retry receives authoritative success and revision");
                check(pending.toCompletableFuture().join().equals(success),"original subscriber receives same success");
            } else {
                if(scenario==2) live.set(actor(0));
                guard.drainCompletions();
                if(scenario==1) check(pending.toCompletableFuture().join().equals(success),"actor tick delivers retained success");
                else check(code(pending)==ReasonCode.AUTH_DENIED,"actor tick rechecks revoked authority");
            }
            check(calls.get()==scenario+1,"saturated delivery never reapplies command");queue.remove().run();
        }
    }
    private static AdminReadOnlyCommands unusedReadOnly(ActorAuthorityPort authority) {
        var calc=new AdminReadOnlyCalculations(input->{throw new AssertionError("calculation not expected");},id->{throw new AssertionError("fixture not expected");},id->{throw new AssertionError("replay not expected");});
        return new AdminReadOnlyCommands(calc,new AdminAuditExporter(Path.of("build/J07/unused"),Runnable::run,1000),id->{throw new AssertionError("audit not expected");},authority,Runnable::run,64);
    }
    private static void qualifiedPorts() {
        var live=new AtomicReference<>(actor(4));ActorAuthorityPort authority=id->Optional.ofNullable(live.get());
        var stamp=new AdminSnapshotStamp(WORLD,new Epoch(1),REV,TICK,new Sequence(88));
        var projection=new AtomicReference<>(new AdminReadResult(AdminStatus.SUCCEEDED,List.of(),Optional.empty(),Optional.empty(),Optional.of(stamp)));
        var submits=new AtomicInteger();var polls=new AtomicInteger();var commands=new HashMap<RuntimeAdminKey,AdminCommand>();
        var statuses=new HashMap<RuntimeAdminKey,AdminStatus>();
        RuntimeAdminPort runtime=new RuntimeAdminPort() {
            public AdminResult submit(AdminCommand c){submits.incrementAndGet();var key=new RuntimeAdminKey(c.actorSession(),c.requestId());commands.put(key,c);statuses.put(key,AdminStatus.PENDING);return new AdminResult(AdminStatus.PENDING,c.requestId(),REV,Optional.empty(),Optional.empty());}
            public AdminResult poll(RuntimeAdminKey key){polls.incrementAndGet();return new AdminResult(statuses.get(key),key.requestId(),new Revision(2),Optional.empty(),Optional.empty());}
        };
        var facade=new AdminFacade(q->projection.get(),runtime,authority,WORLD,new Epoch(1),64,unusedReadOnly(authority));
        var q=query(Optional.empty(),Optional.empty());var empty=facade.query(q,actor(4));
        check(empty.snapshot().orElseThrow().equals(stamp),"coherent empty page preserves real stamp");
        var wire=AdminFacade.snapshot(q,empty,Set.of(AdminOperation.REPLAY));
        check(wire.rows().isEmpty()&&wire.lastDurableSequence().value()==88&&wire.capturedTick().equals(TICK),"wire empty snapshot uses stamp not row1");
        var codec=new fr.proutlost.director.api.codec.ModelCodec<>(AdminReadResult.class);
        check(codec.decode(codec.encode(empty)).equals(empty),"internal stamp codec round trip");
        projection.set(new AdminReadResult(AdminStatus.SUCCEEDED,List.of(),Optional.empty(),Optional.empty()));
        check(facade.query(q,actor(4)).status()==AdminStatus.DEFERRED,"legacy unstamped projection defers");
        projection.set(new AdminReadResult(AdminStatus.SUCCEEDED,List.of(),Optional.empty(),Optional.empty(),Optional.of(new AdminSnapshotStamp(WORLD,new Epoch(2),REV,TICK,new Sequence(1)))));
        check(facade.query(q,actor(4)).status()==AdminStatus.DEFERRED,"foreign boot projection defers");
        projection.set(empty);
        check(facade.query(query(Optional.empty(),Optional.of(new Revision(9))),actor(4)).status()==AdminStatus.DEFERRED,"stale expected revision defers");
        live.set(actor(0));check(facade.query(q,actor(4)).error().orElseThrow().code()==ReasonCode.AUTH_DENIED,"query resolves live authority");live.set(actor(4));
        var pause=command(AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));var pending=facade.submit(pause,actor(4));
        check(!pending.toCompletableFuture().isDone(),"runtime pending is not success");
        var retry=facade.submit(pause,actor(4));check(submits.get()==1&&!retry.toCompletableFuture().isDone(),"pending idempotent retry polls without resubmit");
        check(code(facade.submit(new AdminCommand(SESSION,pause.requestId(),new Revision(2),pause.operation(),pause.payload()),actor(4)))==ReasonCode.IDEMPOTENCY_CONFLICT,"runtime conflicting revision refused");
        statuses.put(new RuntimeAdminKey(SESSION,pause.requestId()),AdminStatus.SUCCEEDED);facade.tick();
        check(pending.toCompletableFuture().join().status()==AdminStatus.SUCCEEDED&&pending.toCompletableFuture().join().revision().value()==2,"runtime terminal result delivered");
        check(retry.toCompletableFuture().join().equals(pending.toCompletableFuture().join()),"same terminal correlated result");
        var delivered=AdminFacade.commandResult(pending.toCompletableFuture().join());
        check(delivered.commandId().equals(pause.requestId())&&delivered.newRevision().orElseThrow().value()==2,"closed wire command preserves request and revision");
        live.set(actor(0));check(code(facade.submit(pause,actor(4)))==ReasonCode.AUTH_DENIED,"terminal retry rechecks current authority");live.set(actor(4));

        var stop=command(AdminOperation.STOP,new StopPayload(Optional.empty(),true,"admin"));var revoked=facade.submit(stop,actor(4));int before=polls.get();live.set(actor(0));facade.tick();
        check(code(revoked)==ReasonCode.AUTH_DENIED&&polls.get()==before,"revocation blocks poll and delivery");live.set(actor(4));
        var trigger=command(AdminOperation.TRIGGER,new TriggerPayload(new DefinitionId("test:event"),new RegionId(ID),Set.of(),Set.of()));
        var triggerResult=facade.submit(trigger,actor(4));statuses.put(new RuntimeAdminKey(SESSION,trigger.requestId()),AdminStatus.REJECTED);facade.tick();
        check(triggerResult.toCompletableFuture().join().status()==AdminStatus.REJECTED,"runtime refusal never bypassed");
        var region=command(AdminOperation.REGION,new RegionInspectionPayload(new RegionId(ID)));int calls=submits.get();
        check(code(facade.submit(region,actor(4)))==ReasonCode.NO_TARGET&&submits.get()==calls,"inspection reads projection, no runtime mutation");
        var ui=facade.submit(command(AdminOperation.UI,new EmptyAdminPayload()),actor(4));
        check(ui.toCompletableFuture().join().status()==AdminStatus.SUCCEEDED&&submits.get()==calls,"UI permission admitted without runtime world action");
        var drain=command(AdminOperation.DRAIN,new ControlPayload(RunControl.DRAINING));var disconnected=facade.submit(drain,actor(4));facade.disconnect(SESSION);
        check(code(disconnected)==ReasonCode.AUTH_DENIED,"disconnect clears pending delivery");
        RuntimeAdminPort uncertain=new RuntimeAdminPort(){public AdminResult submit(AdminCommand c){throw new IllegalStateException("lost receipt");}public AdminResult poll(RuntimeAdminKey k){return new AdminResult(AdminStatus.SUCCEEDED,k.requestId(),REV,Optional.empty(),Optional.empty());}};
        var uncertainFacade=new AdminFacade(q2->empty,uncertain,authority,WORLD,new Epoch(1),10,unusedReadOnly(authority));
        var uncertainResult=uncertainFacade.submit(command(AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED)),actor(4));
        check(!uncertainResult.toCompletableFuture().isDone(),"lost runtime receipt remains reconcilable");
        uncertainFacade.tick();check(uncertainResult.toCompletableFuture().join().status()==AdminStatus.SUCCEEDED,"lost receipt reconciles by poll");
    }
    private static void requestLifecycle() {
        var otherSession=new SessionId(new UUID(0,22));
        ActorAuthorityPort authority=id->Optional.of(new ActorIdentity(id,ActorKind.PLAYER,Optional.of(new PlayerId(id.value())),Optional.empty(),id.equals(SESSION)?0:4,true));
        var publicActor=authority.current(SESSION).orElseThrow();var admin=authority.current(otherSession).orElseThrow();
        var submits=new AtomicInteger();var polls=new AtomicInteger();var pollFailures=new AtomicInteger(1);
        RuntimeAdminPort runtime=new RuntimeAdminPort(){
            public AdminResult submit(AdminCommand c){submits.incrementAndGet();throw new IllegalStateException("receipt lost after application");}
            public AdminResult poll(RuntimeAdminKey key){polls.incrementAndGet();if(pollFailures.getAndDecrement()>0)throw new IllegalStateException("transient poll failure");return new AdminResult(AdminStatus.SUCCEEDED,key.requestId(),new Revision(7),Optional.empty(),Optional.empty());}
        };
        var facade=new AdminFacade(q->{throw new AssertionError("unexpected query");},runtime,authority,WORLD,new Epoch(1),2,unusedReadOnly(authority));
        var queue=new Queue();var tick=new AtomicLong();var guard=new AdminAccessGuard(facade,authority,tick::get,queue,4,2,100,40,1200);
        var first=command(AdminOperation.STATUS,new EmptyAdminPayload());
        var result=guard.submit(first,publicActor);queue.drain();
        check(!result.toCompletableFuture().isDone(),"guard retains lost-submit pending future");
        facade.tick();guard.drainCompletions();check(!result.toCompletableFuture().isDone(),"transient poll failure does not complete guard cache");
        var same=guard.submit(first,publicActor);facade.tick();queue.drain();guard.drainCompletions();
        check(result.toCompletableFuture().join().status()==AdminStatus.SUCCEEDED&&same.toCompletableFuture().join().equals(result.toCompletableFuture().join()),"guard original and retry deliver eventual authoritative success");
        check(submits.get()==1&&polls.get()==2,"lost receipt reconciliation never resubmits effects");
        for(int n=0;n<12;n++) {
            tick.addAndGet(20);var command=command(AdminOperation.STATUS,new EmptyAdminPayload());
            var next=guard.submit(command,publicActor);queue.drain();facade.tick();queue.drain();guard.drainCompletions();
            check(next.toCompletableFuture().join().status()==AdminStatus.SUCCEEDED,"sequential commands exceed retained capacity without exhaustion");
        }
        int before=submits.get();
        check(code(guard.submit(first,publicActor))==ReasonCode.DELIVERY_UNCERTAIN&&submits.get()==before,"evicted guard ID defers without downstream reapplication");
        check(code(facade.submit(first,publicActor))==ReasonCode.DELIVERY_UNCERTAIN&&submits.get()==before,"evicted facade ID defers without runtime resubmit");
        var conflict=new AdminCommand(SESSION,first.requestId(),new Revision(99),first.operation(),first.payload());
        check(code(facade.submit(conflict,publicActor))==ReasonCode.DELIVERY_UNCERTAIN&&submits.get()==before,"changed evicted payload cannot bypass replay fence");
        // Keep two public requests active; another session must retain its own admission slots.
        var publicOne=guard.submit(command(AdminOperation.STATUS,new EmptyAdminPayload()),publicActor);
        var publicTwo=guard.submit(command(AdminOperation.STATUS,new EmptyAdminPayload()),publicActor);queue.drain();
        check(!publicOne.toCompletableFuture().isDone()&&!publicTwo.toCompletableFuture().isDone(),"public session fills only its own active slots");
        var adminCommand=new AdminCommand(otherSession,new RequestId(UUID.randomUUID()),REV,AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));
        var privileged=guard.submit(adminCommand,admin);queue.drain();facade.tick();queue.drain();guard.drainCompletions();
        check(privileged.toCompletableFuture().join().status()==AdminStatus.SUCCEEDED,"public session cannot consume administrative session capacity");
        // A correlated DEFERRED receipt is also polled, including a same-ID facade retry.
        var once=new AtomicInteger();
        RuntimeAdminPort deferred=new RuntimeAdminPort(){public AdminResult submit(AdminCommand c){once.incrementAndGet();return new AdminResult(AdminStatus.DEFERRED,c.requestId(),REV,Optional.empty(),Optional.empty());}public AdminResult poll(RuntimeAdminKey k){return new AdminResult(AdminStatus.SUCCEEDED,k.requestId(),REV,Optional.empty(),Optional.empty());}};
        var retryFacade=new AdminFacade(q->{throw new AssertionError();},deferred,authority,WORLD,new Epoch(1),2,unusedReadOnly(authority));
        var pending=retryFacade.submit(adminCommand,admin);check(!pending.toCompletableFuture().isDone(),"deferred runtime receipt remains pending");
        var retried=retryFacade.submit(adminCommand,admin);
        check(retried.toCompletableFuture().join().status()==AdminStatus.SUCCEEDED&&once.get()==1,"same-ID retry reconciles by poll only");
    }
    private static ReasonCode code(CompletionStage<AdminResult> result) {return result.toCompletableFuture().join().error().orElseThrow().code();}
    private static void pages() {
        var pages=new AdminProjectionPages(WORLD,10,20,24576);List<AdminRow> rows=new ArrayList<>();
        for(int i=0;i<5;i++) rows.add(new AdminRow(new ScopeRef(ScopeKind.WORLD,ID),REV,TICK,
                new DecisionAdminData(new DecisionId(new UUID(0,i)),TICK,List.of(),Optional.empty(),Optional.empty(),List.of(),Optional.empty())));
        var retainedRows=List.copyOf(rows);
        pages.publish(WORLD,REV,TICK,new Sequence(8),rows,Set.of(AdminOperation.REPLAY));rows.clear();
        var tiny=new AdminProjectionPages(WORLD,10,10,1);
        tiny.publish(WORLD,REV,TICK,new Sequence(8),List.of(),Set.of());
        expect(ReasonCode.BUDGET_EXHAUSTED,()->tiny.snapshot(query(Optional.empty(),Optional.empty()),actor(4)));
        var q=query(Optional.empty(),Optional.empty());var first=pages.snapshot(q,actor(4));check(first.rows().size()==2&&first.nextCursor().isPresent(),"first page immutable publication");
        var second=pages.snapshot(query(first.nextCursor(),Optional.of(REV)),actor(4));check(second.rows().size()==2&&!second.rows().equals(first.rows()),"second page");
        check(pages.snapshot(query(second.nextCursor(),Optional.of(REV)),actor(4)).rows().size()==1,"last page");
        // One slot must suffice for an arbitrarily long serial read: consumed
        // opaque tokens cannot exhaust the admin UI after a few pages.
        var oneSlot=new AdminProjectionPages(WORLD,10,1,24576);
        oneSlot.publish(WORLD,REV,TICK,new Sequence(8),retainedRows,Set.of());
        var oneFirst=oneSlot.snapshot(q,actor(4));
        check(oneFirst.nextCursor().isPresent(),"one-slot initial cursor");
        expect(ReasonCode.RATE_LIMITED,()->oneSlot.snapshot(q,actor(4)));
        // Rejection must not evict the original reader's continuation.
        var oneSecond=oneSlot.snapshot(query(oneFirst.nextCursor(),Optional.of(REV)),actor(4));
        check(oneSecond.nextCursor().isPresent(),"one-slot cursor rotates on continuation");
        expect(ReasonCode.STALE_CONTEXT,()->oneSlot.snapshot(query(oneFirst.nextCursor(),Optional.of(REV)),actor(4)));
        check(oneSlot.snapshot(query(oneSecond.nextCursor(),Optional.of(REV)),actor(4)).nextCursor().isEmpty(),
                "one-slot pagination completes without RATE_LIMITED");
        expect(ReasonCode.STALE_CONTEXT,()->oneSlot.snapshot(query(oneSecond.nextCursor(),Optional.of(REV)),actor(4)));
        check(oneSlot.snapshot(q,actor(4)).nextCursor().isPresent(),"retired tokens free capacity for a new read");
        expect(ReasonCode.AUTH_DENIED,()->pages.snapshot(q,actor(2)));
        expect(ReasonCode.STALE_CONTEXT,()->pages.snapshot(query(Optional.of("forged"),Optional.empty()),actor(4)));
        var other=new ActorIdentity(new SessionId(UUID.randomUUID()),ActorKind.PLAYER,Optional.of(new PlayerId(ID)),Optional.empty(),4,true);
        expect(ReasonCode.STALE_CONTEXT,()->pages.snapshot(new AdminQuery(other.session(),q.requestId(),q.panel(),q.filter(),first.nextCursor(),Optional.of(REV),2),other));
        pages.publish(WORLD,new Revision(2),new Tick(11),new Sequence(9),List.of(),Set.of());
        expect(ReasonCode.STALE_CONTEXT,()->pages.snapshot(query(first.nextCursor(),Optional.of(REV)),actor(4)));
        var empty=pages.snapshot(q,actor(4));check(empty.rows().isEmpty()&&empty.snapshotRevision().value()==2&&empty.lastDurableSequence().value()==9,"empty retains atomic metadata");
    }
    private static void disasterStateFilter() {
        var disasterId=new DisasterId(new UUID(0,77));
        var warning=new WarningEvidence(Set.of(),new Tick(1),
            new DurationTicks(1),HASH,new Epoch(1),false,false);
        var footprint=new Footprint(new DimensionId("minecraft:overworld"),
            Set.of(),Set.of(),List.of());
        var blob=BlobContent.capture(new ResourceId("test:j07_filter"),1,
            new byte[]{1}).reference();
        var disaster=new DisasterInstance(
            new AggregateHeader(1,disasterId.value(),REV,new Sequence(1),TICK),
            disasterId,new EventInstanceId(ID),new DefinitionId("test:disaster"),
            DisasterStage.WATCH,Map.of(),new UnitValue(.1),footprint,warning,
            Set.of(),List.of(),blob,Set.of(),"idle",Set.of());
        var row=new AdminRow(new ScopeRef(ScopeKind.EVENT,ID),REV,TICK,
            new DisasterAdminData(disaster));
        var pages=new AdminProjectionPages(WORLD,10,10,24576);
        pages.publish(WORLD,REV,TICK,new Sequence(1),List.of(row),Set.of());
        var all=new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),
            AdminPanel.ACTIVE,new EventFilter(Optional.empty(),Set.of()),
            Optional.empty(),Optional.empty(),10);
        check(pages.snapshot(all,actor(4)).rows().size()==1,
            "unfiltered ACTIVE includes disaster WATCH");
        var running=new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),
            AdminPanel.ACTIVE,new EventFilter(Optional.empty(),Set.of(EventState.RUNNING)),
            Optional.empty(),Optional.empty(),10);
        check(pages.snapshot(running,actor(4)).rows().isEmpty(),
            "EventState.RUNNING must not match DisasterStage.WATCH");
        var foreign=new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),
            AdminPanel.ACTIVE,new EventFilter(Optional.of(new EventInstanceId(new UUID(0,78))),Set.of()),
            Optional.empty(),Optional.empty(),10);
        check(pages.snapshot(foreign,actor(4)).rows().isEmpty(),
            "disaster filtered by exact event instance");
        var wrongDomain=new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),AdminPanel.ACTIVE,
            new EventFilter(Optional.of(new EventInstanceId(disasterId.value())),Set.of()),Optional.empty(),Optional.empty(),10);
        check(pages.snapshot(wrongDomain,actor(4)).rows().isEmpty(),"disaster ID is not an event inspection ID");
        var live=new AtomicReference<>(actor(0));var reads=new AtomicInteger();
        AdminPort backend=new AdminPort(){
            public AdminReadResult query(AdminQuery q,ActorIdentity a){reads.incrementAndGet();return pages.query(q,a,new Epoch(1));}
            public CompletionStage<AdminResult> submit(AdminCommand c,ActorIdentity a){throw new AssertionError("F8 disaster read must not submit mutation");}
        };
        var guard=new AdminAccessGuard(backend,id->Optional.of(live.get()),()->0,Runnable::run,8,100,100,40,1200);
        var denied=guard.query(all,actor(4));
        check(denied.status()==AdminStatus.REJECTED&&denied.error().orElseThrow().code()==ReasonCode.AUTH_DENIED&&reads.get()==0,
            "supplied OP4 cannot expose disaster data when server authority is player");
        live.set(actor(4));var allowed=guard.query(all,actor(4));
        check(allowed.status()==AdminStatus.SUCCEEDED&&allowed.data().size()==1&&allowed.data().getFirst().data() instanceof DisasterAdminData&&reads.get()==1,
            "actual OP4 authority may receive the typed disaster row through read-only guard");
        live.set(actor(3));var revoked=guard.query(all,actor(4));
        check(revoked.status()==AdminStatus.REJECTED&&revoked.error().orElseThrow().code()==ReasonCode.AUTH_DENIED&&reads.get()==1,
            "OP4 revocation is checked before any disaster projection read");
    }
    private static void codecItemPages() {
        var validation=new ValidationResult(true,List.of(),new ReadSet(Map.of(),Map.of(),Map.of()));
        var row=new AdminRow(new ScopeRef(ScopeKind.WORLD,ID),REV,TICK,new DecisionAdminData(new DecisionId(ID),TICK,List.of(),Optional.empty(),Optional.empty(),Collections.nCopies(5000,validation),Optional.empty()));
        assertSplits(List.of(row,row),query(Optional.empty(),Optional.empty()));
        var large=new AdminRow(row.scope(),REV,TICK,new DecisionAdminData(new DecisionId(ID),TICK,List.of(),Optional.empty(),Optional.empty(),Collections.nCopies(10000,validation),Optional.empty()));
        assertOversized(large,query(Optional.empty(),Optional.empty()));
    }
    private static void assertSplits(List<AdminRow> rows,AdminQuery query) {
        var codec=new fr.proutlost.director.api.codec.ModelCodec<>(AdminSnapshot.class);
        try {codec.encode(new AdminSnapshot(query.requestId(),REV,TICK,new Sequence(1),rows,Optional.empty(),Set.of()));throw new AssertionError("fixture must exceed codec bound");}
        catch(IllegalArgumentException expected){checks++;}
        var pages=new AdminProjectionPages(WORLD,10,10,2_000_000);pages.publish(WORLD,REV,TICK,new Sequence(1),rows,Set.of());
        var first=pages.snapshot(query,actor(4));check(first.rows().size()==1&&first.nextCursor().isPresent(),"codec overflow yields bounded first page");
        check(codec.encode(first).length<=fr.proutlost.director.api.codec.CanonicalJsonV1.MAX_BYTES,"first page encodable");
        var next=new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),query.panel(),query.filter(),first.nextCursor(),Optional.of(REV),query.limit());
        var second=pages.snapshot(next,actor(4));check(second.rows().size()==1&&second.nextCursor().isEmpty(),"overflow continuation preserves second row");
    }
    private static void assertOversized(AdminRow row,AdminQuery query) {
        var pages=new AdminProjectionPages(WORLD,10,10,2_000_000);pages.publish(WORLD,REV,TICK,new Sequence(1),List.of(row),Set.of());
        var facade=facade(pages,(c,a)->{throw new AssertionError("read must not dispatch command");});
        var result=facade.query(query,actor(4));
        check(result.status()==AdminStatus.REJECTED&&result.error().orElseThrow().code()==ReasonCode.BUDGET_EXHAUSTED,"single oversized row typed refusal");
    }
    private static void queryFailClosed() {
        ActorAuthorityPort authority=session->Optional.of(actor(4));
        RuntimeAdminPort never=new RuntimeAdminPort() {
            public AdminResult submit(AdminCommand command) {throw new AssertionError("read must not mutate");}
            public AdminResult poll(RuntimeAdminKey key) {throw new AssertionError("read must not poll");}
        };
        var query=query(Optional.empty(),Optional.empty());
        var calls=new AtomicInteger();
        var stale=new AdminFacade(q->{calls.incrementAndGet();throw new AdminProjectionPages.Rejected(ReasonCode.STALE_CONTEXT);},
                never,authority,WORLD,new Epoch(1),8,unusedReadOnly(authority));
        // The supplied permission is not authoritative; only a mismatched
        // authenticated identity must be rejected before reading projections.
        var impostor=new ActorIdentity(SESSION,ActorKind.PLAYER,
                Optional.of(new PlayerId(new UUID(0,99))),Optional.empty(),4,true);
        var denied=stale.query(query,impostor);
        check(denied.status()==AdminStatus.REJECTED&&denied.error().orElseThrow().code()==ReasonCode.AUTH_DENIED
                &&calls.get()==0,"unauthorized queries do not evaluate projection");
        var staleResponse=stale.query(query,actor(4));
        check(staleResponse.status()==AdminStatus.DEFERRED&&staleResponse.error().orElseThrow().code()==ReasonCode.STALE_CONTEXT
                &&staleResponse.data().isEmpty()&&staleResponse.snapshot().isEmpty(),
                "typed stale cursor is deferred with no data or stamp");
        var oversized=new AdminFacade(q->{throw new AdminProjectionPages.Rejected(ReasonCode.BUDGET_EXHAUSTED);},
                never,authority,WORLD,new Epoch(1),8,unusedReadOnly(authority));
        var rejected=oversized.query(query,actor(4));
        check(rejected.status()==AdminStatus.REJECTED&&rejected.error().orElseThrow().code()==ReasonCode.BUDGET_EXHAUSTED,
                "typed projection budget failure is a bounded rejection");
        var broken=new AdminFacade(q->{throw new IllegalStateException("internal projection detail");},
                never,authority,WORLD,new Epoch(1),8,unusedReadOnly(authority));
        var safe=broken.query(query,actor(4));
        check(safe.status()==AdminStatus.FAILED_SAFE&&safe.error().orElseThrow().code()==ReasonCode.INTERNAL_FAULT
                &&safe.data().isEmpty()&&safe.snapshot().isEmpty()&&safe.nextCursor().isEmpty(),
                "projection failure contains no partial data or internal details");
    }
    private static void facadeRefusal() {
        var pages=new AdminProjectionPages(WORLD,10,10,24576);
        pages.publish(WORLD,REV,TICK,new Sequence(1),List.of(),Set.of());
        var called=new AtomicInteger();
        var trigger=command(AdminOperation.TRIGGER,new TriggerPayload(new DefinitionId("test:event"),new RegionId(ID),Set.of(new PlayerId(ID)),Set.of()));
        var facade=facade(pages,(c,a)-> {
            called.incrementAndGet();check(c.equals(trigger),"trigger forwarded without plan/bypass rewriting");
            return CompletableFuture.completedFuture(new AdminResult(AdminStatus.REJECTED,c.requestId(),REV,Optional.empty(),
                Optional.of(new SystemError(ReasonCode.SAFETY_COOLDOWN,Severity.WARNING,false,Optional.empty(),Set.of(),new CorrelationId(ID),SafeState.UNCHANGED))));
        });
        check(code(facade.submit(trigger,new ActorIdentity(SESSION,ActorKind.PLAYER,Optional.of(new PlayerId(new UUID(0,99))),Optional.empty(),0,true)))==ReasonCode.AUTH_DENIED&&called.get()==0,"facade denies unauthorized trigger");
        check(code(facade.submit(trigger,actor(4)))==ReasonCode.SAFETY_COOLDOWN&&called.get()==1,"force refusal preserved");
        check(facade.query(query(Optional.empty(),Optional.empty()),actor(4)).status()==AdminStatus.SUCCEEDED,"facade empty query");
        check(facade.query(query(Optional.empty(),Optional.empty()),new ActorIdentity(SESSION,ActorKind.PLAYER,Optional.of(new PlayerId(new UUID(0,99))),Optional.empty(),0,true)).error().orElseThrow().code()==ReasonCode.AUTH_DENIED,"facade private query");
    }
    private static AdminFacade facade(AdminProjectionPages pages,java.util.function.BiFunction<AdminCommand,ActorIdentity,CompletionStage<AdminResult>> dispatch) {
        ActorAuthorityPort authority=id->Optional.of(actor(4));
        RuntimeAdminPort runtime=new RuntimeAdminPort() {
            public AdminResult submit(AdminCommand c){return dispatch.apply(c,actor(4)).toCompletableFuture().join();}
            public AdminResult poll(RuntimeAdminKey key){throw new AssertionError("unexpected poll");}
        };
        var calculations=new AdminReadOnlyCalculations(input->{throw new AssertionError("unexpected calculation");},id->{throw new AssertionError("unexpected fixture");},id->{throw new AssertionError("unexpected replay");});
        var readOnly=new AdminReadOnlyCommands(calculations,new AdminAuditExporter(Path.of("build/J07/unused"),Runnable::run,1000),id->{throw new AssertionError("unexpected audit");},authority,Runnable::run,10);
        return new AdminFacade(q->pages.query(q,actor(4),new Epoch(1)),runtime,authority,WORLD,new Epoch(1),20,readOnly);
    }
    private static AdminQuery query(Optional<String> cursor,Optional<Revision> revision) {return new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),AdminPanel.DECISIONS,new DecisionFilter(Optional.empty()),cursor,revision,2);}
    private static void expect(ReasonCode code,Runnable r) {try{r.run();throw new AssertionError("not rejected");}catch(AdminProjectionPages.Rejected e){check(e.reason()==code,"rejection "+code);}}
    private static void calculations() throws Exception {
        var scopes=new ScopeSet(WORLD,Set.of());
        var context=new SnapshotBatch(new RequestId(ID),scopes,TICK,Map.of(),new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),Set.of(),Set.of(),HealthMode.NORMAL);
        var header=new AggregateHeader(1,ID,REV,new Sequence(1),TICK);
        var world=new DirectorWorldState(header,WORLD,new Epoch(1),TICK,RunControl.PAUSED,HealthMode.READ_ONLY_SAFE,PacingState.REST,GlobalTension.CALM,new ResourceId("test:profile"),HASH,HASH,new Epoch(1),new Sequence(1),new EngineControlState(1,REV,TICK,TICK,new UnitValue(0),TICK,Optional.empty(),new UnitValue(0)));
        var zero=new BudgetVector(0,0,0,0,0,0,0,0,0,0);
        Map<ResourceId,Long> queues=new HashMap<>();
        for(int i=0;i<10;i++)queues.put(new ResourceId("test:"+"a".repeat(60000)+i),1L);
        var bigWorld=new AdminRow(new ScopeRef(ScopeKind.WORLD,ID),REV,TICK,new WorldAdminData(world,new BudgetView(zero,zero,Map.of()),queues,List.of()));
        var worldQuery=new AdminQuery(SESSION,new RequestId(ID),AdminPanel.WORLD,new WorldFilter(),Optional.empty(),Optional.empty(),2);
        assertSplits(List.of(bigWorld,bigWorld),worldQuery);
        for(int i=10;i<20;i++)queues.put(new ResourceId("test:"+"a".repeat(60000)+i),1L);
        assertOversized(new AdminRow(bigWorld.scope(),REV,TICK,new WorldAdminData(world,new BudgetView(zero,zero,Map.of()),queues,List.of())),worldQuery);
        var input=new DecisionInput(context,new MemoryView(scopes,Map.of(),List.of(),List.of(),List.of(),Map.of(),Map.of(),Map.of()),new CatalogSnapshot(new Epoch(1),HASH,Map.of(),Map.of(),Map.of()),new ConfigView(REV,HASH,Calibration.DEFAULT_FOR_TESTING,Map.of()),List.of(),List.of(),123,new DecisionState(world,new BudgetView(zero,zero,Map.of()),new OwnershipView(Map.of()),List.of(),List.of(),List.of(),List.of(),List.of(),List.of()),Optional.empty());
        var audit=new AuditDecision(new DecisionId(ID),context,REV,HASH,REV,HASH,List.of(),List.of(),123,List.of(),Optional.empty(),Optional.empty(),new RoleAssignments(Map.of()),scopes,List.of(),List.of(),List.of(),Optional.empty(),List.of(),Map.of(),List.of(),List.of(),List.of(),Map.of(),List.of());
        var proposal=new DecisionProposal(new DecisionId(ID),List.of(),Optional.empty(),audit,List.of());
        var received=new AtomicReference<DecisionInput>();var calls=new AtomicInteger();
        var calculations=new AdminReadOnlyCalculations(i->{received.set(i);calls.incrementAndGet();return proposal;},id->input,id->input);
        check(calculations.simulate(new ResourceId("test:fixture"))==proposal&&received.get()==input,"simulation pure decision dispatch");
        check(calculations.replay(new DecisionId(ID),false)==proposal&&received.get()==input,"exact recorded input replay");
        calculations.replay(new DecisionId(ID),true);check(received.get().advisorySnapshot().isEmpty()&&received.get().seed()==123&&calls.get()==3,"without AI keeps seed and context");
        var storeReads=new AtomicInteger();
        DurableStore store=new DurableStore() {
            final StoreSession fakeSession=new StoreSession(WORLD,new Epoch(1),new Epoch(1),new SessionId(ID));
            public StoreReadResult view(StoreQuery query) {
                storeReads.incrementAndGet();
                var records=query.aggregateKeys().isEmpty()?List.<DurableRecord>of(new DurableAudit(audit)):List.<DurableRecord>of();
                var aggregates=query.aggregateKeys().isEmpty()?List.<RestoredAggregate>of():List.of(new RestoredAggregate(new WorldAggregate(world),new Sequence(1),HASH));
                var payload=new StoreView(records,aggregates,new Sequence(1),false,Optional.empty());
                return new StoreReadResult(ReadStatus.AVAILABLE,Optional.of(payload),
                    Optional.of(new ReadStamp(fakeSession,new Sequence(1),ReadContracts.queryHash(query))),List.of());
            }
            public CompletionStage<StoreReadResult> read(StoreQuery query) {return CompletableFuture.completedFuture(view(query));}
            public CompletionStage<CommitAck> append(DurableBatch batch) {throw new AssertionError("read query wrote store");}
            public StoreSession session() {return fakeSession;}
            public CompletionStage<BlobReadResult> readBlobs(BlobReadRequest request) {throw new AssertionError("actor attempted blob IO");}
        };
        var storeQueries=new AdminStoreQueries(store);
        check(storeQueries.world(WORLD).orElseThrow().equals(world),"world projection from store port");
        var auditPage=storeQueries.audit(new DecisionId(ID),new StoreQuery(WORLD,Set.of(),new Sequence(0),Optional.empty(),20));
        check(auditPage.audit().orElseThrow().equals(audit)&&auditPage.continuation().isEmpty()&&storeReads.get()==2,"bounded audit read without writes");
        // The test is independently runnable on a clean Windows/Linux workspace.
        Path auditParent=Path.of("build/J07");Files.createDirectories(auditParent);
        var queue=new Queue();Path root=Files.createTempDirectory(auditParent,"audit-test-");
        var exporter=new AdminAuditExporter(root,queue,100000);var permission=new AtomicBoolean(true);
        var denied=exporter.export(audit,permission::get);permission.set(false);queue.drain();
        check(denied.toCompletableFuture().isCompletedExceptionally(),"export permission recheck");
        try(var files=Files.list(root)){check(files.findAny().isEmpty(),"denied export creates no file");}
        permission.set(true);var written=exporter.export(audit,permission::get);queue.drain();var path=written.toCompletableFuture().join();
        check(path.getParent().equals(root.toAbsolutePath()),"server-derived export path");
        check(new fr.proutlost.director.api.codec.ModelCodec<>(AuditDecision.class).decode(Files.readAllBytes(path)).equals(audit),"real exported audit round trip");
        var worker=new Queue();var live=new AtomicReference<>(actor(4));ActorAuthorityPort authority=id->Optional.of(live.get());
        var ro=new AdminReadOnlyCommands(calculations,exporter,id->audit,authority,worker,20);
        var stamp=new AdminSnapshotStamp(WORLD,new Epoch(1),REV,TICK,new Sequence(1));
        RuntimeAdminPort noRuntime=new RuntimeAdminPort(){public AdminResult submit(AdminCommand c){throw new AssertionError("read only called runtime");}public AdminResult poll(RuntimeAdminKey k){throw new AssertionError("read only polled runtime");}};
        var facade=new AdminFacade(q->new AdminReadResult(AdminStatus.SUCCEEDED,List.of(),Optional.empty(),Optional.empty(),Optional.of(stamp)),noRuntime,authority,WORLD,new Epoch(1),20,ro);
        var simulation=command(AdminOperation.SIMULATE,new SimulationPayload(new ResourceId("test:fixture"),context,true));
        var sim=facade.submit(simulation,actor(4));check(!sim.toCompletableFuture().isDone(),"simulation queued off actor");
        worker.drain();check(!sim.toCompletableFuture().isDone(),"worker cannot deliver before actor authority check");facade.tick();
        check(sim.toCompletableFuture().join().data().orElseThrow().data() instanceof LaboratoryAdminData,"simulation typed result from real calculation route");
        var labQuery=new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),AdminPanel.LABORATORY,new LaboratoryFilter(Optional.of(new ResourceId("test:fixture"))),Optional.empty(),Optional.empty(),10);
        check(facade.query(labQuery,actor(4)).data().size()==1,"simulation result retrievable by fixture");
        var replay=command(AdminOperation.REPLAY,new ReplayPayload(new DecisionId(ID),true));var replayResult=facade.submit(replay,actor(4));worker.drain();facade.tick();
        check(replayResult.toCompletableFuture().join().data().orElseThrow().data() instanceof DecisionAdminData,"replay typed decision result");
        var replayQuery=new AdminQuery(SESSION,new RequestId(UUID.randomUUID()),AdminPanel.DECISIONS,new DecisionFilter(Optional.of(new DecisionId(ID))),Optional.empty(),Optional.empty(),10);
        check(facade.query(replayQuery,actor(4)).data().size()==1,"replay result retrieved by original decision target");
        var export=command(AdminOperation.AUDIT_EXPORT,new AuditExportPayload(new DecisionId(ID)));var exported=facade.submit(export,actor(4));worker.drain();queue.drain();facade.tick();
        check(exported.toCompletableFuture().join().status()==AdminStatus.SUCCEEDED,"facade export completes after writer and actor delivery");
        var serverPath=ro.exportPath(new RuntimeAdminKey(SESSION,export.requestId())).orElseThrow();
        check(Files.exists(serverPath)&&serverPath.getParent().equals(root.toAbsolutePath()),"facade exposes server-derived export path");
        var deniedCommand=command(AdminOperation.REPLAY,new ReplayPayload(new DecisionId(ID),false));var deniedResult=facade.submit(deniedCommand,actor(4));live.set(actor(0));facade.tick();worker.drain();facade.tick();
        check(code(deniedResult)==ReasonCode.AUTH_DENIED,"read-only deferred authority revoked");
        check(ro.exportPath(new RuntimeAdminKey(SESSION,export.requestId())).isEmpty(),"export path private after revocation");
        live.set(actor(4));
        var late=command(AdminOperation.REPLAY,new ReplayPayload(new DecisionId(ID),false));var lateResult=facade.submit(late,actor(4));worker.drain();live.set(actor(0));facade.tick();
        check(code(lateResult)==ReasonCode.AUTH_DENIED,"successful calculation withheld when permission revoked at delivery");
        var absent=new ActorIdentity(new SessionId(UUID.randomUUID()),ActorKind.PLAYER,Optional.of(new PlayerId(ID)),Optional.empty(),4,true);
        check(facade.query(new AdminQuery(absent.session(),new RequestId(UUID.randomUUID()),AdminPanel.DECISIONS,replayQuery.filter(),Optional.empty(),Optional.empty(),10),absent).status()==AdminStatus.REJECTED,"foreign session cannot retrieve private result");
        ActorAuthorityPort perSession=id->Optional.of(new ActorIdentity(id,ActorKind.PLAYER,Optional.of(new PlayerId(id.value())),Optional.empty(),4,true));
        var small=new AdminReadOnlyCommands(calculations,exporter,id->audit,perSession,worker,2);
        var firstLocal=command(AdminOperation.REPLAY,new ReplayPayload(new DecisionId(ID),false));
        var initial=small.submit(firstLocal,stamp);worker.drain();small.tick();check(initial.toCompletableFuture().join().status()==AdminStatus.SUCCEEDED,"local first result");
        for(int n=0;n<12;n++) {
            var next=small.submit(command(AdminOperation.REPLAY,new ReplayPayload(new DecisionId(ID),false)),stamp);worker.drain();small.tick();
            check(next.toCompletableFuture().join().status()==AdminStatus.SUCCEEDED,"local completed retention permits sustained requests");
        }
        int calculationCount=calls.get();
        check(code(small.submit(firstLocal,stamp))==ReasonCode.DELIVERY_UNCERTAIN&&calls.get()==calculationCount,"evicted local request never recalculates or reexports");
        var p1=small.submit(command(AdminOperation.REPLAY,new ReplayPayload(new DecisionId(ID),false)),stamp);
        var p2=small.submit(command(AdminOperation.REPLAY,new ReplayPayload(new DecisionId(ID),false)),stamp);
        var other=new AdminCommand(new SessionId(new UUID(0,77)),new RequestId(UUID.randomUUID()),REV,AdminOperation.REPLAY,new ReplayPayload(new DecisionId(ID),false));
        var independent=small.submit(other,stamp);worker.drain();small.tick();
        check(independent.toCompletableFuture().join().status()==AdminStatus.SUCCEEDED&&p1.toCompletableFuture().isDone()&&p2.toCompletableFuture().isDone(),"local pending capacity isolated per session");
        var exportOnce=command(AdminOperation.AUDIT_EXPORT,new AuditExportPayload(new DecisionId(ID)));
        var exportReceipt=small.submit(exportOnce,stamp);worker.drain();queue.drain();small.tick();
        check(exportReceipt.toCompletableFuture().join().status()==AdminStatus.SUCCEEDED,"retained export completed once");
        for(int n=0;n<3;n++) {
            small.submit(command(AdminOperation.REPLAY,new ReplayPayload(new DecisionId(ID),false)),stamp);worker.drain();small.tick();
        }
        long filesBefore;try(var files=Files.list(root)){filesBefore=files.count();}
        check(code(small.submit(exportOnce,stamp))==ReasonCode.DELIVERY_UNCERTAIN,"evicted export explicitly refuses reexecution");
        worker.drain();queue.drain();small.tick();
        try(var files=Files.list(root)){check(files.count()==filesBefore,"evicted export retry creates no duplicate file");}




    }
    private static void check(boolean condition,String message) {checks++;if(!condition)throw new AssertionError(message);}
}
