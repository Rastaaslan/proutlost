package fr.proutlost.director.bootstrap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/** Real local worker/future concurrency, not Minecraft or J04 acceptance. */
public final class DirectorShutdownDrainTests {
    private static int assertions;
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);assertions++;}
    private static void block(BooleanSupplier done){while(!done.getAsBoolean())LockSupport.parkNanos(100_000);}
    private static void take(CountDownLatch latch){try{if(!latch.await(3,TimeUnit.SECONDS))throw new AssertionError("test latch timeout");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}}

    private static void actualDaemonCompletion()throws InterruptedException {
        var close=new CompletableFuture<Void>();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pendingWitness=new AtomicBoolean();
        var writer=Thread.ofPlatform().daemon(true).name("close-test-daemon-writer").start(()->{take(release);close.complete(null);});
        var controller=Thread.ofPlatform().name("close-test-release").start(()->{take(entered);pendingWitness.set(!close.isDone());release.countDown();});
        boolean observed=DirectorShutdownDrain.await(close,TimeUnit.SECONDS.toNanos(2),predicate->{entered.countDown();block(predicate);});
        controller.join(3000);writer.join(3000);
        check(pendingWitness.get(),"close was really pending when the bounded shutdown wait began");
        check(writer.isDaemon(),"real worker is daemon like J04 writer");
        check(observed&&close.isDone()&&!close.isCompletedExceptionally(),"shutdown returned only after actual daemon completion");
        check(!writer.isAlive()&&!controller.isAlive(),"test workers ended");
    }

    private static void timeoutPreservesPending()throws InterruptedException {
        var close=new CompletableFuture<Void>();var release=new CountDownLatch(1);
        var writer=Thread.ofVirtual().name("close-test-virtual-waiter").start(()->{take(release);close.complete(null);});
        long started=System.nanoTime();
        boolean observed=DirectorShutdownDrain.await(close,TimeUnit.MILLISECONDS.toNanos(30),DirectorShutdownDrainTests::block);
        long elapsed=System.nanoTime()-started;
        check(!observed&&!close.isDone(),"deadline means UNCONFIRMED; no manufactured completion");
        check(elapsed>=TimeUnit.MILLISECONDS.toNanos(30)&&elapsed<TimeUnit.SECONDS.toNanos(2),"actual wait is bounded");
        check(writer.isDaemon(),"virtual close waiter is really daemon");
        release.countDown();writer.join(3000);check(close.isDone(),"pending close may genuinely complete later");
    }

    private static void exceptionalCompletionIsNotSuccess(){
        var failure=new IllegalStateException("declared fsync failure");
        var close=CompletableFuture.failedFuture(failure);
        check(DirectorShutdownDrain.await(close,1,DirectorShutdownDrainTests::block),"exceptional completion is observed");
        check(close.isCompletedExceptionally(),"original exceptional close is retained for finishClose/marker");
        try{close.join();throw new AssertionError("failure swallowed");}catch(CompletionException actual){check(actual.getCause()==failure,"exact failure retained");}
    }

    private static void monotonicWrapAndEarlyReturn(){
        var clock=new AtomicLong(Long.MAX_VALUE-5);var close=new CompletableFuture<Void>();
        boolean observed=DirectorShutdownDrain.await(close,10,predicate->{check(!predicate.getAsBoolean(),"deadline not reached");clock.addAndGet(11);check(predicate.getAsBoolean(),"nanoTime wrap uses elapsed difference");},clock::get);
        check(!observed&&!close.isDone(),"wrapped deadline still unconfirmed");
        check(!DirectorShutdownDrain.await(close,1,predicate->{}),"early-returning scheduler cannot invent completion");
        check(DirectorShutdownDrain.await(CompletableFuture.completedFuture(null),1,DirectorShutdownDrainTests::block),"already completed close is observed");
        for(long invalid:new long[]{0,-1,TimeUnit.MINUTES.toNanos(1)+1}){
            try{DirectorShutdownDrain.await(close,invalid,predicate->{});throw new AssertionError("unbounded timeout accepted");}
            catch(IllegalArgumentException expected){assertions++;}
        }
    }

    private static void child(boolean bounded){
        var close=new CompletableFuture<Void>();var release=new CountDownLatch(1);
        Thread.ofPlatform().daemon(true).name("close-child-daemon-writer").start(()->{
            try{release.await();close.complete(null);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();close.completeExceptionally(interrupted);}
        });
        var terminal=close.whenComplete((ok,failure)->System.out.println(failure==null?"CLOSE_CALLBACK_PASS":"CLOSE_CALLBACK_FAILED"));
        System.out.println("CLOSE_REQUESTED_CALLBACK_REGISTERED");
        if(bounded){
            boolean observed=DirectorShutdownDrain.await(terminal,TimeUnit.SECONDS.toNanos(2),predicate->{release.countDown();block(predicate);});
            if(!observed)throw new AssertionError("child bounded close unconfirmed");
        }
        System.out.println("MAIN_END");
    }

    private static String childOutput(String mode)throws IOException,InterruptedException {
        var java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        var process=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),DirectorShutdownDrainTests.class.getName(),mode).redirectErrorStream(true).start();
        try{
            if(!process.waitFor(5,TimeUnit.SECONDS))throw new AssertionError("owned child JVM timeout");
            String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            check(process.exitValue()==0,"new child JVM exited zero");return output;
        }finally{if(process.isAlive())process.destroyForcibly();}
    }

    private static void actualJvmExitCounterexample()throws IOException,InterruptedException {
        String old=childOutput("unawaited");
        check(old.contains("CLOSE_REQUESTED_CALLBACK_REGISTERED")&&old.contains("MAIN_END"),"old close request and callback registration really ran");
        check(!old.contains("CLOSE_CALLBACK_"),"actual JVM exited0 with pending daemon close and no terminal callback");
        String fixed=childOutput("bounded");
        check(fixed.contains("CLOSE_CALLBACK_PASS")&&!fixed.contains("CLOSE_CALLBACK_FAILED"),"bounded shutdown child observed actual completion");
    }

    public static void main(String[] args)throws InterruptedException,IOException {
        if(args.length==1){child(args[0].equals("bounded"));return;}
        actualJvmExitCounterexample();actualDaemonCompletion();timeoutPreservesPending();exceptionalCompletionIsNotSuccess();monotonicWrapAndEarlyReturn();
        System.out.println("PASS bounded shutdown drain assertions="+assertions);
        System.out.println("Scope: local daemon/virtual-worker concurrency; Minecraft/J04 native shutdown and cold readback NOT_RUN.");
    }
}
