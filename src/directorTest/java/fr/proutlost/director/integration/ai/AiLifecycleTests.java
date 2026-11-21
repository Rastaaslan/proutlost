package fr.proutlost.director.integration.ai;

import fr.proutlost.director.api.model.*;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** TEST_ONLY: deterministic transport lifecycle, including delayed cancellation acknowledgement. */
public final class AiLifecycleTests {
    private static int assertions;
    private static void check(boolean value, String name) { assertions++; if (!value) throw new AssertionError(name); }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(3,TimeUnit.SECONDS); }
    private static AiInput input(long now, int key) {
        var hash=new Fingerprint(String.format("%064x",key));
        return new AiInput(1,new RequestId(UUID.randomUUID()),new Pseudonym("p_0123456789abcdef"),new DecisionId(UUID.randomUUID()),hash,hash,hash,
                now+TimeUnit.SECONDS.toNanos(5),List.of(),List.of(),1,new AiOutputLimits(1024,0,0,0,0,BudgetMath.zero()));
    }
    public static int run() throws Exception {
        assertions=0;
        var scheduler=new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        var clock=new AtomicLong(100);var tick=new AtomicLong(0);var calls=new AtomicInteger();
        var unresolved=new AtomicReference<CompletableFuture<AiTransportResponse>>();
        try {
            try(var advisor=new OptionalAiAdvisor(r -> {
                calls.incrementAndGet();var future=new CompletableFuture<AiTransportResponse>();unresolved.set(future);return future.minimalCompletionStage();
            },scheduler,clock::get,tick::get,()->input(0,1).contextHash(),
                    new OptionalAiAdvisor.Policy(TimeUnit.MILLISECONDS.toNanos(20),1,1,100,100,2),new ResourceId("test:pending"),"v1")) {
                var first=advisor.propose(input(clock.get(),1));
                check(await(first).status()==AiStatus.FALLBACK,"timeout returns immediately without resolving transport");
                check(!unresolved.get().isDone(),"original transport still outstanding");
                for(int i=2;i<12;i++) {
                    clock.addAndGet(1000);tick.addAndGet(1000);
                    check(await(advisor.propose(input(clock.get(),i))).status()==AiStatus.FALLBACK,"reset probe cannot overtake pending exchange");
                    check(calls.get()==1,"one underlying exchange across circuit resets");
                }
                unresolved.get().completeExceptionally(new IllegalStateException("late network failure"));
                await(barrier(scheduler));
                check(await(first).status()==AiStatus.FALLBACK,"late completion never changes fallback");
                var next=advisor.propose(input(clock.get(),20));
                check(await(next).status()==AiStatus.FALLBACK && calls.get()==2,"new exchange only after original termination");
                clock.addAndGet(1000);tick.addAndGet(1000);
                check(await(advisor.propose(input(clock.get(),21))).status()==AiStatus.FALLBACK && calls.get()==2,"second unresolved timeout retains slot");
                unresolved.get().completeExceptionally(new IllegalStateException("cleanup"));
            }
            calls.set(0);tick.set(0);
            try(var advisor=new OptionalAiAdvisor(r->{calls.incrementAndGet();return CompletableFuture.failedFuture(new IllegalStateException("down"));},scheduler,clock::get,tick::get,()->input(0,1).contextHash(),
                    new OptionalAiAdvisor.Policy(1000000000,1,100,100,10,1),new ResourceId("test:bounded"),"v1")) {
                await(advisor.propose(input(clock.get(),1)));tick.set(2);
                await(advisor.propose(input(clock.get(),1)));
                check(calls.get()==1,"identical hashes suppressed beyond call gap");
                await(advisor.propose(input(clock.get(),2)));
                check(calls.get()==1,"full dedup cache does not evict live key");
                tick.set(10);await(advisor.propose(input(clock.get(),1)));
                check(calls.get()==2,"rejected duplicates do not extend rolling window");
            }
            // This is the same bridge used by sendAsync in HttpAiTransport, without a network service.
            var delayed=new DelayedCancellation();var operation=HttpAiTransport.track(delayed,clock::get);
            operation.requestCancellation();operation.requestCancellation();
            check(delayed.cancellations==1,"HTTP exchange cancellation requested once");
            check(!operation.isDone(),"cancellation request is not terminal cleanup acknowledgement");
            delayed.completeExceptionally(new CancellationException());
            check(operation.isCompletedExceptionally(),"HTTP terminal callback completes transport lifecycle");
            var exchange=new CompletableFuture<HttpResponse<byte[]>>();var immediate=HttpAiTransport.track(exchange,clock::get);
            immediate.cancel(true);
            check(exchange.isCancelled() && immediate.isCompletedExceptionally(),"public cancellation reaches actual exchange handle");
            var held=new DelayedCancellation();var transport=HttpAiTransport.track(held,clock::get);calls.set(0);
            try(var advisor=new OptionalAiAdvisor(r->{calls.incrementAndGet();return transport;},scheduler,clock::get,tick::get,()->input(0,1).contextHash(),
                    new OptionalAiAdvisor.Policy(TimeUnit.MILLISECONDS.toNanos(20),1,1,100,100,2),new ResourceId("test:http"),"v1")) {
                check(await(advisor.propose(input(clock.get(),1))).status()==AiStatus.FALLBACK,"HTTP timeout fallback");
                await(barrier(scheduler));
                check(held.cancellations==1 && !transport.isDone(),"advisor asks HTTP cleanup without releasing pending operation");
                clock.addAndGet(1000);tick.addAndGet(1000);
                check(await(advisor.propose(input(clock.get(),2))).status()==AiStatus.FALLBACK && calls.get()==1,"HTTP pending cancellation blocks reset probe");
                held.completeExceptionally(new CancellationException());
            }
        } finally { scheduler.shutdownNow(); }
        System.out.println("PASS AI lifecycle unresolved timeouts/reset probes/HTTP cancellation acknowledgement");
        return assertions;
    }
    private static CompletableFuture<Void> barrier(Executor executor) { return CompletableFuture.runAsync(()->{},executor); }
    private static final class DelayedCancellation extends CompletableFuture<HttpResponse<byte[]>> {
        int cancellations;
        @Override public boolean cancel(boolean interrupt) { cancellations++;return false; }
    }
}
