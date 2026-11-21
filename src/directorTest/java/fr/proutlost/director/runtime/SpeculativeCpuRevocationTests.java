package fr.proutlost.director.runtime;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual owned CPU worker interruption; transient sites are declared synthetic
 * actor fixtures. Native external fail/session hooks require pinned MC execution. */
public final class SpeculativeCpuRevocationTests {
 private static int assertions;
 private static void check(boolean yes,String label){assertions++;if(!yes)throw new AssertionError(label);}
 private static void stage(String stage)throws Exception{
  var registry=new SpeculativeCpuRegistry();var sites=new HashMap<String,String>();sites.put(stage,"synthetic unpinned selection");
  var started=new CountDownLatch(1);var interrupted=new CountDownLatch(1);var closed=new AtomicBoolean();var discarded=new AtomicInteger();
  try(var worker=new BoundedDecisionWorker("P008-revocation-"+stage)){
   var currentWithoutJob=new Object();var work=worker.submit(()->{started.countDown();try{new CountDownLatch(1).await(3,TimeUnit.SECONDS);}catch(InterruptedException stop){interrupted.countDown();Thread.currentThread().interrupt();throw new CancellationException("authority revoked");}return "unexpected late result";}).orElseThrow();
   check(started.await(2,TimeUnit.SECONDS),stage+" real worker reaches owned calculation boundary");
   registry.track(currentWithoutJob,()->{work.cancel();discarded.addAndGet(sites.size());sites.clear();registry.completed(currentWithoutJob);});
   // Original external guard returned without polling/cancelling the capture.
   check(!work.done()&&sites.size()==1&&registry.pending()==1,"before external revocation callback, obsolete CPU/site remain retained");
   registry.revokeAll();
   check(!closed.get()&&sites.isEmpty()&&discarded.get()==1,"transient sites discarded on actor before world/runtime close");
   check(work.done()&&work.metrics().cancelled(),"late pure result invalidated immediately");
   check(interrupted.await(2,TimeUnit.SECONDS),"owned running computation really interrupted before close");
   check(registry.pending()==0&&registry.revoked(),"all unpublished owners released and scope permanently fenced");
   try{work.result();throw new AssertionError("revoked pure result accepted");}catch(CancellationException expected){assertions++;}
   registry.revokeAll();check(discarded.get()==1,"repeat fail/session notification never re-discards completed selection");
   try{registry.track(new Object(),()->{});throw new AssertionError("new capture accepted after revocation");}catch(CancellationException expected){assertions++;}
   closed.set(true);
  }
 }
 private static void queuedWork()throws Exception{
  var scope=new SpeculativeCpuRegistry();var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);var ranQueued=new AtomicBoolean();var discarded=new AtomicInteger();
  try(var worker=new BoundedDecisionWorker("P008-revocation-queued")){
   var active=worker.submit(()->{entered.countDown();try{new CountDownLatch(1).await(3,TimeUnit.SECONDS);}catch(InterruptedException stopped){interrupted.countDown();throw new CancellationException();}return 1;}).orElseThrow();
   check(entered.await(2,TimeUnit.SECONDS),"real running worker owns first slot");
   var queued=worker.submit(()->{ranQueued.set(true);return 2;}).orElseThrow();
   scope.track(new Object(),()->{active.cancel();discarded.incrementAndGet();});scope.track(new Object(),()->{queued.cancel();discarded.incrementAndGet();});
   scope.revokeAll();check(interrupted.await(2,TimeUnit.SECONDS),"registry interrupts current calculation before workerclose");
   check(queued.done()&&queued.metrics().cancelled()&&!ranQueued.get(),"obsolete queued final calculation never starts");
   check(discarded.get()==2&&scope.pending()==0,"running and queued owners both discard transient selection");
  }
 }
 private static void allOwnersAndRetry(){
  var scope=new SpeculativeCpuRegistry();var first=new String("same");var second=new String("same");var automaticCurrent=new Object();var published=new Object();var count=new AtomicInteger();var durableAck=new AtomicInteger();
  scope.track(first,count::incrementAndGet);scope.track(second,count::incrementAndGet);scope.track(automaticCurrent,count::incrementAndGet);
  scope.track(published,()->{throw new AssertionError("published event lifecycle must stay with its legitimate owner");});scope.completed(published);
  scope.revokeAll();check(count.get()==3&&scope.pending()==0,"identity-based registry covers distinct equal job keys plus automatic current outside job map");
  durableAck.incrementAndGet();check(durableAck.get()==1,"speculative cancellation does not close or remove legitimate ACK drain");
  var retries=new SpeculativeCpuRegistry();var calls=new AtomicInteger();var success=new AtomicInteger();
  retries.track(new Object(),()->{if(calls.incrementAndGet()==1)throw new IllegalStateException("declared transient discard failure");});
  retries.track(new Object(),success::incrementAndGet);
  try{retries.revokeAll();throw new AssertionError("failed discard declared successful");}catch(IllegalStateException expected){assertions++;}
  check(retries.pending()==1&&success.get()==1,"one discard failure does not prevent all other owners; failed handle retained");
  retries.revokeAll();check(calls.get()==2&&success.get()==1&&retries.pending()==0,"retry releases failed transient state without replaying already released owners");
 }
 public static void main(String[]args)throws Exception{
  stage("FIRST_PURE");stage("FINAL_PURE");queuedWork();allOwnersAndRetry();
  System.out.println("PASS SpeculativeCpuRevocationTests assertions="+assertions+" scope=ACTUAL_CPU_INTERRUPTION_SYNTHETIC_TRANSIENT_SITES_NOT_NATIVE_RUNTIME");
 }
}
